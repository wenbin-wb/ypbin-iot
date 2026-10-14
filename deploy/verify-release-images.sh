#!/usr/bin/env bash
# 校验 ypbin-iot 的 Release **容器镜像制品**（issue #234「镜像制品化」的部署侧校验入口）。
#
# 它做什么：
#   1) 取该 Release 的 images.digests.tsv（每服务一行：服务名、本地镜像名、GHCR 名、tags、
#      registry digest、image id、tar 文件名、tar sha256）；
#   2) 取 images.manifest.sha256 并逐个 *.tar.gz 核对 sha256（sha256sum -c）；
#   3) 断言恰好 6 个服务、digest 形如 sha256:<64hex>、tar 存在、且**两份清单互证**（tar sha256 一致）；
#   4) 可选 --git：清单首部的 "# git:" 必须等于你要部署的提交；
#   5) 可选 --load：docker load 每个 tar，断言镜像存在、image id 与清单一致；给了 --jars-manifest 时，
#      再核对**镜像内 /app/app.jar 的 sha256 == jar 清单里的 sha256**（证明镜像真的装了那个 jar）；
#   6) 可选 --pull：docker pull <ghcr_repo>@<digest>，断言拉取到的 RepoDigests 含清单 digest。
#
# 用法：
#   # A. 已下载好 tar + 清单，只校验文件
#   deploy/verify-release-images.sh --dir /tmp/release-v1.0.0 --git <期望40位sha>
#   # B. 直接从 GitHub Release 下载再校验（需要 curl；无需 gh）
#   deploy/verify-release-images.sh --tag v1.0.0 --dir /tmp/release-v1.0.0
#   # C. 部署机常用：本地 load 并核对镜像内 jar
#   deploy/verify-release-images.sh --dir /tmp/release-v1.0.0 --load \
#     --jars-manifest /tmp/release-v1.0.0/jars.manifest.sha256
#   # D. GHCR 主路径：核对可拉取且 digest 一致（需先 docker login ghcr.io）
#   deploy/verify-release-images.sh --tag v1.0.0 --pull
#
# 退出码：0=通过；非 0=失败（缺文件/checksum 不符/数量不符/git 不符/镜像缺失/镜像内 jar 不符/拉取 digest 不符）。
# 说明：下载走 github.com 的公开 Release 资产地址，不需要 token；私有/限流环境请用模式 A。
# 边界：--pull 需要能访问 ghcr.io；私有包还需 read:packages 的 PAT（docker login ghcr.io）。
set -euo pipefail

REPO_DEFAULT="wenbin-wb/ypbin-iot"
DIGESTS_NAME="images.digests.tsv"
TAR_MANIFEST_NAME="images.manifest.sha256"
EXPECTED_COUNT=6
DIGEST_RE='^sha256:[0-9a-f]{64}$'

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/,""); print} NR>1 && !/^#/ {exit}' "$0"; }
die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "== $* =="; }

dir=""
tag=""
expected_git=""
repo="$REPO_DEFAULT"
do_load=0
do_pull=0
allow_missing_digest=0
jars_manifest=""

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--dir) dir="${2:-}"; shift 2 ;;
    -t|--tag) tag="${2:-}"; shift 2 ;;
    -g|--git) expected_git="${2:-}"; shift 2 ;;
    -r|--repo) repo="${2:-}"; shift 2 ;;
    --load) do_load=1; shift ;;
    --pull) do_pull=1; shift ;;
    --jars-manifest) jars_manifest="${2:-}"; shift 2 ;;
    --allow-missing-digest) allow_missing_digest=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

[ -n "$dir" ] || dir="$(pwd)"

if [ -n "$tag" ]; then
  mkdir -p "$dir"
  base="https://github.com/${repo}/releases/download/${tag}"
  for name in "$DIGESTS_NAME" "$TAR_MANIFEST_NAME"; do
    if [ ! -f "$dir/$name" ]; then
      echo "下载清单：${base}/${name}"
      curl -fL --retry 3 --retry-delay 2 -o "$dir/$name" "${base}/${name}" \
        || die "下载 $name 失败（tag=${tag} repo=${repo}）"
    fi
  done
  # 清单里是 basename；逐个补齐缺失的 tar
  while IFS= read -r t; do
    [ -n "$t" ] || continue
    if [ ! -f "$dir/$t" ]; then
      echo "下载镜像 tar：${base}/${t}"
      curl -fL --retry 3 --retry-delay 2 -o "$dir/$t" "${base}/${t}" || die "下载 ${t} 失败"
    fi
  done < <(grep -E '^[0-9a-f]{64}  .*\.tar\.gz$' "$dir/$TAR_MANIFEST_NAME" | awk '{print $2}')
fi

[ -f "$dir/$DIGESTS_NAME" ] || die "找不到清单 $dir/$DIGESTS_NAME（用 --dir 指定制品目录，或 --tag 指定 Release）"
[ -f "$dir/$TAR_MANIFEST_NAME" ] || die "找不到清单 $dir/$TAR_MANIFEST_NAME"

info "清单来源与构建信息"
grep -E '^# (git|built|release-tag|version|registry):' "$dir/$DIGESTS_NAME" || true
manifest_git="$(grep -E '^# git:' "$dir/$DIGESTS_NAME" | awk '{print $3}' | head -1 || true)"
version="$(grep -E '^# version:' "$dir/$DIGESTS_NAME" | awk '{print $3}' | head -1 || true)"
[ -n "$version" ] || die "清单首部缺 '# version:'，无法确定镜像 tag"

rows="$(grep -vE '^#|^$' "$dir/$DIGESTS_NAME" || true)"
[ -n "$rows" ] || die "清单里没有任何服务行：$dir/$DIGESTS_NAME"
count="$(printf '%s\n' "$rows" | wc -l | tr -d ' ')"
info "清单声明的服务镜像数：${count}（期望 ${EXPECTED_COUNT}）"
[ "$count" -eq "$EXPECTED_COUNT" ] || die "镜像行数与产出契约不符（${count} != ${EXPECTED_COUNT}）"

# 逐行结构校验（digest 非空且合法、tar 文件在）
while IFS= read -r line; do
  [ -n "$line" ] || continue
  # 先把 tab 换成非空白分隔符再解析：bash 会把连续的 IFS 空白（含 tab）折叠，
  # 空列（如 GHCR 失败时的 digest）会让后面所有列整体错位。
  IFS='|' read -r svc local_img ghcr_repo tags digest image_id tar_file tar_sha <<< "${line//$'\t'/|}"
  [ -n "$tar_file" ] || die "${svc}: tar_file 为空"
  [ -n "$tar_sha" ] || die "${svc}: tar_sha256 为空"
  if [ "$allow_missing_digest" -eq 0 ]; then
    [ -n "$digest" ] || die "${svc}: digest 为空（GHCR 推送失败？只校验 tar 兜底请加 --allow-missing-digest）"
    printf '%s' "$digest" | grep -qE "$DIGEST_RE" || die "${svc}: digest 格式非法：『${digest}』"
  fi
  [ -f "$dir/$tar_file" ] || die "${svc}: 缺 tar 文件 $dir/$tar_file"
done < <(printf '%s\n' "$rows")

info "逐个核对 tar.gz 的 sha256"
tar_lines="$(grep -E '^[0-9a-f]{64}  .*\.tar\.gz$' "$dir/$TAR_MANIFEST_NAME" || true)"
[ -n "$tar_lines" ] || die "images.manifest.sha256 里没有任何 tar.gz 条目"
tar_count="$(printf '%s\n' "$tar_lines" | wc -l | tr -d ' ')"
[ "$tar_count" -eq "$EXPECTED_COUNT" ] || die "tar 条目数与产出契约不符（${tar_count} != ${EXPECTED_COUNT}）"
( cd "$dir" && sha256sum -c <(printf '%s\n' "$tar_lines") ) || die "tar.gz sha256 校验失败（制品与清单不符）"

# 两份清单互证：tar 的 sha256 在 images.manifest.sha256 与 images.digests.tsv 必须一致
while IFS= read -r line; do
  [ -n "$line" ] || continue
  # 先把 tab 换成非空白分隔符再解析：bash 会把连续的 IFS 空白（含 tab）折叠，
  # 空列（如 GHCR 失败时的 digest）会让后面所有列整体错位。
  IFS='|' read -r svc local_img ghcr_repo tags digest image_id tar_file tar_sha <<< "${line//$'\t'/|}"
  want="$(printf '%s\n' "$tar_lines" | awk -v f="$tar_file" '$2==f{print $1; exit}')"
  [ -n "$want" ] || die "${svc}: images.manifest.sha256 里没有 $tar_file"
  [ "$want" = "$tar_sha" ] || die "${svc}: 两份清单对 $tar_file 的 sha256 不一致（tsv=${tar_sha} manifest=${want}）"
done < <(printf '%s\n' "$rows")

if [ -n "$expected_git" ]; then
  info "核对「制品来自哪个提交」"
  [ -n "$manifest_git" ] || die "清单首部缺 '# git:'，无法核对提交"
  [ "$manifest_git" = "$expected_git" ] || die "清单 git=${manifest_git}，期望=${expected_git} —— 制品不是你要部署的提交，禁止上线"
  echo "git sha 一致：${manifest_git}"
fi

if [ "$do_load" -eq 1 ]; then
  command -v docker >/dev/null 2>&1 || die "--load 需要 docker，但本机找不到 docker"
  info "docker load 并核对镜像（tag=${version}）"
  if [ -n "$jars_manifest" ]; then
    [ -f "$jars_manifest" ] || die "--jars-manifest 指定的文件不存在：$jars_manifest"
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    IFS='|' read -r svc local_img ghcr_repo tags digest image_id tar_file tar_sha <<< "${line//$'\t'/|}"
    echo "-- ${svc}: docker load -i $dir/$tar_file"
    docker load -i "$dir/$tar_file" >/dev/null
    loaded_id="$(docker image inspect --format '{{.Id}}' "${local_img}:${version}")" \
      || die "${svc}: load 后找不到镜像 ${local_img}:${version}"
    if [ -n "$image_id" ]; then
      [ "$loaded_id" = "$image_id" ] || die "${svc}: 载入镜像 id=${loaded_id} 与清单 image_id=${image_id} 不一致"
    fi
    if [ -n "$jars_manifest" ]; then
      want_jar_sha="$(grep -E "^[0-9a-f]{64}  ${local_img}-.*\.jar$" "$jars_manifest" | awk '{print $1}' | head -1 || true)"
      [ -n "$want_jar_sha" ] || die "${svc}: jars.manifest 里找不到 ${local_img}-*.jar 的 sha256"
      tmp="$(mktemp -d)"
      cid="$(docker create "${local_img}:${version}")"
      docker cp "${cid}:/app/app.jar" "${tmp}/app.jar" >/dev/null
      docker rm -f "$cid" >/dev/null
      got_jar_sha="$(sha256sum "${tmp}/app.jar" | awk '{print $1}')"
      rm -rf "$tmp"
      [ "$got_jar_sha" = "$want_jar_sha" ] || die "${svc}: 镜像内 /app/app.jar（${got_jar_sha}）≠ jar 清单（${want_jar_sha}）"
      echo "   ${svc}: 镜像内 /app/app.jar 与 jar 清单一致"
    fi
  done < <(printf '%s\n' "$rows")
fi

if [ "$do_pull" -eq 1 ]; then
  command -v docker >/dev/null 2>&1 || die "--pull 需要 docker，但本机找不到 docker"
  info "docker pull 并核对 registry digest（需 ghcr.io 凭据/网络）"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    IFS='|' read -r svc local_img ghcr_repo tags digest image_id tar_file tar_sha <<< "${line//$'\t'/|}"
    [ -n "$digest" ] || die "${svc}: digest 为空，无法 --pull 核对（不要用 --allow-missing-digest）"
    docker pull "${ghcr_repo}@${digest}" >/dev/null || die "${svc}: docker pull ${ghcr_repo}@${digest} 失败"
    repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "${ghcr_repo}@${digest}" || true)"
    [ -n "$repo_digests" ] || die "${svc}: docker image inspect ${ghcr_repo}@${digest} 无输出"
    printf '%s' "$repo_digests" | grep -q "$digest" || die "${svc}: RepoDigests 不含 ${digest}：${repo_digests}"
    echo "   ${svc}: ${ghcr_repo}@${digest} 拉取一致"
  done < <(printf '%s\n' "$rows")
fi

echo "OK：${count} 个服务镜像的 tar sha256 / digest 清单全部通过"
