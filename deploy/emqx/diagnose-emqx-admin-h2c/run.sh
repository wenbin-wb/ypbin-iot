#!/usr/bin/env bash
# =============================================================================
# run.sh —— 复跑「平台 EMQX 管理面 HTTP/2 缺陷」判据（在**运维机**上运行）
# =============================================================================
# 做法：把探针源码传到生产机 → 用生产机的 javac 编译 → `docker cp` 进**平台容器** →
# 在容器里用**平台自己的 JDK 与 HttpClient**跑三种模式 → 清理。
#
# 为什么要放进容器跑：缺陷只在「JDK HttpClient 的 h2c upgrade」路径上复现，
# 用 curl 复现不了（curl 默认 HTTP/1.1，或用 --http2-prior-knowledge 走另一条路径）。
# 凭据从**容器自身的 env** 读（`EMQX_API_KEY`/`EMQX_API_SECRET`），不进 argv、不打印、不落盘。
#
# 只读性质：不改任何配置、不重启任何东西；只向一条**无人订阅的诊断主题**
# （`ypbin/diagnostic/h2c-probe`）发消息 ⇒ EMQX 回 202，不会打扰任何设备。
#
# 用法：
#   bash deploy/emqx/diagnose-emqx-admin-h2c/run.sh --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test"
# =============================================================================
set -uo pipefail

PROD_SSH=""
CONTAINER=ypbin-iot
JAVA_BIN=/opt/java/openjdk/bin/java
JAVAC_BIN=javac

while [ $# -gt 0 ]; do
  case "$1" in
    --prod-ssh)  PROD_SSH="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --java)      JAVA_BIN="$2"; shift 2 ;;
    --javac)     JAVAC_BIN="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done
[ -n "$PROD_SSH" ] || { echo "缺 --prod-ssh" >&2; exit 1; }

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$DIR/EmqxAdminH2cProbe.java"
[ -f "$SRC" ] || { echo "找不到 $SRC" >&2; exit 1; }

# shellcheck disable=SC2086
prodr() { ssh -o BatchMode=yes $PROD_SSH "$@"; }

echo "=== 0) 传源码 + 在生产机编译（本地低配机器不参与构建）==="
prodr "umask 077; mkdir -p /tmp/ypbin-h2c && cat > /tmp/ypbin-h2c/EmqxAdminH2cProbe.java" < "$SRC"
prodr "cd /tmp/ypbin-h2c && $JAVAC_BIN -d . EmqxAdminH2cProbe.java && ls -1 EmqxAdminH2cProbe.class" \
  || { echo "❌ 编译失败"; exit 1; }
prodr "docker cp /tmp/ypbin-h2c/EmqxAdminH2cProbe.class $CONTAINER:/tmp/ >/dev/null && echo '  ·  class 已放入容器'"

run_mode() {
  echo "=== mode=$1 ==="
  # 容器里 CLASSPATH 指向应用 jar，会禁用单文件/影响主类解析 ⇒ 显式 unset
  prodr "docker exec -i $CONTAINER sh -c 'cd /tmp && env -u CLASSPATH $JAVA_BIN EmqxAdminH2cProbe $1 2>&1'"
}

echo
run_mode h2
echo
run_mode h1
echo
run_mode warm

echo
echo "=== 清理 ==="
prodr "rm -rf /tmp/ypbin-h2c; echo '  ·  生产机临时目录已删'"
prodr "docker exec -i $CONTAINER sh -c 'rm -f /tmp/EmqxAdminH2cProbe.class; echo \"  ·  容器内 class 已删\"'"
echo
echo "判据：h2 ⇒ 3/3 EXC(EOF) 且 h1 ⇒ 3/3 HTTP 202 且 warm ⇒ 3/3 HTTP 202"
echo "      ⇒ 平台的 EMQX 管理面客户端在 HTTP/2 下发布必失败；HTTP/1.1 或预热后正常。"
