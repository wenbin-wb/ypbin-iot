#!/usr/bin/env bash
# =============================================================================
# gen-env.sh —— 在**目标机**上生成 /opt/emqx/.env（600）与 API Key 预置文件（600）
# =============================================================================
# 为什么要有这个脚本：真实凭据**绝不入库、绝不打印、绝不经手聊天/日志**。
# 做法是「值在目标机上就地生成，只落 600 的文件」。
#
# 幂等性：默认**不覆盖**已存在的 .env（避免把在跑的部署改坏）。
#   要轮换：`gen-env.sh --force`（会重新生成全部凭据 ⇒ 需要重建容器 + 重新初始化 ACL/账号）。
#
# 输出纪律：本脚本**只打印键名、值与长度指纹**（sha256 前 12 位），从不打印值本身。
# 用法：
#   bash gen-env.sh            # 首次生成（已存在则拒绝）
#   bash gen-env.sh --force    # 轮换全部凭据
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$DIR/.env"
APIKEY_DIR="$DIR/api-key"
APIKEY_FILE="$APIKEY_DIR/default_api_key.conf"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ -f "$ENV_FILE" ] && [ "$FORCE" -eq 0 ]; then
  echo "拒绝覆盖已存在的 $ENV_FILE（要轮换请加 --force）" >&2
  exit 1
fi

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令: $1" >&2; exit 1; }; }
need openssl

# ── 生成（16/24/32 字节 CSPRNG）───────────────────────────────────────────────
NODE_COOKIE="$(openssl rand -hex 16)"
DASH_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')"
API_KEY="$(openssl rand -hex 16)"
API_SECRET="$(openssl rand -base64 32 | tr -d '\n')"

# ── .env（600；写入前先 umask，避免短暂的 644 窗口）──────────────────────────
old_umask="$(umask)"
umask 077
cat >"$ENV_FILE" <<EOF
# 由 deploy/emqx/gen-env.sh 生成于 $(date -u +%FT%TZ)（UTC）
# 🔴 含真实凭据：600、不入库、不打印。文件在目标机 /opt/emqx/.env。
EMQX_IMAGE=docker.m.daocloud.io/emqx/emqx:5.8.9
EMQX_NODE_NAME=emqx@ypbin-emqx.local
EMQX_NODE_COOKIE=$NODE_COOKIE
EMQX_DASHBOARD_PASSWORD=$DASH_PASSWORD
EMQX_DASHBOARD_PORT=18093
EMQX_DASHBOARD_BIND_ADDR=127.0.0.1
EMQX_MQTT_BIND_ADDR=127.0.0.1
EMQX_MEM_LIMIT=2g
EMQX_API_KEY=$API_KEY
EMQX_API_SECRET=$API_SECRET
EOF
chmod 600 "$ENV_FILE"

# ── API Key 预置文件（官方格式 `api_key:api_secret[:role]`；缺 role 隐式 administrator）
# ⚠️ 实测坑：容器内 EMQX 以 `emqx` 用户（uid 1000）运行，文件若为 root:root 600 会
#    `failed_to_open_the_bootstrap_file, reason: Permission denied`（**只告警不致命**，
#    表现为 API Key 静默不可用）。故属主必须给 uid 1000。
EMQX_UID="${EMQX_UID:-1000}"
mkdir -p "$APIKEY_DIR"
printf '%s:%s\n' "$API_KEY" "$API_SECRET" >"$APIKEY_FILE"
# ⚠️ **不要**对 chown 加 `|| true`：属主不对 ⇒ 容器内（uid 1000）读不到 ⇒ EMQX 只在启动日志里
#    打印 `failed_to_open_the_bootstrap_file, reason: Permission denied`（**不致死不报错**），
#    结果是 API Key 静默失效。这里失败就**显式报错退出**（R6：禁静默降级）。
if ! chown "$EMQX_UID:$EMQX_UID" "$APIKEY_DIR" "$APIKEY_FILE"; then
  echo "chown 到容器 uid ($EMQX_UID) 失败 ⇒ API Key 预置文件容器内读不到，必须人工修正后再起容器" >&2
  exit 1
fi
chmod 700 "$APIKEY_DIR"
chmod 400 "$APIKEY_FILE"
# 自证：属主/权限就是容器内 EMQX 进程能读到的形态（uid 必须等于 EMQX_UID，权限必须为 400）
own="$(stat -c '%u:%g' "$APIKEY_FILE")"; perm="$(stat -c '%a' "$APIKEY_FILE")"
if [ "$own" != "$EMQX_UID:$EMQX_UID" ] || [ "$perm" != "400" ]; then
  echo "API Key 预置文件权限自证失败：owner=$own perm=$perm（期望 $EMQX_UID:$EMQX_UID / 400）" >&2
  exit 1
fi

# ── 只用「长度 + 指纹」回显，绝不回显值 ──────────────────────────────────────
echo "已生成（仅回显长度与指纹，值不打印）："
for pair in "EMQX_NODE_COOKIE:$NODE_COOKIE" "EMQX_DASHBOARD_PASSWORD:$DASH_PASSWORD" \
            "EMQX_API_KEY:$API_KEY" "EMQX_API_SECRET:$API_SECRET"; do
  name="${pair%%:*}"; val="${pair#*:}"
  printf '  %-26s len=%-3s sha256=%s\n' "$name" "${#val}" \
    "$(printf '%s' "$val" | sha256sum | cut -c1-12)"
done
printf '  %-26s %s (mode %s)\n' "$ENV_FILE" "已写入" "$(stat -c %a "$ENV_FILE")"
printf '  %-26s %s (mode %s, owner %s)\n' "$APIKEY_FILE" "已写入" "$(stat -c %a "$APIKEY_FILE")" "$(stat -c '%u:%g' "$APIKEY_FILE")"
printf '  %-26s %s\n' "API-Key 可用性" "**未在此校验**：起容器后由 emqx-init.sh 的 A00 断言（GET /api/v5/status=200）兜底"
umask "$old_umask"
