#!/usr/bin/env bash
# =============================================================================
# emqx-tls-selfcheck.sh —— 8883（MQTT over TLS）验收自检（**用输出说话**）
# =============================================================================
# 在**中间件机**上运行。设计依据：docs/EMQX-DEPLOY.md §3.2；本仓既有风格
# （emqx-selfcheck.sh）——每条判据都有"真实命令输出"或"指标前后差值"，不做"没报错就算过"。
#
# 覆盖判据：
#   T1 `emqx ctl listeners` 里 ssl:default **running=true / enable=true**，监听 8883
#   T2 宿主侧 8883 监听地址与 `.env` 的 `EMQX_MQTT_TLS_BIND_ADDR` 声明一致
#   T3 TLS **握手真完成**：`openssl s_client` 返回 `Verify return code` 且**取到证书链**
#      （共用/本地各一次；`--external-host` 给了就额外从外部视角验一次）
#   T4 服务端证书与 /opt/emqx/certs/server.crt **同指纹**（证明客户端看到的就是我们装的那张）
#      + SAN 含连接用的主机名/IP（否则客户端会主机名不匹配）
#   T5 证书有效期（剩余天数 < 30 报 WARN）
#   T6 **鉴权仍生效**：无凭据经 8883 连接被拒（CONNACK rc!=0 或断开）+ 指标差值
#   T7 **凭据连接成功**：平台签发风格的账号经 8883 连接成功（CONNACK rc=0）
#   T8 **ACL 仍生效**：经 8883 发自己主题放行、发别人主题被拒（指标差值）
#   T9 明文 1883 **仍在**（本批不收回；若被意外关闭立即报错）
#
# ⚠️ 判据口径（**别读成更强的结论**）：
#   · T6–T8 用 `--insecure`（不校验服务端证书）**只验证 TLS 之上的 MQTT 层语义**；
#     证书本身的可信度由 T3/T4 单独判定。两件事分开写，避免"用了 --insecure ⇒ 整轮不算数"
#     或"握手过了 ⇒ 证书一定可信"这两种相反的误读。
#   · 自签证书下客户端默认不信任 ⇒ 正式客户端必须导入 ca.crt。
#
# 凭据纪律：MQTT 探测口令**必然进 argv**（`docker run … -P <明文>`）—— 与 emqx-selfcheck.sh
#   的 S6–S11 同一件事，如实登记；账号在退出时删除 ⇒ 口令随之失效。
#   HTTP 侧凭据只经 600 的 curl 配置文件（-K）传递，不进 argv。
#
# 退出码：0 = 全部 PASS；1 = 有 FAIL
# 用法：bash emqx-tls-selfcheck.sh [--env-file /opt/emqx/.env] [--base-url http://127.0.0.1:18093]
#                                 [--external-host 43.242.200.8] [--port 8883]
#                                 [--bench-image <ref>] [--keep-probe-users] [--no-mqtt-probe]
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
BASE_URL=http://127.0.0.1:18093
CONTAINER=ypbin-emqx
NETWORK=emqx-edge
HOSTNAME_IN_NET=ypbin-emqx
TLS_PORT=8883
PLAIN_PORT=1883
CERT_DIR=/opt/emqx/certs
# 与 emqx-selfcheck.sh 同一镜像（pin 版本 + digest；`--pull=never` 保证不依赖网络）
BENCH_IMAGE=docker.m.daocloud.io/emqx/emqtt-bench:0.6.2
BENCH_DIGEST=sha256:b34b364859dab936507a73388fcf55d6a9fe422e17bbf36997e884500288dcf8
EXTERNAL_HOST=""
KEEP=0
RUN_MQTT=1

TENANT=1001
DEV_A="${TENANT}.2001"
DEV_B="${TENANT}.2002"

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file)      ENV_FILE="$2"; shift 2 ;;
    --base-url)      BASE_URL="$2"; shift 2 ;;
    --container)     CONTAINER="$2"; shift 2 ;;
    --network)       NETWORK="$2"; shift 2 ;;
    --port)          TLS_PORT="$2"; shift 2 ;;
    --cert-dir)      CERT_DIR="$2"; shift 2 ;;
    --external-host) EXTERNAL_HOST="$2"; shift 2 ;;
    --bench-image)   BENCH_IMAGE="$2"; shift 2 ;;
    --bench-digest)  BENCH_DIGEST="$2"; shift 2 ;;
    --keep-probe-users) KEEP=1; shift ;;
    --no-mqtt-probe) RUN_MQTT=0; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"

CURL_MAXTIME=10
CURL_CONNECT_TIMEOUT=3
PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"

AUTH_ID='password_based%3Abuilt_in_database'
PASS_N=0; FAIL_N=0; WARN_N=0

pass() { PASS_N=$((PASS_N+1)); printf '  ✅ %s\n' "$1"; }
fail() { FAIL_N=$((FAIL_N+1)); printf '  ❌ %s\n' "$1"; }
warn() { WARN_N=$((WARN_N+1)); printf '  ⚠️ %s\n' "$1"; }
info() { printf '  ·  %s\n' "$1"; }

api() { # api <method> <path> [json] → API_CODE / API_BODY
  local method="$1" path="$2" data="${3:-}"
  local body="$PRIV/body"
  local args=(-s --max-time "$CURL_MAXTIME" --connect-timeout "$CURL_CONNECT_TIMEOUT" \
              -K "$PRIV/curlrc" -o "$body" -w '%{http_code}' -X "$method" \
              -H 'Content-Type: application/json')
  if [ -n "$data" ]; then
    printf '%s' "$data" >"$PRIV/data.json"; chmod 600 "$PRIV/data.json"
    args+=(--data-binary "@$PRIV/data.json")
  fi
  API_CODE="$(curl "${args[@]}" "$BASE_URL$path")"
  API_BODY="$(cat "$body")"; rm -f "$body"
}
metrics() { # metrics <key> → 数值（读不到输出 -1）
  curl -s --max-time "$CURL_MAXTIME" --connect-timeout "$CURL_CONNECT_TIMEOUT" -K "$PRIV/curlrc" \
    "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('$1',-1))" 2>/dev/null || echo -1
}

CLEANED=0
cleanup() {
  [ "$CLEANED" -eq 1 ] && return
  CLEANED=1
  del_or_warn() {
    api DELETE "$2"
    case "$API_CODE" in
      204|404) printf '  %-22s 已清理（HTTP %s）\n' "$1" "$API_CODE" ;;
      *)       printf '  ⚠️ %-19s 清理失败（HTTP %s）—— 临时对象可能残留\n' "$1" "$API_CODE" >&2 ;;
    esac
  }
  if [ "$KEEP" -eq 1 ]; then
    echo "⚠️ --keep-probe-users：保留 $DEV_A / $DEV_B（口令仍有效），请手工清理" >&2
  else
    del_or_warn "认证用户 $DEV_A" "/api/v5/authentication/$AUTH_ID/users/$DEV_A"
    del_or_warn "认证用户 $DEV_B" "/api/v5/authentication/$AUTH_ID/users/$DEV_B"
    del_or_warn "授权缓存"        "/api/v5/authorization/cache"
  fi
  rm -rf "$PRIV"
  unset -f del_or_warn
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM

echo "=== T1 监听器状态（emqx ctl listeners）==="
LST="$(docker exec "$CONTAINER" /opt/emqx/bin/emqx ctl listeners 2>&1)"
ssl_block="$(printf '%s' "$LST" | awk '/^ssl:default/{f=1} f&&/^[a-z]+:/&&!/^ssl:default/{f=0} f')"
echo "$ssl_block" | sed 's/^/     /'
printf '%s' "$ssl_block" | grep -qE 'running +: +true' \
  && pass "ssl:default running = true" || fail "ssl:default 未在运行（看上面输出）"
printf '%s' "$ssl_block" | grep -qE 'enbale +: +true' \
  && pass "ssl:default enable = true" || fail "ssl:default enable 不是 true"
printf '%s' "$ssl_block" | grep -qE "listen_on +: +.*${TLS_PORT}" \
  && pass "ssl:default 监听 $TLS_PORT" || fail "ssl:default 未监听 $TLS_PORT"
# 明文 1883 必须**仍在**（本批刻意不收回）
printf '%s' "$LST" | awk '/^tcp:default/{f=1} f&&/^[a-z]+:/&&!/^tcp:default/{f=0} f' \
  | grep -qE 'running +: +true' \
  && pass "tcp:default（明文 $PLAIN_PORT）仍在运行 —— 与 8883 并存，本批未收回" \
  || fail "tcp:default 未运行 —— 明文 $PLAIN_PORT 被意外关闭，**会影响现有链路**，立即排查！"

echo "=== T2 宿主侧监听与声明一致 ==="
DECL_TLS="$(sed -n 's/^EMQX_MQTT_TLS_BIND_ADDR=//p' "$ENV_FILE" | tail -1)"
DECL_TLS="${DECL_TLS:-127.0.0.1}"   # compose 默认值（fail-closed）
info "声明 EMQX_MQTT_TLS_BIND_ADDR=${DECL_TLS}"
host_tls_addrs="$(ss -lnt 2>/dev/null | awk '{print $4}' | grep -E "[:.]${TLS_PORT}\$" | sort -u | tr '\n' ' ')"
info "宿主 ${TLS_PORT} 监听集合 = {${host_tls_addrs% }}"
case "$DECL_TLS" in
  0.0.0.0)
    printf '%s' "$host_tls_addrs" | grep -q "0.0.0.0:${TLS_PORT}" \
      && pass "宿主 0.0.0.0:${TLS_PORT} 在（与声明一致）" \
      || fail "声明对外但宿主没有 0.0.0.0:${TLS_PORT} 监听 ⇒ 绑定层没生效" ;;
  127.0.0.1)
    [ -z "$host_tls_addrs" ] && info "声明只绑回环，宿主无 ${TLS_PORT} 监听（容器内 listener 仍可能开着）"
    printf '%s' "$host_tls_addrs" | grep -qvE '^127\.0\.0\.1:' \
      && fail "声明只绑回环，但宿主存在非回环 ${TLS_PORT} 监听：$host_tls_addrs" \
      || pass "宿主无非回环 ${TLS_PORT} 监听（与声明一致）" ;;
  *) fail "EMQX_MQTT_TLS_BIND_ADDR='$DECL_TLS' 不是受支持的绑定地址" ;;
esac

echo "=== T3 TLS 握手真验证（openssl s_client）==="
tls_handshake() { # tls_handshake <host> <port> <label> → 打印摘要；成功返回 0
  local host="$1" port="$2" label="$3" out rc
  out="$(printf 'Q' | timeout 12 openssl s_client -connect "${host}:${port}" \
          -servername "$host" -showcerts 2>&1)"
  rc=$?
  # 从输出里取几条硬证据
  local proto cipher verify subj issuer chain
  proto="$(printf '%s' "$out" | sed -n 's/^ *Protocol *: *//p' | head -1)"
  cipher="$(printf '%s' "$out" | sed -n 's/^ *Cipher *: *//p' | head -1)"
  verify="$(printf '%s' "$out" | sed -n 's/^ *Verify return code: *//p' | head -1)"
  subj="$(printf '%s' "$out" | sed -n 's/^subject=//p' | head -1)"
  issuer="$(printf '%s' "$out" | sed -n 's/^issuer=//p' | head -1)"
  chain="$(printf '%s' "$out" | grep -c 'BEGIN CERTIFICATE')"
  printf '     [%s] rc=%s protocol=%s cipher=%s\n' "$label" "$rc" "${proto:-?}" "${cipher:-?}"
  printf '     [%s] subject=%s\n' "$label" "${subj:-?}"
  printf '     [%s] issuer=%s\n' "$label" "${issuer:-?}"
  printf '     [%s] Verify return code: %s；证书链证书数=%s\n' "$label" "${verify:-（无）}" "$chain"
  [ "$rc" -eq 0 ] && [ "$chain" -ge 1 ] && [ -n "$proto" ]
}

if tls_handshake "$HOSTNAME_IN_NET" "$TLS_PORT" "容器名(同网桥)"; then
  pass "T3a 握手完成（-servername $HOSTNAME_IN_NET）并取到证书链"
else
  fail "T3a 握手失败（-servername $HOSTNAME_IN_NET）"
fi
if tls_handshake 127.0.0.1 "$TLS_PORT" "宿主回环"; then
  pass "T3b 握手完成（宿主 127.0.0.1:${TLS_PORT}）并取到证书链"
else
  fail "T3b 握手失败（宿主 127.0.0.1:${TLS_PORT}）"
fi
if [ -n "$EXTERNAL_HOST" ]; then
  if tls_handshake "$EXTERNAL_HOST" "$TLS_PORT" "外部($EXTERNAL_HOST)"; then
    pass "T3c 从外部主机 $EXTERNAL_HOST 握手完成 ⇒ **公网可达**"
  else
    fail "T3c 外部 $EXTERNAL_HOST:${TLS_PORT} 握手失败 ⇒ 云安全组/绑定未放行（见文档 §3.2）"
  fi
else
  info "T3c 未指定 --external-host（外部可达性用生产机上的 mqtt-external-probe.py --tls 另测）"
fi

echo "=== T4 服务端证书指纹与本地文件一致 + SAN 覆盖连接名 ==="
served="$(printf 'Q' | timeout 12 openssl s_client -connect "127.0.0.1:${TLS_PORT}" \
            -servername "$HOSTNAME_IN_NET" 2>/dev/null \
          | openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"
local_fp="$(openssl x509 -in "$CERT_DIR/server.crt" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"
if [ -n "$served" ] && [ "$served" = "$local_fp" ]; then
  pass "服务端实际出示的证书 = $CERT_DIR/server.crt（SHA256 ${served:0:17}…）"
elif [ -n "$served" ]; then
  fail "服务端出示的证书与 $CERT_DIR/server.crt **不一致**：served=${served:0:17}… local=${local_fp:0:17}…"
else
  fail "取不到服务端证书（握手未完成）"
fi
san="$(openssl x509 -in "$CERT_DIR/server.crt" -noout -ext subjectAltName 2>/dev/null | tail -1 | sed 's/^ *//')"
info "SAN = ${san:-（无）}"
for want in "$HOSTNAME_IN_NET" "localhost" "43.242.200.8"; do
  printf '%s' "$san" | grep -q -- "$want" \
    && pass "SAN 含 $want" || warn "SAN **不含** $want（按该名字连接时会出现主机名校验失败）"
done

echo "=== T5 证书有效期 ==="
end="$(openssl x509 -in "$CERT_DIR/server.crt" -noout -enddate 2>/dev/null | cut -d= -f2)"
if [ -n "$end" ]; then
  end_epoch="$(date -d "$end" +%s 2>/dev/null || echo 0)"
  now_epoch="$(date +%s)"
  days_left=$(( (end_epoch - now_epoch) / 86400 ))
  if [ "$days_left" -lt 0 ]; then
    fail "证书**已过期**（到期 $end）"
  elif [ "$days_left" -lt 30 ]; then
    warn "证书 $days_left 天后到期（$end）—— 自签不会自动续期，请安排重签（gen-certs.sh --force）"
  else
    pass "证书有效期剩余 $days_left 天（至 $end）"
  fi
else
  fail "读不到证书有效期"
fi

if [ "$RUN_MQTT" -eq 0 ]; then
  echo; echo "（--no-mqtt-probe：跳过 T6–T8 的 MQTT 层探测）"
else
echo "=== T6–T8 经 8883 的鉴权 / 凭据 / ACL（emqtt-bench $BENCH_IMAGE）==="
if ! docker image inspect "$BENCH_IMAGE" >/dev/null 2>&1; then
  fail "本地缺少 $BENCH_IMAGE ⇒ 无法求值（先 docker pull，或用 --bench-image 指定）"
else
  if ! docker image inspect "$BENCH_IMAGE" --format '{{.Id}}' | grep -q "${BENCH_DIGEST#sha256:}"; then
    warn "本地 $BENCH_IMAGE 的镜像 ID 与登记的 digest 不一致（镜像可能被替换过）"
  fi
  # `-s` 开 TLS。⚠️ 用 `--insecure`：自签证书下 emqtt-bench 默认会因不受信任而拒绝；
  # 这里**只验证 TLS 之上的 MQTT 语义**，证书可信度由 T3/T4 判定。
  bench() { timeout "$1" docker run --rm --pull=never --network "$NETWORK" "$BENCH_IMAGE" "${@:2}" >/dev/null 2>&1 || true; }

  PW_A="$(openssl rand -hex 12)"; PW_B="$(openssl rand -hex 12)"
  for u in "$DEV_A:$PW_A" "$DEV_B:$PW_B"; do
    api DELETE "/api/v5/authentication/$AUTH_ID/users/${u%%:*}"
    api POST "/api/v5/authentication/$AUTH_ID/users" \
        "{\"user_id\":\"${u%%:*}\",\"password\":\"${u#*:}\",\"is_superuser\":false}"
    [ "$API_CODE" = 201 ] && pass "临时账号 ${u%%:*} 已创建（口令不回显）" \
                          || fail "临时账号 ${u%%:*} 创建失败：HTTP $API_CODE $API_BODY"
  done
  api DELETE /api/v5/authorization/cache || true

  # ── T6 无凭据经 8883 必须被拒 ──────────────────────────────────────────────
  before="$(metrics packets.connack.auth_error)"
  bench 15 conn -h "$HOSTNAME_IN_NET" -p "$TLS_PORT" -s --insecure -c 1 -i 1
  after="$(metrics packets.connack.auth_error)"
  if [ "$after" -gt "$before" ]; then
    pass "T6 无凭据经 8883 被拒：packets.connack.auth_error $before → $after"
  else
    fail "T6 无凭据经 8883 **未被拒**：connack.auth_error $before → $after（鉴权可能失效！）"
  fi
  anon="$(metrics client.auth.anonymous)"
  [ "$anon" = 0 ] && pass "T6b client.auth.anonymous = 0（无匿名通过）" \
                  || fail "T6b 🔴 client.auth.anonymous = $anon（应恒为 0）"

  # ── T7 正确凭据经 8883 连接成功 ────────────────────────────────────────────
  ok0="$(metrics authentication.success)"
  bench 15 conn -h "$HOSTNAME_IN_NET" -p "$TLS_PORT" -s --insecure -u "$DEV_A" -P "$PW_A" -c 1 -i 1
  ok1="$(metrics authentication.success)"
  if [ "$ok1" -gt "$ok0" ]; then
    pass "T7 凭据经 8883 认证成功：authentication.success $ok0 → $ok1"
  else
    fail "T7 凭据经 8883 认证**未成功**：authentication.success $ok0 → $ok1"
  fi

  # ── T8 ACL 经 8883 仍生效（自己主题放行 / 别人主题被拒）────────────────────
  al0="$(metrics authorization.matched.allow)"; ae0="$(metrics packets.publish.auth_error)"
  bench 15 pub -h "$HOSTNAME_IN_NET" -p "$TLS_PORT" -s --insecure -u "$DEV_A" -P "$PW_A" -c 1 -w \
        -t "ypbin/v1/$TENANT/2001/up/property" -L 1 -q 1 -I 200
  al1="$(metrics authorization.matched.allow)"; ae1="$(metrics packets.publish.auth_error)"
  if [ "$al1" -gt "$al0" ] && [ "$ae1" = "$ae0" ]; then
    pass "T8a 经 8883 发**自己**主题放行：matched.allow $al0 → $al1（auth_error 不增）"
  else
    fail "T8a 经 8883 发自己主题未放行：matched.allow $al0 → $al1，auth_error $ae0 → $ae1"
  fi
  nm0="$(metrics authorization.nomatch)"
  bench 15 pub -h "$HOSTNAME_IN_NET" -p "$TLS_PORT" -s --insecure -u "$DEV_A" -P "$PW_A" -c 1 -w \
        -t "ypbin/v1/$TENANT/2002/up/property" -L 1 -q 1 -I 200
  nm1="$(metrics authorization.nomatch)"
  if [ "$nm1" -gt "$nm0" ]; then
    pass "T8b 经 8883 发**别人**主题被拒：authorization.nomatch $nm0 → $nm1"
  else
    fail "T8b 经 8883 越权 publish **未被拒**：nomatch $nm0 → $nm1"
  fi
  info "⚠️ 上面 3 次探测的 MQTT 口令进了 docker 的 argv（emqtt-bench 只支持 -P 明文）；账号退出时删除"
fi
fi

echo
echo "=== TLS 自检结论：PASS=$PASS_N FAIL=$FAIL_N WARN=$WARN_N ==="
[ "$FAIL_N" -eq 0 ] && { echo "结论: PASS（8883 可用、握手真完成、鉴权与 ACL 仍生效）"; exit 0; }
echo "结论: FAIL"; exit 1
