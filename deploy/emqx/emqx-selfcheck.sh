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
# 凭据纪律：临时测试账号的口令**不落盘、不回显**；所有带口令的 HTTP body 用
#           `--data-binary @<私有 600 文件>` 传（**不进 argv**），脚本退出时 trap 删除
#           全部临时账号与临时 ACL 规则。⚠️ 不要写成"只在进程内存"——建号与登录的 JSON
#           若用 `-d '<json>'` 会出现在 /proc/<pid>/cmdline（本机非 root 用户可读）。
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

# ── 凭据不进 argv、中间文件不落世界可读目录 ──────────────────────────────────
# · `curl -u` 会把密钥写进 /proc/<pid>/cmdline ⇒ 改用 700 目录里的 600 `-K` 配置文件；
# · Dashboard 登录响应含 JWT，**不能**放 /tmp（默认 umask 644）⇒ 一律落在 $PRIV（700）。
PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"

AUTH_ID='password_based%3Abuilt_in_database'
PASS_N=0; FAIL_N=0

pass() { PASS_N=$((PASS_N+1)); printf '  ✅ %s\n' "$1"; }
fail() { FAIL_N=$((FAIL_N+1)); printf '  ❌ %s\n' "$1"; }
note() { printf '     · %s\n' "$1"; }

api() { # api <method> <path> [json] → API_CODE / API_BODY
  local method="$1" path="$2" data="${3:-}"
  local body="$PRIV/api-body"
  local args=(-s -K "$PRIV/curlrc" -o "$body" -w '%{http_code}' -X "$method"
              -H 'Content-Type: application/json')
  if [ -n "$data" ]; then
    # 用 `--data-binary @file`：body（可能含口令）走私有 600 文件，**不进 argv**
    printf '%s' "$data" >"$PRIV/data.json"; chmod 600 "$PRIV/data.json"
    args+=(--data-binary "@$PRIV/data.json")
  fi
  API_CODE="$(curl "${args[@]}" "$BASE_URL$path")"
  API_BODY="$(cat "$body")"; rm -f "$body"
}

# metrics <key> → 数值（读不到输出 -1）
metrics() {
  curl -s -K "$PRIV/curlrc" "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('$1',-1))" 2>/dev/null || echo -1
}
metric_snapshot() {
  curl -s -K "$PRIV/curlrc" "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c '
import json,sys
d=json.load(sys.stdin)
for k in ["packets.connack.auth_error","packets.publish.auth_error","packets.subscribe.auth_error",
          "authorization.nomatch","authorization.matched.allow","authorization.matched.deny",
          "authorization.deny","client.auth.anonymous","authentication.success"]:
    print("     %-34s %s" % (k, d.get(k,-1)))
' 2>/dev/null
}

CLEANED=0
cleanup() {
  [ "$CLEANED" -eq 1 ] && return
  CLEANED=1
  [ "$KEEP" -eq 1 ] && { echo "（--keep-probe-users：保留临时账号与规则以便排查）"; return; }
  for u in "$DEV_A" "$DEV_B" "$SVC_PROBE"; do
    api DELETE "/api/v5/authentication/$AUTH_ID/users/$u" >/dev/null 2>&1
  done
  api DELETE "/api/v5/authorization/sources/built_in_database/rules/users/$SVC_PROBE" >/dev/null 2>&1
  api DELETE /api/v5/authorization/cache >/dev/null 2>&1
  rm -rf "$PRIV"
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM

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
code="$(curl -s -o "$PRIV/health" -w '%{http_code}' "$BASE_URL/status")"
body="$(cat "$PRIV/health")"; rm -f "$PRIV/health"
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
printf '%s' '{"username":"admin","password":"public"}' >"$PRIV/login-bad.json"; chmod 600 "$PRIV/login-bad.json"
code="$(curl -s -o "$PRIV/login-bad" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-binary "@$PRIV/login-bad.json" "$BASE_URL/api/v5/login")"
[ "$code" = 401 ] && pass "旧默认口令 admin/public 被拒（HTTP 401）" \
                  || fail "旧默认口令 admin/public **未被拒**（HTTP $code）⇒ 口令没改到位"
rm -f "$PRIV/login-bad"
printf '{"username":"admin","password":"%s"}' "$EMQX_DASHBOARD_PASSWORD" >"$PRIV/login-ok.json"
chmod 600 "$PRIV/login-ok.json"
code="$(curl -s -o "$PRIV/login-ok" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-binary "@$PRIV/login-ok.json" "$BASE_URL/api/v5/login")"
if [ "$code" = 200 ]; then
  TOKLEN="$(LOGIN_JSON="$PRIV/login-ok" python3 -c 'import json,os;print(len(json.load(open(os.environ["LOGIN_JSON"])).get("token","")))' 2>/dev/null)"
  pass "来自 .env 的 Dashboard 口令可登录（HTTP 200；token 长度 ${TOKLEN:-?}）"
else
  fail "来自 .env 的 Dashboard 口令登录失败（HTTP $code）⇒ data 卷是旧口令初始化的（见 EMQX-DEPLOY.md 的口令重置一节）"
fi
rm -f "$PRIV/login-ok"

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
  [ "$API_CODE" = 201 ] && pass "临时账号 ${u%%:*} 已创建（口令不经 argv、不回显）" \
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
metric_snapshot | tee "$PRIV/before"

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
metric_snapshot | tee "$PRIV/after"
rm -f "$PRIV/before" "$PRIV/after"

echo
echo "=== 自检结论：PASS=$PASS_N FAIL=$FAIL_N ==="
[ "$FAIL_N" -eq 0 ] && { echo "结论: PASS（阶段① 自检全绿）"; exit 0; }
echo "结论: FAIL"; exit 1
