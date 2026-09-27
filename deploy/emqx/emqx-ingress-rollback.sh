#!/usr/bin/env bash
# =============================================================================
# emqx-ingress-rollback.sh —— 撤销 MQTT **入站**链路（EMQX 侧规则/动作 + 反向通道）
# =============================================================================
# 在**中间件机**上运行；`--tunnel` 另需在生产机上执行（删除反向隧道单元）。
#
# 撤销范围与**不**撤销的东西（先说清楚，避免误伤）：
#   撤销：① 规则 ypbin_up_property；② 动作（桥接）ypbin-ingress；③ 中间件机中继 socket 与防火墙放行
#   不撤销：EMQX 本身的认证/ACL（`no_match=deny`、内置库、设备/服务账号规则）、既有管理面出向隧道
#           （emqx-tunnel.service）、平台容器与数据 —— 入站停掉不影响下行与凭据同步
#
# 顺序是硬约束（实测）：**先删规则再删动作**——EMQX 拒绝删除仍被规则引用的桥接（HTTP 400）。
#
# 平台侧同时要做的事（本脚本不代办，因为它们不在中间件机上）：
#   · 生产机：`systemctl disable --now emqx-ingress-tunnel.service`（删掉反向转发 18084）
#   · 设备侧：把上报切回既有 HTTP 通道（`POST /internal/readings`），否则数据会停
#   · 数据：入站专用表 `iot_mqtt_ingest_receipt` 可保留（无害）；真要删见
#           deploy/sql/rollback/2026-10-01-iot-mqtt-ingest-receipt-rollback.sql
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
BASE_URL=http://127.0.0.1:18093
BRIDGE_NAME=ypbin-ingress
RULE_NAME=ypbin_up_property
# 段 B 的回执链路（up/reply → /internal/command-replies）也要一起撤，否则回滚后回执桥仍指着已回滚的端点重试
REPLY_BRIDGE_NAME=ypbin-reply
REPLY_RULE_NAME=ypbin_up_reply
PURGE_TUNNEL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --tunnel)   PURGE_TUNNEL=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"

PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
trap 'rm -rf "$PRIV"' EXIT
printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }

api() {
  local method="$1" path="$2" code
  code="$(curl -s --max-time 10 --connect-timeout 3 -K "$PRIV/curlrc" -o "$PRIV/out" \
          -w '%{http_code}' -X "$method" "$BASE_URL$path")"
  API_CODE="$code"; API_BODY="$(cat "$PRIV/out")"
}

echo "=== 1) 先删规则（EMQX 拒绝删除仍被引用的动作）==="
api GET /api/v5/rules
rule_ids="$(printf '%s' "$API_BODY" | python3 -c '
import json, sys
rules = json.load(sys.stdin).get("data", [])
wanted = set(sys.argv[1:])
print(" ".join(r["id"] for r in rules if r.get("name") in wanted))
' "$RULE_NAME" "$REPLY_RULE_NAME")"
if [ -z "$rule_ids" ]; then
  ok "无同名规则（幂等）"
else
  for id in $rule_ids; do
    api DELETE "/api/v5/rules/$id"
    case "$API_CODE" in 204|404) ok "DELETE rules/$id（HTTP $API_CODE）";; *) bad "DELETE rules/$id：HTTP $API_CODE";; esac
  done
fi

echo "=== 2) 删动作（桥接：属性 + 回执）==="
for name in "$BRIDGE_NAME" "$REPLY_BRIDGE_NAME"; do
  api DELETE "/api/v5/bridges/webhook:$name"
  case "$API_CODE" in 204|404) ok "DELETE bridges/webhook:$name（HTTP $API_CODE）";; *) bad "DELETE bridge $name：HTTP $API_CODE；响应=$API_BODY";; esac
done

echo "=== 3) 中间件机：停中继与防火墙放行（保留单元文件，便于回滚回来）==="
systemctl disable --now emqx-ingress-relay.socket >/dev/null 2>&1 && ok "emqx-ingress-relay.socket 已停用" || bad "停 emqx-ingress-relay.socket 失败"
systemctl disable --now emqx-ingress-firewall.service >/dev/null 2>&1 && ok "emqx-ingress-firewall.service 已停用（iptables 放行规则随 ExecStop 删除）" || bad "停 emqx-ingress-firewall.service 失败"
if iptables -C INPUT -s 172.28.0.0/16 -p tcp --dport 18084 -j ACCEPT 2>/dev/null; then
  bad "iptables 放行规则仍在（ExecStop 未生效）——请手工删除，否则端口仍对 emqx-edge 网桥开放"
else
  ok "iptables 放行规则已删除（回读 -C 未命中）"
fi

if [ "$PURGE_TUNNEL" -eq 1 ]; then
  echo "=== 4) 生产机反向隧道（本机执行 systemctl 会失败，需在生产机跑）==="
  bad "--tunnel 需要**在生产机**上执行：systemctl disable --now emqx-ingress-tunnel.service"
fi

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（入站链路已撤销，下行/认证/ACL 未受影响）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
