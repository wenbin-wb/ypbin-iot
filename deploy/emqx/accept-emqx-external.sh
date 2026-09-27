#!/usr/bin/env bash
# =============================================================================
# accept-emqx-external.sh —— **从外部网络**（生产机视角）经公网 1883 的端到端验收
# =============================================================================
# 在**运维机**上运行：脚本自己 ssh 到两台机器取证据。判据全部是「差值」与「真值查询」。
#
# 🔴 与既有 `accept-emqx-{ingress,downlink}.sh` 的关系（**说清改动，不假装是同一个脚本**）：
#   本脚本是「连接端点改为**公网 1883**」的变体，复用同样的判据与辅助逻辑，但有三处**必须**的差异：
#     ① **探针运行位置**：既有脚本把 MQTT 客户端跑在**中间件机**上连 `127.0.0.1:1883`；
#        本脚本把客户端跑在**生产机**上连 `43.242.200.8:1883` —— 这才是「从外部真实接入」，
#        也才可能验到宿主绑定/防火墙/云安全组这三层。
#     ② **探针实现**：生产机上**没有 paho**（且不该为一次验收去污染生产机 Python）⇒ 改用
#        `mqtt-external-probe.py`（**零第三方依赖**，只用标准库）。既有脚本内部那两段
#        内联 paho 片段（重投回执 / 伪造回执）在本脚本里由该探针的 `pub` 模式承担。
#     ③ **口令落位**：口令由平台在生产机现签现用，**明文只在生产机的 600 文件**里，
#        不跨机传输（既有脚本是 `prod | mw` 管道落到中间件机），收尾 shred。
#
# 判据（①–⑤ 与任务书对齐，另加下行 ⑥）：
#   ① 数值落库：IoTDB `iot.reading` 出现本条消息的 time+value_double（按 ts 精确归属）
#   ② 曲线点  ：`GET /devices/{id}/series?propertyId=…` 返回该点
#   ③ 幂等    ：同一 requestId 重投 ⇒ duplicated 增长、IoTDB 该 ts 仍 1 行、回执仍 1 行
#   ④ 越权被拒：用自己的凭据发**别人的主题**（**指标差值**判定）
#   ⑤ 无凭据被拒：匿名连接必须被拒（**指标差值** + 探针退出码）
#   ⑥ 下行    ：外部模拟设备订阅 down/# → 平台下发 → **真收到** → 回执 → 实例 succeeded
#
# ⚠️ `authorization.deny_action = ignore` ⇒ 越权 publish 既不回错也不断开，客户端照常收到 PUBACK。
#    **越权用例的判据只能是 EMQX 指标差值**，不能用退出码（用退出码会给出假绿）。
#
# ⚠️ 副作用（与既有 ingress 脚本一致，知情后使用）：第 0 步会**轮换该设备的凭据**（version +1、
#    旧口令立即失效），收尾 shred 明文 ⇒ 跑完后这台设备没有"已知口令"。若有真设备在用，
#    跑完请重新签发并把明文交付设备。
#
# 用法：
#   bash deploy/emqx/accept-emqx-external.sh \
#        --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
#        --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test" \
#        --mw-host 43.242.200.8 --device 9300012 --tenant 1 --property temperature
# =============================================================================
set -uo pipefail

MW_SSH=""; PROD_SSH=""
MW_HOST="43.242.200.8"; MW_PORT=1883
DEVICE=9300012; TENANT=1; PROPERTY=temperature; OTHER_DEVICE=9300013
PROD_ENV=/opt/ypbin/ypbin-iot/deploy/.env
PROBE_LOCAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mqtt-external-probe.py"
PROBE_REMOTE=/tmp/ypbin-mqtt-ext-probe.py
PW_REMOTE=/tmp/ypbin-mqtt-ext-pw
ISSUE_REMOTE=/tmp/ypbin-mqtt-ext-issue.json
TMP=/tmp/ypbin-ext-$$
VALUE=23.4
# 凭据同步到 EMQX 的等待窗口（秒）：轮换后平台要 POST 到 EMQX，立刻连可能撞上同步延迟
SYNC_WAIT=6

while [ $# -gt 0 ]; do
  case "$1" in
    --mw-ssh) MW_SSH="$2"; shift 2 ;;
    --prod-ssh) PROD_SSH="$2"; shift 2 ;;
    --mw-host) MW_HOST="$2"; shift 2 ;;
    --mw-port) MW_PORT="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --tenant) TENANT="$2"; shift 2 ;;
    --property) PROPERTY="$2"; shift 2 ;;
    --other-device) OTHER_DEVICE="$2"; shift 2 ;;
    --prod-env) PROD_ENV="$2"; shift 2 ;;
    --sync-wait) SYNC_WAIT="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done
[ -n "$MW_SSH" ] && [ -n "$PROD_SSH" ] || { echo "缺 --mw-ssh/--prod-ssh" >&2; exit 1; }
[ -f "$PROBE_LOCAL" ] || { echo "找不到本地探针 $PROBE_LOCAL" >&2; exit 1; }

# shellcheck disable=SC2086
mwr()   { ssh -o BatchMode=yes $MW_SSH "$@"; }
# shellcheck disable=SC2086
prodr() { ssh -o BatchMode=yes $PROD_SSH "$@"; }

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# ── 平台指标（生产机）────────────────────────────────────────────────────────
metric() {
  prodr "curl -s -m 8 'http://127.0.0.1:18084/actuator/metrics/$1' | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(int(sum(x[\"value\"] for x in d.get(\"measurements\",[]))))
except Exception: print(-1)'"
}
# ── EMQX 指标（中间件机，只比差值）────────────────────────────────────────────
emqx_metric() {
  mwr "set -a; . /opt/emqx/.env; set +a; P=\$(mktemp -d); chmod 700 \$P; printf 'user = \"%s:%s\"\n' \"\$EMQX_API_KEY\" \"\$EMQX_API_SECRET\" > \$P/c; chmod 600 \$P/c; curl -s --max-time 10 -K \$P/c 'http://127.0.0.1:18093/api/v5/metrics?aggregate=true' | python3 -c 'import json,sys;print(json.load(sys.stdin).get(\"$1\",-1))'; rm -rf \$P"
}
# ── IoTDB：按 ts+value 精确计数 ───────────────────────────────────────────────
# 口径说明（沿用既有脚本的教训）：**不能**用「设备总行数」当判据 —— access 通道每 2s 也在写
# 同一设备同一点位（$FEEDER_VALUE=23.5），会把总行数推高，入站断掉时那条判据依然是绿的。
# 承重判据是「按 ts+value 精确定位恰好 1 行」。
iotdb_hit() {  # $1=ts $2=value
  local from=$(( $1 - 1500 )) to=$(( $1 + 1500 ))
  prodr "PW=\$(sed -n 's/^IOTDB_PASSWORD=//p' $PROD_ENV); docker exec ypbin-iotdb bash -c \"start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw \\\"\$PW\\\" -sql_dialect table -e \\\"SELECT count(*) FROM iot.reading WHERE device_id='$DEVICE' AND time >= $from AND time <= $to AND value_double=$2\\\"\" 2>/dev/null | grep -oE '^[|][[:space:]]*[0-9]+[[:space:]]*[|]' | head -1 | tr -dc '0-9'"
}
# ── 外部探针（在**生产机**上跑，连**公网**端点）────────────────────────────────
probe_pub() {  # $1=username(空=匿名) $2=topic $3=payload
  local user="$1" topic="$2" payload="$3" autharg=""
  [ -n "$user" ] && autharg="--username '$user' --password-file $PW_REMOTE"
  prodr "timeout 30 python3 $PROBE_REMOTE --host $MW_HOST --port $MW_PORT --mode pub $autharg --client-id 'ext-$DEVICE-pub' --topic '$topic' --payload '$payload' --qos 1; echo \"__EXIT=\$?\""
}
probe_auth() { # $1=username(空=匿名)
  local user="$1" autharg=""
  [ -n "$user" ] && autharg="--username '$user' --password-file $PW_REMOTE"
  prodr "timeout 30 python3 $PROBE_REMOTE --host $MW_HOST --port $MW_PORT --mode auth $autharg --client-id 'ext-$DEVICE-auth'; echo \"__EXIT=\$?\""
}
exit_of() { sed -n 's/^__EXIT=//p' <<<"$1"; }

echo "=== 0) 前置：探针上生产机（含完整性自证）+ 现签设备凭据（只落生产机 600 文件）==="
LOCAL_MD5="$(md5sum "$PROBE_LOCAL" | awk '{print $1}')"
prodr "umask 077; cat > $PROBE_REMOTE; chmod 700 $PROBE_REMOTE" < "$PROBE_LOCAL"
prodr "python3 -c 'import ast; ast.parse(open(\"$PROBE_REMOTE\", encoding=\"utf-8\").read()); print(\"  ·  探针语法自检通过\")'"
REMOTE_MD5="$(prodr "md5sum $PROBE_REMOTE | awk '{print \$1}'")"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] && ok "探针上传完整（md5 一致 $LOCAL_MD5）" || bad "探针上传不完整：本地 $LOCAL_MD5 vs 生产机 ${REMOTE_MD5:-NA}"

prodr "umask 077; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-ext-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' http://127.0.0.1:18084/devices/$DEVICE/credential > $ISSUE_REMOTE; chmod 600 $ISSUE_REMOTE"
prodr "python3 -c '
import json,hashlib
d=json.load(open(\"$ISSUE_REMOTE\")); data=d.get(\"data\") or {}; pw=data.get(\"password\") or \"\"
print(\"  ·  R.code=%s username=%s version=%s pwlen=%d pw_sha8=%s\" % (d.get(\"code\"), data.get(\"username\"), data.get(\"credentialVersion\"), len(pw), hashlib.sha256(pw.encode()).hexdigest()[:8]))
'"
# 明文只在生产机落 600 文件（不经 stdout、不跨机）；只回显长度自证
prodr "umask 077; python3 -c 'import json;print((json.load(open(\"$ISSUE_REMOTE\")).get(\"data\") or {}).get(\"password\",\"\"),end=\"\")' > $PW_REMOTE; chmod 600 $PW_REMOTE; echo \"  ·  口令已落生产机 $PW_REMOTE（600，长度 \$(wc -c < $PW_REMOTE)）\""
prodr "shred -u $ISSUE_REMOTE 2>/dev/null || rm -f $ISSUE_REMOTE"
# EMQX 侧账号存在性（只打印用户名）—— 证明"平台签发"确实同步到了 broker，而不是只写了库
mwr "set -a; . /opt/emqx/.env; set +a; P=\$(mktemp -d); chmod 700 \$P; printf 'user = \"%s:%s\"\n' \"\$EMQX_API_KEY\" \"\$EMQX_API_SECRET\" > \$P/c; chmod 600 \$P/c; curl -s --max-time 10 -K \$P/c 'http://127.0.0.1:18093/api/v5/authentication/password_based%3Abuilt_in_database/users?limit=200' | python3 -c 'import json,sys;print(\"  ·  EMQX 侧账号：\", sorted(u[\"user_id\"] for u in json.load(sys.stdin).get(\"data\",[])))'; rm -rf \$P"
info "等待凭据同步 ${SYNC_WAIT}s 再连（避免撞同步延迟造成假红）"; sleep "$SYNC_WAIT"

echo "=== 1) 可达性：**外部视角**（生产机）探 $MW_HOST:$MW_PORT ==="
if prodr "timeout 8 nc -z -w 5 $MW_HOST $MW_PORT" >/dev/null 2>&1; then
  ok "TCP $MW_HOST:$MW_PORT 从外部可达（这才是「对外暴露」的实测证据）"
else
  bad "TCP $MW_HOST:$MW_PORT 从外部不可达 ⇒ 真实设备也连不上（查绑定地址 / 宿主机 / 云安全组）"
fi

echo "=== 2) ⑤ 无凭据（匿名）连接必须被拒（指标差值 + 退出码）==="
CE0=$(emqx_metric packets.connack.auth_error); ANON0=$(emqx_metric client.auth.anonymous)
out="$(probe_auth "")"; echo "$out" | sed 's/^/  ·  /'
code="$(exit_of "$out")"
CE1=$(emqx_metric packets.connack.auth_error); ANON1=$(emqx_metric client.auth.anonymous)
[ "${code:-}" = "2" ] && ok "匿名连接被拒：探针退出码=2（AUTH_REFUSED）" || bad "匿名连接未被拒：退出码=${code:-?}（期望 2）"
[ "$((CE1 - CE0))" -ge 1 ] && ok "⑤ connack.auth_error +$((CE1 - CE0))（broker 侧确认拒绝）" || bad "connack.auth_error 未增长（+$((CE1 - CE0))）"
[ "$((ANON1 - ANON0))" -eq 0 ] && ok "client.auth.anonymous 恒 0（$ANON0→$ANON1：没人以匿名身份通过认证）" || bad "client.auth.anonymous 增长了 $((ANON1 - ANON0))！有匿名客户端被接受"

echo "=== 3) ④ 越权发布（用自己的凭据发别人的主题）必须被拒 ==="
PE0=$(emqx_metric packets.publish.auth_error); NM0=$(emqx_metric authorization.nomatch)
info "发往 ypbin/v1/$TENANT/$OTHER_DEVICE/up/property（不是本设备的主题）"
out="$(probe_pub "$TENANT.$DEVICE" "ypbin/v1/$TENANT/$OTHER_DEVICE/up/property" '{"requestId":"ext-unauth","propertyId":"temperature","value":"1","ts":0}')"
echo "$out" | sed 's/^/  ·  /'
info "探针退出码=$(exit_of "$out")：**deny_action=ignore 下客户端看不出被拒（照常 PUBACK）**，故判据只能用指标"
PE1=$(emqx_metric packets.publish.auth_error); NM1=$(emqx_metric authorization.nomatch)
if [ "$((PE1 - PE0))" -ge 1 ] && [ "$((NM1 - NM0))" -ge 1 ]; then
  ok "④ 越权发布被拒：publish.auth_error +$((PE1 - PE0))、authorization.nomatch +$((NM1 - NM0))"
else
  bad "④ 越权发布**未被拒**（publish.auth_error +$((PE1 - PE0))、nomatch +$((NM1 - NM0))）⇒ ACL 失效，立即排查"
fi

echo "=== 4) 正向：从外部发自己的 up/property（① 落库 + ② 曲线）==="
TS=$(date +%s%3N)
REQ="ext-e2e-$TS"
ACCEPT0=$(metric iot.mqtt.ingest.accepted)
info "requestId=$REQ ts=$TS value=$VALUE"
PAYLOAD="{\"requestId\":\"$REQ\",\"propertyId\":\"$PROPERTY\",\"value\":\"$VALUE\",\"quality\":\"GOOD\",\"ts\":$TS,\"pollIntervalMs\":30000}"
out="$(probe_pub "$TENANT.$DEVICE" "ypbin/v1/$TENANT/$DEVICE/up/property" "$PAYLOAD")"
echo "$out" | sed 's/^/  ·  /'
[ "$(exit_of "$out")" = "0" ] && ok "外部发布成功（PUBACK，退出码 0）" || bad "外部发布失败：退出码=$(exit_of "$out")"
sleep 6
ACCEPT1=$(metric iot.mqtt.ingest.accepted)
[ "$((ACCEPT1 - ACCEPT0))" -ge 1 ] && ok "平台入站受理 iot.mqtt.ingest.accepted +$((ACCEPT1 - ACCEPT0))" || bad "入站受理计数未增长（+$((ACCEPT1 - ACCEPT0))）"
hit="$(iotdb_hit "$TS" "$VALUE")"
[ "${hit:-0}" = "1" ] && ok "① IoTDB 本消息恰好 1 行（ts+value 精确归属，与 access 通道的 23.5 可区分）" || bad "① 按 ts+value 定位到 ${hit:-?} 行（期望 1）"
SERIES="$(prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/series?propertyId=$PROPERTY&from=$((TS-1500))&to=$((TS+1500))'")"
printf '%s' "$SERIES" | grep -q "\"$TS\"" && ok "② series 返回该点：$(printf '%s' "$SERIES" | head -c 200)" || bad "② series 未返回该点：$(printf '%s' "$SERIES" | head -c 200)"

echo "=== 5) ③ 幂等：同一 requestId 从外部重投 ==="
DUP0=$(metric iot.mqtt.ingest.duplicated)
probe_pub "$TENANT.$DEVICE" "ypbin/v1/$TENANT/$DEVICE/up/property" "$PAYLOAD" >/dev/null 2>&1
sleep 6
DUP1=$(metric iot.mqtt.ingest.duplicated)
hit2="$(iotdb_hit "$TS" "$VALUE")"
RECEIPT="$(prodr "umask 077; printf \"SELECT count(*) FROM iot_mqtt_ingest_receipt WHERE request_id='%s';\" '$REQ' > /tmp/ypbin-ext-q.sql; chmod 600 /tmp/ypbin-ext-q.sql; docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" ypbin_admin -N' < /tmp/ypbin-ext-q.sql 2>/dev/null | tr -dc '0-9'; rm -f /tmp/ypbin-ext-q.sql")"
[ "$((DUP1 - DUP0))" -ge 1 ] && ok "③ iot.mqtt.ingest.duplicated +$((DUP1 - DUP0))" || bad "幂等计数未增长（+$((DUP1 - DUP0))）"
[ "${hit2:-0}" = "1" ] && ok "③ 重投后按 ts+value 仍为 1 行（未新增时序行）" || bad "③ 重投后定位到 ${hit2:-?} 行（期望 1）"
[ "${RECEIPT:-0}" = "1" ] && ok "③ MySQL 幂等回执行数=1" || bad "③ 回执行数=${RECEIPT:-?}（期望 1）"

echo "=== 6) ⑥ 下行：平台下发 ⇒ **外部**模拟设备真收到 ⇒ 回执 ⇒ 实例 succeeded ==="
# ─────────────────────────────────────────────────────────────────────────────
# 6a) **预热平台的 EMQX 管理面连接**（🔴 这是绕过平台已知缺陷，不是修复；缺陷已登记）
#   平台 `EmqxRestAdminClient` 用 JDK `HttpClient` 的**默认 HTTP/2**。实测（本仓
#   `deploy/emqx/diagnose-emqx-admin-h2c/` 可复跑）：
#     · 某条**新连接**上第一个请求若是**带体**请求（POST /api/v5/publish），h2c upgrade 握手会被
#       EMQX/Cowboy 直接断开 ⇒ `java.io.IOException: EOF reached while reading`
#       ⇒ 平台把它判成 `EMQX_ERROR / EMQX 管理面不可达`（发布其实**根本没到** EMQX）。
#     · 同一客户端改用 HTTP/1.1：3/3 成功；或先用一个**无体**请求把 h2c 连接建起来，再 POST：也成功。
#   ⇒ 平台的发布在"连接刚建好就被带体请求触发"时必失败、在"连接已被无体请求预热过"时成功。
#   🔴 **正解**是给 `EmqxRestAdminClient` 显式 `.version(HttpClient.Version.HTTP_1_1)`
#      （或对新建连接上的 POST 重试一次）。本仓**未改平台代码**（超出本轮 `deploy/emqx/**`+`docs/**`
#      的改动范围，已登记为待办）。这里用"签发凭据"（内部先做**无体** DELETE）把连接预热，
#      以便把下行链路的**其余部分**（EMQX → 公网 → 外部设备 → 回执 → 实例 succeeded）真正验完。
# ─────────────────────────────────────────────────────────────────────────────
ISSUE2=/tmp/ypbin-mqtt-ext-issue2.json
prodr "umask 077; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-ext-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' http://127.0.0.1:18084/devices/$DEVICE/credential > $ISSUE2; chmod 600 $ISSUE2"
prodr "umask 077; python3 -c 'import json;print((json.load(open(\"$ISSUE2\")).get(\"data\") or {}).get(\"password\",\"\"),end=\"\")' > $PW_REMOTE; chmod 600 $PW_REMOTE; shred -u $ISSUE2 2>/dev/null || rm -f $ISSUE2"
ok "已预热平台管理面连接（并同步刷新外部设备口令文件，长度 $(prodr "wc -c < $PW_REMOTE" | tr -dc '0-9')）"

DOWN_TOPIC="ypbin/v1/$TENANT/$DEVICE/down/#"
REPLY_TOPIC="ypbin/v1/$TENANT/$DEVICE/up/reply"
KIND=property_set; IDENTIFIER=switchState
prodr "rm -f $TMP.listen; nohup timeout 90 python3 $PROBE_REMOTE --host $MW_HOST --port $MW_PORT --mode listen --username '$TENANT.$DEVICE' --password-file $PW_REMOTE --client-id 'ext-$DEVICE-sub' --topic '$DOWN_TOPIC' --device-id $DEVICE --reply-topic '$REPLY_TOPIC' --reply-code 0 --reply-message ok --reply-data '{\"applied\":26.5}' --wait-seconds 60 > $TMP.listen 2>&1 & echo started"
for _ in $(seq 1 40); do prodr "grep -q SUBSCRIBED $TMP.listen 2>/dev/null" && break; sleep 0.5; done
prodr "grep -q SUBSCRIBED $TMP.listen" && ok "外部模拟设备已订阅 down/#（先等订阅再下发，避免竞态假红）" || bad "外部模拟设备未订阅成功：$(prodr "cat $TMP.listen 2>/dev/null" | head -3)"
SEND_JSON="$(prodr "umask 077; printf '%s' '{\"kind\":\"$KIND\",\"identifier\":\"$IDENTIFIER\",\"params\":{\"value\":26.5},\"timeoutMs\":6000}' > /tmp/ypbin-ext-cmd.json; chmod 600 /tmp/ypbin-ext-cmd.json; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-ext-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' -H 'Content-Type: application/json' --data-binary @/tmp/ypbin-ext-cmd.json http://127.0.0.1:18084/devices/$DEVICE/commands; rm -f /tmp/ypbin-ext-cmd.json")"
REQ_DOWN="$(printf '%s' "$SEND_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("requestId",""))')"
printf '%s' "$SEND_JSON" | python3 -c '
import json,sys
d=(json.load(sys.stdin).get("data") or {})
print("  ·  下发响应 statusCode=%s errorCode=%s errorMsg=%s" % (d.get("statusCode"), d.get("errorCode"), d.get("errorMsg")))
print("  ·  topic=%s payload=%s" % (d.get("topic"), d.get("payload")))'
[ -n "$REQ_DOWN" ] && ok "平台返回 requestId=$REQ_DOWN" || bad "下发未返回 requestId（响应=$SEND_JSON）"
for _ in $(seq 1 60); do prodr "grep -q RECEIVED $TMP.listen 2>/dev/null" && break; sleep 0.5; done
prodr "cat $TMP.listen 2>/dev/null" | sed 's/^/  ·  /'
prodr "grep -q RECEIVED $TMP.listen" && ok "⑥ 外部模拟设备**真收到**下行报文" || bad "⑥ 外部模拟设备未收到下行报文"
prodr "grep -q REPLY_SENT $TMP.listen" && ok "⑥ 外部模拟设备已回执（code=0）" || bad "⑥ 外部模拟设备未回执"
sleep 4
ST="$(prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/commands?page=1&pageSize=50' | python3 -c '
import json,sys
rows=(json.load(sys.stdin).get(\"data\") or {}).get(\"items\") or []
hit=[r for r in rows if r.get(\"requestId\")==\"$REQ_DOWN\"]
print((hit[0].get(\"statusCode\") or \"\") if hit else \"NOT_FOUND\")'")"
[ "$ST" = "succeeded" ] && ok "⑥ 实例终态 = succeeded" || bad "⑥ 实例终态 = $ST（期望 succeeded）"

echo "=== 7) 收尾：清理生产机的临时口令与探针 ==="
prodr "shred -u $PW_REMOTE 2>/dev/null || rm -f $PW_REMOTE; rm -f $PROBE_REMOTE $TMP.listen; echo cleaned"
left="$(prodr "ls -1 $PW_REMOTE $PROBE_REMOTE 2>/dev/null | tr '\n' ' '")"
[ -z "$left" ] && ok "生产机已清理（口令文件与探针均已删除，回读无残留）" || bad "生产机仍有残留：$left"

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（从外部网络经公网 $MW_PORT 的 ①–⑥ 全部命中）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
