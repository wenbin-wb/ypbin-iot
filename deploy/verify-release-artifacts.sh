#!/usr/bin/env bash
# 一键校验 ypbin-iot 的 GitHub Release 制品（issue #234 的部署侧校验入口）。
#
# 它做什么：
#   1) 取该 Release 的 jars.manifest.sha256（含 `# git: <sha>` 与 `# built: <UTC>` 头）；
#   2) 逐个 jar 核对 sha256（sha256sum -c）；
#   3) 断言清单确实是 6 个服务 jar（与本仓 release-artifacts.yml 的产出契约一致）；
#   4) 可选：把清单首部的 git sha 与你期望部署的提交比对（--git）。
#
# 用法：
#   # A. 已经手工/别处下载好制品，校验本地目录
#   deploy/verify-release-artifacts.sh --dir /tmp/release-v1.0.0 --git <期望的40位sha>
#
#   # B. 直接从 GitHub Release 下载再校验（需要 curl；无需 gh）
#   deploy/verify-release-artifacts.sh --tag v1.0.0 --dir /tmp/release-v1.0.0
#
#   # C. 两者都不给：按“当前目录已放好制品”校验
#   deploy/verify-release-artifacts.sh
#
# 退出码：0=校验通过；非 0=失败（缺文件/校验不符/数量不符/git sha 不符/下载失败）。
# 说明：下载走 github.com 的公开 Release 资产地址，不需要 token；私有/限流环境请用模式 A。
set -euo pipefail

REPO_DEFAULT="wenbin-wb/ypbin-iot"
MANIFEST_NAME="jars.manifest.sha256"
EXPECTED_JAR_COUNT=6

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

die() { echo "ERROR: $*" >&2; exit 1; }

dir=""
tag=""
expected_git=""
repo="$REPO_DEFAULT"

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--dir) dir="${2:-}"; shift 2 ;;
    -t|--tag) tag="${2:-}"; shift 2 ;;
    -g|--git) expected_git="${2:-}"; shift 2 ;;
    -r|--repo) repo="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -z "$dir" ]; then
  dir="$(pwd)"
fi

if [ -n "$tag" ]; then
  mkdir -p "$dir"
  base="https://github.com/${repo}/releases/download/${tag}"
  if [ ! -f "$dir/$MANIFEST_NAME" ]; then
    echo "下载清单：${base}/${MANIFEST_NAME}"
    curl -fL --retry 3 --retry-delay 2 -o "$dir/$MANIFEST_NAME" "$base/$MANIFEST_NAME" \
      || die "下载 $MANIFEST_NAME 失败（tag=${tag} repo=${repo}）"
  else
    echo "已存在本地清单，跳过下载：$dir/$MANIFEST_NAME"
  fi
  # 清单里是 basename；逐个补齐缺失的 jar
  while IFS= read -r jar; do
    [ -n "$jar" ] || continue
    if [ ! -f "$dir/$jar" ]; then
      echo "下载制品：${base}/${jar}"
      curl -fL --retry 3 --retry-delay 2 -o "$dir/$jar" "$base/$jar" \
        || die "下载 ${jar} 失败"
    fi
  done < <(grep -E '^[0-9a-f]{64}  .*\.jar$' "$dir/$MANIFEST_NAME" | awk '{print $2}')
fi

[ -f "$dir/$MANIFEST_NAME" ] || die "找不到清单 $dir/$MANIFEST_NAME（用 --dir 指定制品目录，或 --tag 指定 Release）"

echo "== 清单来源与构建信息 =="
grep -E '^# (git|built):' "$dir/$MANIFEST_NAME" || true
manifest_git="$(grep -E '^# git:' "$dir/$MANIFEST_NAME" | awk '{print $3}' | head -1)"

jar_lines="$(grep -E '^[0-9a-f]{64}  .*\.jar$' "$dir/$MANIFEST_NAME" || true)"
[ -n "$jar_lines" ] || die "清单里没有任何 jar 条目：$dir/$MANIFEST_NAME"
count="$(printf '%s\n' "$jar_lines" | wc -l | tr -d ' ')"
echo "== 清单声明的可执行 jar 数：$count（期望 ${EXPECTED_JAR_COUNT}）=="
[ "$count" -eq "$EXPECTED_JAR_COUNT" ] || die "jar 数量与产出契约不符（${count} != ${EXPECTED_JAR_COUNT}）"

echo "== 逐个核对 sha256 =="
( cd "$dir" && sha256sum -c <(printf '%s\n' "$jar_lines") ) || die "sha256 校验失败（制品与清单不符）"

if [ -n "$expected_git" ]; then
  echo "== 核对「制品来自哪个提交」=="
  [ -n "$manifest_git" ] || die "清单首部缺 '# git:'，无法核对提交"
  if [ "$manifest_git" != "$expected_git" ]; then
    die "清单 git=${manifest_git}，期望=${expected_git} —— 制品不是你要部署的提交，禁止上线"
  fi
  echo "git sha 一致：${manifest_git}"
fi

echo "OK：${count} 个 jar 的 sha256 全部通过"
