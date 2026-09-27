#!/usr/bin/env bash
# =============================================================================
# emqx-selfcheck.sh —— 阶段① 验收自检（**用数据说话**：EMQX 侧指标前后差值 + HTTP 状态码）
# =============================================================================
# 在**中间件机**上运行。设计依据：EMQX-INGRESS-DESIGN.md §8.5 第 8 步（负向自检）、H1/H2/H5。
#
# 覆盖的判据（每条都有"前后对照"，不做"没报错就算过"）：
#   S1 容器健康 + 节点 status
#   S2 宿主侧监听**只绑回环**（1883 / 18093）
#   S3 Dashboard 免鉴权健康端点 `/status` = 200
#   S4 REST 免鉴权 `/api/v5/status` = 200；**带 API Key** 的受保护端点也 = 200（H5：REST 不接受
#      Dashboard 用户凭据，必须 API Key）
#   S5 **旧默认口令 admin/public 必须被拒**（401）；`.env` 里的新口令可登录（200）（H2）
#   S6 匿名 MQTT 连接必须被拒（`packets.connack.auth_error` 增长；`client.auth.anonymous` 保持 0）
#   S7 设备 A publish 到**别人**的主题必须被拒（`packets.publish.auth_error` + `authorization.nomatch` 增长）
#   S8 设备 A publish 到**自己**的主题必须放行（`authorization.matched.allow` 增长，且 auth_error 不增）
#   S9 设备 A subscribe **别人**的 down 必须被拒（`packets.subscribe.auth_error` 增长）
#   S10 设备 A subscribe **自己**的 down 必须放行（`matched.allow` 增长）
#   S11 服务账号（username 无点）subscribe 通配 `ypbin/v1/+/+/up/#` 必须放行
#       —— 同时验证 `client_attrs_init` 的 `nth` 越界不会让服务账号路径失效（设计 U6/U19）
#
# 凭据纪律：临时测试账号的口令**只存在于本进程内存**（不落任何文件），
#           退出时 trap 删除全部临时账号与临时 ACL 规则；输出永不打印口令。
#
# 退出码：0 = 全部 PASS；1 = 有 FAIL
# 用法：bash emqx-selfcheck.sh [--env-file /opt/emqx/.env] [--base-url http://127.0.0.1:18093]
#                             [--bench-image <ref>] [--network emqx-edge] [--keep-probe-users]
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
BASE_URL=http://127.0.0.1:18093
CONTAINER=ypbin-emqx
# 官方压测工具 emqtt-bench（**pin 到具体版本 + digest**；`--pull=never` 保证测试不依赖网络）
BENCH_IMAGE=docker.m.daocloud.io/emqx/emqtt-bench:0.6.2
BENCH_DIGEST=sha256:b34b364859dab936507a73388fcf55d6a9fe422e17bbf36997e884500288dcf8
NETWORK=emqx-edge
HOSTNAME_IN_NET=ypbin-emqx
MQTT_PORT=1883
KEEP=0

TENANT=1001
DEV_A="${TENANT}.2001"
DEV_B="${TENANT}.2002"
SVC_PROBE=svc-probe
PROBE_TOPIC_SVC='ypbin/v1/+/+/up/#'

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file)   ENV_FILE="$2"; shift 2 ;;
    --base-url)   BASE_URL="$2"; shift 2 ;;
    --container)  CONTAINER="$2"; shift 2 ;;
    --bench-image) BENCH_IMAGE="$2"; shift 2 ;;
    --bench-digest) BENCH_DIGEST="$2"; shift 2 ;;
    --network)    NETWORK="$2"; shift 2 ;;
    --keep-probe-users) KEEP=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"
: "${EMQX_DASHBOARD_PASSWORD:?}"

AUTH_ID='password_based%3Abuilt_in_database'
PASS_N=0; FAIL_N=0

pass() { PASS_N=$((PASS_N+1)); printf '  ✅ %s\n' "$1"; }
fail() { FAIL_N=$((FAIL_N+1)); printf '  ❌ %s\n' "$1"; }
note() { printf '     · %s\n' "$1"; }

api() { # api <method> <path> [json] → API_CODE / API_BODY
  local method="$1" path="$2" data="${3:-}"
  local args=(-s -o /tmp/.emqx-sc-body -w '%{http_code}' -X "$method"
              -u "$EMQX_API_KEY:$EMQX_API_SECRET" -H 'Content-Type: application/json')
  [ -n "$data" ] && args+=(-d "$data")
  API_CODE="$(curl "${args[@]}" "$BASE_URL$path")"
  API_BODY="$(cat /tmp/.emqx-sc-body)"; rm -f /tmp/.emqx-sc-body
}

# metrics <key> → 数值（读不到输出 -1）
metrics() {
  curl -s -u "$EMQX_API_KEY:$EMQX_API_SECRET" "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('$1',-1))" 2>/dev/null || echo -1
}
metric_snapshot() {
  curl -s -u "$EMQX_API_KEY:$EMQX_API_SECRET" "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c '
import json,sys
d=json.load(sys.stdin)
for k in ["packets.connack.auth_error","packets.publish.auth_error","packets.subscribe.auth_error",
          "authorization.nomatch","authorization.matched.allow","authorization.matched.deny",
          "authorization.deny","client.auth.anonymous","authentication.success"]:
    print("     %-34s %s" % (k, d.get(k,-1)))
' 2>/dev/null
}

cleanup() {
  [ "$KEEP" -eq 1 ] && { echo "（--keep-probe-users：保留临时账号与规则以便排查）"; return; }
  for u in "$DEV_A" "$DEV_B" "$SVC_PROBE"; do
    api DELETE "/api/v5/authentication/$AUTH_ID/users/$u" >/dev/null 2>&1
  done
  api DELETE "/api/v5/authorization/sources/built_in_database/rules/users/$SVC_PROBE" >/dev/null 2>&1
  api DELETE /api/v5/authorization/cache >/dev/null 2>&1
}
trap cleanup EXIT

echo "=== S1 容器 / 节点健康 ==="
h="$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null || echo unknown)"
[ "$h" = "healthy" ] && pass "docker health = healthy" || fail "docker health = $h"
st="$(docker exec "$CONTAINER" /opt/emqx/bin/emqx ctl status 2>&1 | tr '\n' ' ')"
case "$st" in *"is started"*) pass "emqx ctl status：$st";; *) fail "emqx ctl status：$st";; esac

echo "=== S2 宿主侧监听只绑回环（端口不回公网的结构性保证）==="
for p in "$MQTT_PORT" 18093; do
  # 取该端口的**全部**监听地址，要求集合恰好 = {127.0.0.1:p}
  addrs="$(ss -ltn 2>/dev/null | awk '{print $4}' | grep -E "[:.]${p}\$" | sort -u | tr '\n' ' ')"
  if [ "$addrs" = "127.0.0.1:${p} " ]; then
    pass "宿主 ${p} 监听集合 = {127.0.0.1:${p}}（仅回环）"
  elif [ -z "$addrs" ]; then
    fail "宿主 ${p} 无监听"
  else
    fail "宿主 ${p} 监听集合 = {$addrs}（**不止回环**，有暴露风险）"
  fi
done

echo "=== S3/S4 Health 与 REST 鉴权 ==="
code="$(curl -s -o /tmp/.emqx-sc-h -w '%{http_code}' "$BASE_URL/status")"
body="$(cat /tmp/.emqx-sc-h)"; rm -f /tmp/.emqx-sc-h
if [ "$code" = 200 ] && printf '%s' "$body" | grep -q "is started"; then
  pass "GET /status = 200 且含 'is started'"
else
  fail "GET /status = $code（正文=$body）"
fi
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v5/status")"
[ "$code" = 200 ] && pass "GET /api/v5/status（免鉴权）= 200" || fail "GET /api/v5/status = $code"
api GET /api/v5/authorization/settings
[ "$API_CODE" = 200 ] && pass "GET /authorization/settings（API Key 鉴权）= 200" \
                      || fail "GET /authorization/settings（API Key 鉴权）= $API_CODE"

echo "=== S5 Dashboard 口令（H2）==="
code="$(curl -s -o /tmp/.emqx-sc-l1 -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"public"}' "$BASE_URL/api/v5/login")"
[ "$code" = 401 ] && pass "旧默认口令 admin/public 被拒（HTTP 401）" \
                  || fail "旧默认口令 admin/public **未被拒**（HTTP $code）⇒ 口令没改到位"
rm -f /tmp/.emqx-sc-l1
code="$(curl -s -o /tmp/.emqx-sc-l2 -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$EMQX_DASHBOARD_PASSWORD\"}" "$BASE_URL/api/v5/login")"
if [ "$code" = 200 ]; then
  pass "来自 .env 的 Dashboard 口令可登录（HTTP 200；token 长度 $(python3 -c 'import json;print(len(json.load(open("/tmp/.emqx-sc-l2")).get("token","")))' 2>/dev/null)）"
else
  fail "来自 .env 的 Dashboard 口令登录失败（HTTP $code）⇒ data 卷是旧口令初始化的（见 EMQX-DEPLOY.md 的口令重置一节）"
fi
rm -f /tmp/.emqx-sc-l2

echo "=== S6-S11 MQTT 认证 / ACL 正负用例（emqtt-bench $BENCH_IMAGE）==="
echo "  压测客户端镜像: $BENCH_IMAGE"
echo "  镜像 digest    : $BENCH_DIGEST"
if ! docker image inspect "$BENCH_IMAGE" >/dev/null 2>&1; then
  fail "本地缺少压测镜像 $BENCH_IMAGE ⇒ 无法求值。先 docker pull（或按文档用 --bench-image 指定）"
  echo; echo "结论: FAIL（$FAIL_N 项失败；自检未完整执行）"; exit 1
fi

# ── 建临时账号（口令只在内存里）──────────────────────────────────────────────
PW_A="$(openssl rand -hex 12)"; PW_B="$(openssl rand -hex 12)"; PW_S="$(openssl rand -hex 12)"
for u in "$DEV_A:$PW_A" "$DEV_B:$PW_B" "$SVC_PROBE:$PW_S"; do
  api DELETE "/api/v5/authentication/$AUTH_ID/users/${u%%:*}" >/dev/null
  api POST "/api/v5/authentication/$AUTH_ID/users" \
      "{\"user_id\":\"${u%%:*}\",\"password\":\"${u#*:}\",\"is_superuser\":false}"
  [ "$API_CODE" = 201 ] && pass "临时账号 ${u%%:*} 已创建（口令只在本进程内存，不回显）" \
                        || fail "临时账号 ${u%%:*} 创建失败：HTTP $API_CODE $API_BODY"
done
# 服务账号的通配订阅规则（与生产服务账号同名规则，供 S11 用）
api POST /api/v5/authorization/sources/built_in_database/rules/users \
  "[{\"username\":\"$SVC_PROBE\",\"rules\":[{\"action\":\"subscribe\",\"permission\":\"allow\",\"topic\":\"$PROBE_TOPIC_SVC\"}]}]"
[ "$API_CODE" = 204 ] && pass "临时服务账号规则已建（$SVC_PROBE → $PROBE_TOPIC_SVC）" \
                      || fail "临时服务账号规则创建失败：HTTP $API_CODE"
api DELETE /api/v5/authorization/cache >/dev/null

bench() { timeout "$1" docker run --rm --pull=never --network "$NETWORK" "$BENCH_IMAGE" "${@:2}" >/dev/null 2>&1 || true; }

echo "  ── 指标基线 ──"
metric_snapshot | tee /tmp/.emqx-sc-before

# S6 匿名连接
b="$(metrics packets.connack.auth_error)"; anon0="$(metrics client.auth.anonymous)"
bench 12 conn -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -c 1 -i 1
a="$(metrics packets.connack.auth_error)"; anon1="$(metrics client.auth.anonymous)"
if [ "$a" -gt "$b" ] && [ "$anon1" = "$anon0" ]; then
  pass "S6 匿名连接被拒：packets.connack.auth_error $b → $a（client.auth.anonymous 保持 $anon1）"
else
  fail "S6 匿名连接未被拒：connack.auth_error $b → $a，anonymous $anon0 → $anon1"
fi

# S7 越权 publish
b="$(metrics packets.publish.auth_error)"; n0="$(metrics authorization.nomatch)"
bench 15 pub -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -u "$DEV_A" -P "$PW_A" -c 1 -w \
      -t "ypbin/v1/$TENANT/2002/up/property" -L 1 -q 1 -I 200
a="$(metrics packets.publish.auth_error)"; n1="$(metrics authorization.nomatch)"
if [ "$a" -gt "$b" ] && [ "$n1" -gt "$n0" ]; then
  pass "S7 设备 $DEV_A 发别人主题被拒：publish.auth_error $b → $a，nomatch $n0 → $n1"
else
  fail "S7 越权 publish 未被拒：publish.auth_error $b → $a，nomatch $n0 → $n1"
fi

# S8 自己的主题 publish
al0="$(metrics authorization.matched.allow)"; e0="$(metrics packets.publish.auth_error)"
bench 15 pub -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -u "$DEV_A" -P "$PW_A" -c 1 -w \
      -t "ypbin/v1/$TENANT/2001/up/property" -L 1 -q 1 -I 200
al1="$(metrics authorization.matched.allow)"; e1="$(metrics packets.publish.auth_error)"
if [ "$al1" -gt "$al0" ] && [ "$e1" = "$e0" ]; then
  pass "S8 设备 $DEV_A 发自己主题放行：matched.allow $al0 → $al1（auth_error 不增）"
else
  fail "S8 自己的主题未放行：matched.allow $al0 → $al1，auth_error $e0 → $e1"
fi

# S9 订阅别人的 down
b="$(metrics packets.subscribe.auth_error)"; n0="$(metrics authorization.nomatch)"
bench 10 sub -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -u "$DEV_A" -P "$PW_A" -c 1 \
      -t "ypbin/v1/$TENANT/2002/down/#"
a="$(metrics packets.subscribe.auth_error)"; n1="$(metrics authorization.nomatch)"
if [ "$a" -gt "$b" ] && [ "$n1" -gt "$n0" ]; then
  pass "S9 订阅别人 down 被拒：subscribe.auth_error $b → $a，nomatch $n0 → $n1"
else
  fail "S9 越权 subscribe 未被拒：subscribe.auth_error $b → $a，nomatch $n0 → $n1"
fi

# S10 订阅自己的 down
al0="$(metrics authorization.matched.allow)"
bench 10 sub -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -u "$DEV_A" -P "$PW_A" -c 1 \
      -t "ypbin/v1/$TENANT/2001/down/#"
al1="$(metrics authorization.matched.allow)"
[ "$al1" -gt "$al0" ] && pass "S10 订阅自己的 down 放行：matched.allow $al0 → $al1" \
                      || fail "S10 自己的 down 未放行：matched.allow $al0 → $al1"

# S11 服务账号通配订阅
al0="$(metrics authorization.matched.allow)"
bench 10 sub -h "$HOSTNAME_IN_NET" -p "$MQTT_PORT" -u "$SVC_PROBE" -P "$PW_S" -c 1 -t "$PROBE_TOPIC_SVC"
al1="$(metrics authorization.matched.allow)"
[ "$al1" -gt "$al0" ] && pass "S11 服务账号 $SVC_PROBE 订阅 $PROBE_TOPIC_SVC 放行：matched.allow $al0 → $al1" \
                      || fail "S11 服务账号通配订阅未放行：matched.allow $al0 → $al1"

echo "  ── 指标终态 ──"
metric_snapshot | tee /tmp/.emqx-sc-after
rm -f /tmp/.emqx-sc-before /tmp/.emqx-sc-after

echo
echo "=== 自检结论：PASS=$PASS_N FAIL=$FAIL_N ==="
[ "$FAIL_N" -eq 0 ] && { echo "结论: PASS（阶段① 自检全绿）"; exit 0; }
echo "结论: FAIL"; exit 1
