#!/usr/bin/env bash
# =============================================================================
# emqx-ingress-init.sh —— MQTT 入站（EMQX → 平台）的规则/动作配置（**幂等**，失败即显式报错）
# =============================================================================
# 在**中间件机**上运行。前提：
#   1) /opt/emqx/.env 存在且 API Key 可用（同 emqx-init.sh 的前置）
#   2) /opt/emqx/ingress/internal-token（600）存在 = 平台 INTERNAL_TOKEN
#      ——由部署步骤从**生产机** deploy/.env 经 stdin 管道写入，绝不入库/进日志
#   3) 反向通道已就绪：生产机 emqx-ingress-tunnel.service（-R 127.0.0.1:18084）
#      + 中间件机 emqx-ingress-relay.socket（172.28.0.1:18084 → 127.0.0.1:18084）
#      ⇒ EMQX 容器要访问平台，只能走 172.28.0.1:18084（容器到不了宿主回环）
#
# 做三件事：
#   A. 前置断言（API Key 可用、token 文件可读、反向通道连通——**不通就直接停**）
#   B. 幂等 upsert：Webhook 动作（桥接）+ 规则（SQL 规格化 + 身份一致性校验）
#   C. 回读断言：动作的关键参数（含 max_buffer_bytes=16MB）、规则启用且挂上动作、动作状态 connected
#
# 设计依据：docs/EMQX-INGRESS-DESIGN.md §6.1/§6.5/§8.5（H6/H10）、docs/EMQX-INTEGRATION.md §2/§3
# 回滚：deploy/emqx/emqx-ingress-rollback.sh
#
# 凭据纪律：本脚本回显的一切输出都经 redact()——把 token 值替换成 *** 后再打印
#   （动作的 headers 里就有 token，直接打印桥接 JSON 等于把内部凭证写进终端/日志）。
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
TOKEN_FILE=/opt/emqx/ingress/internal-token
BASE_URL=http://127.0.0.1:18093
# EMQX 容器可达的平台地址（**不是** 127.0.0.1:18084——那是宿主回环，容器看不到）
INGRESS_URL=http://172.28.0.1:18084/internal/mqtt/readings
BRIDGE_NAME=ypbin-ingress
RULE_NAME=ypbin_up_property
UP_TOPIC_FILTER='ypbin/v1/+/+/up/property'
# H6：默认 256MB 在中间件机上过大（阶段②压测时容器 mem_limit=2g），显式下调到 16MB
MAX_BUFFER_BYTES=16MB
REQUEST_TTL=30s
MAX_RETRIES=2

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file)   ENV_FILE="$2"; shift 2 ;;
    --token-file) TOKEN_FILE="$2"; shift 2 ;;
    --base-url)   BASE_URL="$2"; shift 2 ;;
    --ingress-url) INGRESS_URL="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE（先跑 gen-env.sh）" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"

CURL_MAXTIME=10
CURL_CONNECT_TIMEOUT=3
PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
cleanup_priv() { rm -rf "$PRIV"; }
trap cleanup_priv EXIT
printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"

fail=0
ok()   { printf '  ✅ %s\n' "$1"; }
bad()  { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# redact <file>：把 stdin 里的 token 值替换成 ***（token 只从文件读，不进 argv）
redact() {
  python3 -c '
import sys
try:
    tok = open(sys.argv[1]).read().strip()
except OSError:
    tok = ""
data = sys.stdin.read()
sys.stdout.write(data.replace(tok, "***") if tok else data)
' "$1"
}

api() { # api <method> <path> [json-file]
  local method="$1" path="$2" data="${3:-}"
  local args=(-s --max-time "$CURL_MAXTIME" --connect-timeout "$CURL_CONNECT_TIMEOUT" \
              -K "$PRIV/curlrc" -o "$PRIV/out" -w '%{http_code}' -X "$method"
              -H 'Content-Type: application/json')
  if [ -n "$data" ]; then args+=(--data-binary "@$data"); fi
  API_CODE="$(curl "${args[@]}" "$BASE_URL$path")"
  API_BODY="$(cat "$PRIV/out")"
}
expect() { # expect <desc> <want-code>
  if [ "$API_CODE" = "$2" ]; then ok "$1（HTTP $API_CODE）"; else bad "$1：期望 HTTP $2，实得 $API_CODE；响应=$(printf '%s' "$API_BODY" | redact "$TOKEN_FILE")"; fi
}

echo "=== A. 前置断言 ==="
api GET /api/v5/status
if [ "$API_CODE" = "200" ]; then ok "REST API Key 可用"; else bad "REST API Key 不可用（HTTP $API_CODE）"; echo "结论: FAIL"; exit 1; fi

if [ -s "$TOKEN_FILE" ]; then
  # 只回显长度，不回显值
  ok "内部凭证文件可读（长度 $(wc -c <"$TOKEN_FILE") 字节；值不打印）"
else
  bad "读不到内部凭证文件 $TOKEN_FILE（部署前置缺失：需从生产机 deploy/.env 取 INTERNAL_TOKEN 写入 600 文件）"
  echo "结论: FAIL（缺内部凭证，后续断言无意义）"; exit 1
fi

# 反向通道连通性：从**容器内**访问平台入站端点。没有它，桥接只会"连接超时"，
# 排障时极易被误判成平台没起。期望 400/401/200 都算「通」（端点存在且有响应），
# 只有连不上/超时才 FAIL。
probe_code="$(docker exec ypbin-emqx curl -s -o /dev/null -m 8 -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' --data-binary '{}' "$INGRESS_URL" 2>/dev/null || echo 000)"
case "$probe_code" in
  000) bad "容器内访问 $INGRESS_URL 不通（检查生产机 emqx-ingress-tunnel.service 与中间件机 emqx-ingress-relay.socket）" ;;
  200|400|401|404|503) ok "容器内可达平台入站端点（HTTP $probe_code = 通）" ;;
  *) bad "容器内访问 $INGRESS_URL 得到非预期状态码 HTTP $probe_code" ;;
esac

echo
echo "=== B. 幂等 upsert：Webhook 动作 + 规则 ==="

# ── B0 先删**规则**再删动作（顺序是硬约束，实测踩过）──────────────────────────────
# EMQX 拒绝删除仍被规则引用的桥接：先删桥接会得到 400（而不是 204），脚本在这一步就会红。
# 正确顺序：找同名规则 → 删规则 → 删桥接 → 建桥接 → 建规则。
api GET /api/v5/rules
existing_id="$(printf '%s' "$API_BODY" | python3 -c '
import json, sys
try:
    rules = json.load(sys.stdin).get("data", [])
except Exception:
    rules = []
print(next((r["id"] for r in rules if r.get("name") == sys.argv[1]), ""))
' "$RULE_NAME")"
if [ -n "$existing_id" ]; then
  api DELETE "/api/v5/rules/$existing_id"
  case "$API_CODE" in 204|404) ok "DELETE rules/$existing_id（幂等前置清理，HTTP $API_CODE）";; *) bad "DELETE rule：HTTP $API_CODE";; esac
else
  info "无同名规则，跳过清理"
fi

# ── B1 动作（桥接）：先删后建（POST 对已存在名字的语义未核实 ⇒ 不赌）────────────────
api DELETE "/api/v5/bridges/webhook:$BRIDGE_NAME"
case "$API_CODE" in
  204|404) ok "DELETE bridges/webhook:$BRIDGE_NAME（幂等前置清理，HTTP $API_CODE）" ;;
  *) bad "DELETE bridge：HTTP $API_CODE（提示：若仍是 400，检查是否还有别的规则引用它——本脚本只清理同名规则）" ;;
esac

# body 模板：把一条上行消息规格化成**单元素 items**（P0 契约：一条消息 = 一台设备的一个点位）
BODY_TPL="{\"requestId\":\"\${requestId}\",\"items\":[{\"deviceId\":\"\${deviceId}\",\"propertyId\":\"\${propertyId}\",\"value\":\"\${value}\",\"quality\":\"\${quality}\",\"ts\":\${ts},\"pollIntervalMs\":\${pollIntervalMs}}]}"
python3 - "$PRIV/bridge.json" "$INGRESS_URL" "$TOKEN_FILE" "$BODY_TPL" "$MAX_BUFFER_BYTES" "$REQUEST_TTL" "$MAX_RETRIES" <<'PY'
import json, sys
out, url, token_file, body, maxbuf, ttl, retries = sys.argv[1:8]
token = open(token_file).read().strip()
payload = {
    "type": "webhook",
    "name": "ypbin-ingress",
    "url": url,
    "method": "post",
    "headers": {"content-type": "application/json", "X-Internal-Token": token},
    "body": body,
    "max_retries": int(retries),
    "connect_timeout": "5s",
    "resource_opts": {
        "max_buffer_bytes": maxbuf,
        "query_mode": "async",
        "request_ttl": ttl,
        "inflight_window": 1,
        "health_check_interval": "15s",
    },
}
open(out, "w").write(json.dumps(payload))
PY
chmod 600 "$PRIV/bridge.json"
api POST /api/v5/bridges "$PRIV/bridge.json"
expect "POST bridges（webhook 动作：指向平台 /internal/mqtt/readings，带 X-Internal-Token）" 201

# 回读断言：只打印**脱敏后**的 JSON（headers 里有 token）
api GET "/api/v5/bridges/webhook:$BRIDGE_NAME"
expect "GET bridges/webhook:$BRIDGE_NAME（回读）" 200
printf '%s' "$API_BODY" >"$PRIV/bridge_read.json"
python3 - "$PRIV/bridge_read.json" "$INGRESS_URL" "$MAX_BUFFER_BYTES" "$REQUEST_TTL" "$MAX_RETRIES" <<'PY' || fail=1
import json, sys
d = json.load(open(sys.argv[1]))
url, maxbuf, ttl, retries = sys.argv[2:6]
problems = []
if d.get("url") != url: problems.append(f"url={d.get('url')}（期望 {url}）")
if str(d.get("method", "")).lower() != "post": problems.append(f"method={d.get('method')}")
if int(d.get("max_retries", -1)) != int(retries): problems.append(f"max_retries={d.get('max_retries')}")
ro = d.get("resource_opts") or {}
if ro.get("max_buffer_bytes") != maxbuf: problems.append(f"max_buffer_bytes={ro.get('max_buffer_bytes')}（期望 {maxbuf}，H6 要求显式下调）")
if ro.get("query_mode") != "async": problems.append(f"query_mode={ro.get('query_mode')}")
if ro.get("inflight_window") != 1: problems.append(f"inflight_window={ro.get('inflight_window')}")
if str(ro.get("request_ttl")) != ttl: problems.append(f"request_ttl={ro.get('request_ttl')}（期望 {ttl}）")
if "X-Internal-Token" not in (d.get("headers") or {}): problems.append("headers 缺少 X-Internal-Token")
if problems:
    print("  ❌ 动作参数回读不符：" + "；".join(problems)); sys.exit(3)
print("  ✅ 动作参数回读一致（url/method/max_retries/max_buffer_bytes/query_mode/inflight_window/request_ttl/headers 含内部凭证头）")
PY

python3 - "$PRIV/rule.json" "$RULE_NAME" "$BRIDGE_NAME" "$UP_TOPIC_FILTER" <<'PY'
import json, sys
out, rule_name, bridge_name, topic_filter = sys.argv[1:5]
# 规则 SQL：把一条上行消息规格化成薄适配端点需要的字段；WHERE 做**身份一致性校验**
#   —— ACL 已挡住越权发布，这里是纵深防御（防 ACL 配置漂移导致越权写）。
sql = (
    "SELECT"
    " nth(6, tokens(topic, '/')) AS kind,"
    " nth(3, tokens(topic, '/')) AS tenantId,"
    " nth(4, tokens(topic, '/')) AS deviceId,"
    " payload.requestId AS requestId,"
    " payload.propertyId AS propertyId,"
    " payload.value AS value,"
    " payload.quality AS quality,"
    " payload.ts AS ts,"
    " payload.pollIntervalMs AS pollIntervalMs"
    f' FROM "{topic_filter}"'
    " WHERE nth(3, tokens(topic, '/')) = nth(1, tokens(username, '.'))"
    " AND nth(4, tokens(topic, '/')) = nth(2, tokens(username, '.'))"
)
payload = {
    "name": rule_name,
    "sql": sql,
    "actions": [f"webhook:{bridge_name}"],
    "enable": True,
    "description": "ypbin 设备上行属性上报 → 平台薄适配端点（设计 §6.1 主推路径）",
}
open(out, "w").write(json.dumps(payload))
PY
chmod 600 "$PRIV/rule.json"
api POST /api/v5/rules "$PRIV/rule.json"
expect "POST rules（SQL 规格化 + 身份一致性校验 → 动作）" 201
rule_id="$(printf '%s' "$API_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)"

api GET /api/v5/rules
printf '%s' "$API_BODY" | python3 -c '
import json, sys
rules = json.load(sys.stdin).get("data", [])
hit = [r for r in rules if r.get("name") == sys.argv[1]]
if not hit:
    print("  ❌ 回读不到规则 " + sys.argv[1]); sys.exit(3)
r = hit[0]
acts = r.get("actions") or []
if not r.get("enable"):
    print("  ❌ 规则未启用"); sys.exit(3)
suffix = ":" + sys.argv[2]
# 实测：POST 时用 `webhook:<name>`（v1 名），回读会归一成 `http:<name>`（v2 名）⇒ 按后缀判定
if not any(a.endswith(suffix) for a in acts):
    print("  ❌ 规则未挂上动作：" + str(acts)); sys.exit(3)
print("  ✅ 规则 id=%s enable=%s actions=%s" % (r["id"], r["enable"], acts))
' "$RULE_NAME" "$BRIDGE_NAME" || fail=1

echo
echo "=== C. 自检：动作健康状态（走真实链路打平台端点）==="
if [ -n "${rule_id:-}" ]; then
  info "规则 id = $rule_id（回滚时用它 DELETE /api/v5/rules/<id>）"
fi
# 健康检查会带上配置里的 headers ⇒ 同时验证「反向通道通 + 内部凭证被平台接受」，
# 这是本脚本唯一能自证「配置真的生效」的判据（否则只会"绿色通过"但实际不可用）
sleep 2
api GET "/api/v5/bridges/webhook:$BRIDGE_NAME"
status="$(printf '%s' "$API_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null)"
reason="$(printf '%s' "$API_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status_reason",""))' 2>/dev/null)"
if [ "$status" = "connected" ]; then
  ok "动作状态 = connected（健康检查已打通：反向通道 + 内部凭证均被平台接受）"
else
  bad "动作状态 = $status（reason=$(printf '%s' "$reason" | redact "$TOKEN_FILE")）⇒ 链路或凭证未生效"
fi

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（入站规则/动作已生效且链路自检通过）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
