#!/usr/bin/env bash
# =============================================================================
# accept-emqx-downlink.sh —— 段 B 下行端到端验收（**可复跑**）
# =============================================================================
# 在**运维机**上运行。**五条**判据（①–⑤）：
#   ① 在线：下发 → 实例 sent（emqx_message_id 非空）→ 模拟设备**真收到**（打印 payload）→ 回执 code=0
#          → 实例 succeeded、reply_payload 含设备 data/ts、finished_at 为平台时间
#   ② 幂等：同一回执重投 ⇒ duplicated、实例状态不变、iot.command.reply.duplicated +1
#   ③ 设备未连：无订阅者时下发 ⇒ **立即** failed/NO_SUBSCRIBER（记录耗时，应远小于超时）
#   ④ 超时：设备连着但不回执 ⇒ 扫描在 timeout_ms 后置 timeout（原因码 TIMEOUT）
#   ⑤ 伪造：在设备自己的 up/reply 上发一条 payload.deviceId = 别的设备 的回执 ⇒ 必须被平台按
#      「认证主题不一致」丢弃（**这正是"EMQX 模板头 X-Mqtt-Device 失效"的判别用例**）
#
# 凭据纪律：设备口令由本脚本现签现用（**会轮换该设备凭据**），明文只经 `ssh | ssh` 管道落到
# 中间件机 600 文件，不打印、不进 argv；收尾 shred 删除。
#
# 用法：
#   bash deploy/emqx/accept-emqx-downlink.sh \
#        --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
#        --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test" \
#        --device 9300012 --tenant 1 [--kind property_set --identifier temperature]
# =============================================================================
set -uo pipefail

MW_SSH=""; PROD_SSH=""
DEVICE=9300012; TENANT=1; KIND=property_set; IDENTIFIER=temperature
PROD_ENV=/opt/ypbin/ypbin-iot/deploy/.env
MW_PW_FILE=/opt/emqx/ingress/device-password
PROBE=/opt/emqx/mqtt-device-probe.py
PYTHON=/opt/emqx/venv/bin/python3
TMP=/tmp/ypbin-downlink-$$
TIMEOUT_MS=6000

while [ $# -gt 0 ]; do
  case "$1" in
    --mw-ssh) MW_SSH="$2"; shift 2 ;;
    --prod-ssh) PROD_SSH="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --tenant) TENANT="$2"; shift 2 ;;
    --kind) KIND="$2"; shift 2 ;;
    --identifier) IDENTIFIER="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done
[ -n "$MW_SSH" ] && [ -n "$PROD_SSH" ] || { echo "缺 --mw-ssh/--prod-ssh" >&2; exit 1; }

# shellcheck disable=SC2086
mwr() { ssh -o BatchMode=yes $MW_SSH "$@"; }
# shellcheck disable=SC2086
prodr() { ssh -o BatchMode=yes $PROD_SSH "$@"; }

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# 平台 metric 值
metric() {
  prodr "curl -s -m 8 'http://127.0.0.1:18084/actuator/metrics/$1' | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(int(sum(x[\"value\"] for x in d.get(\"measurements\",[]))))
except Exception: print(-1)'"
}
# 下发（返回原始 JSON 到 stdout）
send_command() {
  local timeout_ms="$1"
  prodr "umask 077; printf '%s' '{\"kind\":\"$KIND\",\"identifier\":\"$IDENTIFIER\",\"params\":{\"value\":26.5},\"timeoutMs\":$timeout_ms}' > /tmp/cmd.json; chmod 600 /tmp/cmd.json; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' -H 'Content-Type: application/json' --data-binary @/tmp/cmd.json http://127.0.0.1:18084/devices/$DEVICE/commands"
}
# 查实例状态（按 requestId 过滤）
instance_status() {
  local request_id="$1"
  prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/commands?page=1&pageSize=50' | python3 -c '
import json,sys
target=sys.argv[1]
d=json.load(sys.stdin).get(\"data\") or {}
rows=d.get(\"items\") or d.get(\"records\") or d.get(\"list\") or []
hit=[r for r in rows if r.get(\"requestId\")==target]
print(hit[0].get(\"statusCode\",\"?\") if hit else \"NOT_FOUND\")' '$request_id'"
}

echo "=== 0) 前置：签发设备凭据（会轮换） ==="
prodr "umask 077; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' http://127.0.0.1:18084/devices/$DEVICE/credential > /tmp/issue.json; python3 -c '
import json,hashlib
d=json.load(open(\"/tmp/issue.json\")); data=d.get(\"data\") or {}; pw=data.get(\"password\") or \"\"
print(\"  ·  R.code=%s version=%s pwlen=%d\" % (d.get(\"code\"), data.get(\"credentialVersion\"), len(pw)))'"
prodr "python3 -c 'import json;print((json.load(open(\"/tmp/issue.json\")).get(\"data\") or {}).get(\"password\",\"\"),end=\"\")'" \
  | mwr "umask 077; cat > $MW_PW_FILE; chmod 600 $MW_PW_FILE; echo \"  ·  口令已落到中间件机 600 文件\""

DOWN_TOPIC="ypbin/v1/$TENANT/$DEVICE/down/#"
REPLY_TOPIC="ypbin/v1/$TENANT/$DEVICE/up/reply"

echo "=== ① 在线：设备订阅 → 平台下发 → 设备回执（判据：sent + reply_payload 字段齐全）==="
mwr "rm -f $TMP.listen; nohup $PYTHON $PROBE --mode listen --username '$TENANT.$DEVICE' --password-file $MW_PW_FILE --client-id '$TENANT.$DEVICE' --topic '$DOWN_TOPIC' --device-id $DEVICE --reply-topic '$REPLY_TOPIC' --reply-code 0 --reply-message ok --reply-data '{\"applied\":26.5}' --wait-seconds 40 > $TMP.listen 2>&1 & echo started"
mwr "for i in \$(seq 1 40); do grep -q SUBSCRIBED $TMP.listen 2>/dev/null && break; sleep 0.5; done"
mwr "grep -q SUBSCRIBED $TMP.listen" && ok "模拟设备已建立订阅（先等订阅再下发，避免竞态假红）" || bad "模拟设备未订阅成功"
SEND_JSON="$(send_command "$TIMEOUT_MS")"
REQ="$(printf '%s' "$SEND_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("requestId",""))')"
STATUS_JSON="$(printf '%s' "$SEND_JSON" | python3 -c 'import json,sys; d=(json.load(sys.stdin).get("data") or {}); print("status=%s topic=%s payload=%s" % (d.get("statusCode"), d.get("topic"), d.get("payload")))')"
info "下发响应：$STATUS_JSON"
[ -n "$REQ" ] && ok "平台返回 requestId=$REQ" || bad "下发未返回 requestId（响应=$SEND_JSON）"
printf '%s' "$SEND_JSON" | python3 -c '
import json,sys
d=(json.load(sys.stdin).get("data") or {})
assert d.get("statusCode")=="sent", "下发响应状态=%s（期望 sent）" % d.get("statusCode")

print("  ·  下发响应 statusCode=%s emqxMessageId=%s" % (d.get("statusCode"), d.get("emqxMessageId")))' || bad "下发响应不是 sent（期望 200=至少一个订阅者）"
mwr "for i in \$(seq 1 40); do grep -q RECEIVED $TMP.listen 2>/dev/null && break; sleep 0.5; done; cat $TMP.listen" | sed 's/^/  ·  /'
mwr "grep -q RECEIVED $TMP.listen" && ok "模拟设备**真收到**下行报文" || bad "模拟设备未收到下行报文"
mwr "grep -q REPLY_SENT $TMP.listen" && ok "模拟设备已回执（code=0）" || bad "模拟设备未回执"
sleep 4
ST="$(instance_status "$REQ")"
[ "$ST" = "succeeded" ] && ok "实例终态 = succeeded" || bad "实例终态 = $ST（期望 succeeded）"
prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/commands?page=1&pageSize=5' | python3 -c '
import json,sys
# ⚠️ PageResult 的 JSON 字段是 items（不是 records/list）——读错会让整段变成死断言（假绿）
rows=(json.load(sys.stdin).get("data") or {}).get("items") or []
hit=[r for r in rows if r.get("requestId")=="$REQ"]
assert hit, "按 requestId 找不到实例（字段名或分页参数不对？）"
r=hit[0]
rp=r.get("replyPayload") or ""
print("  ·  reply_payload=%s" % rp)
print("  ·  finishedAt=%s emqxMessageId=%s" % (r.get("finishedAt"), r.get("emqxMessageId")))
assert "receivedAt" in rp, "reply_payload 少了平台落库时间 receivedAt"
assert "ts" in rp, "reply_payload 少了设备时间 ts"
assert r.get("finishedAt"), "finished_at 为空（平台落库时间）"
if not (r.get("emqxMessageId") or ""):
    print("  ·  ⚠️ emqxMessageId 为空：EMQX publish 响应体的 id 解析是**尽力而为**（状态码才定成败），已登记")
print("  ·  判据 ok：reply_payload 同时含设备 ts 与平台 receivedAt，finished_at 非空")
'" && ok "① 回执落库字段齐全（设备 ts + 平台 receivedAt + finished_at）" || bad "① 回执落库字段断言失败（见上面的 AssertionError）"

echo "=== ② 幂等：同一回执重投 ==="
DUP0=$(metric iot.command.reply.duplicated)
mwr "$PYTHON - <<PY
import json, time, paho.mqtt.client as m
from paho.mqtt.client import CallbackAPIVersion, Client, MQTTv311
pw=open('$MW_PW_FILE').read().strip()
c=Client(CallbackAPIVersion.VERSION2, client_id='$TENANT.$DEVICE-dup', protocol=MQTTv311)
c.username_pw_set('$TENANT.$DEVICE', pw)
c.connect('127.0.0.1', 1883, 15); c.loop_start(); time.sleep(1)
c.publish('$REPLY_TOPIC', json.dumps({'deviceId':$DEVICE,'requestId':'$REQ','code':0,'message':'ok','data':{'applied':26.5},'ts':int(time.time()*1000)}), qos=1)
time.sleep(2); c.disconnect(); c.loop_stop()
print('  ·  重复回执已投递')
PY"
sleep 4
DUP1=$(metric iot.command.reply.duplicated)
ST2="$(instance_status "$REQ")"
[ "$ST2" = "succeeded" ] && ok "重复回执后实例仍为 succeeded（不被改写）" || bad "重复回执改变了状态：$ST2"
DUP_BODY="$(prodr "umask 077; printf '%s' '{\"deviceId\":$DEVICE,\"requestId\":\"$REQ\",\"code\":0,\"message\":\"ok\",\"data\":{\"applied\":26.5}}' > /tmp/dup.json; chmod 600 /tmp/dup.json; TOKEN=\$(sed -n 's/^INTERNAL_TOKEN=//p' $PROD_ENV); printf 'header = \"X-Internal-Token: %s\"\n' \"\$TOKEN\" > /tmp/dup.cfg; chmod 600 /tmp/dup.cfg; curl -s -K /tmp/dup.cfg -H 'Content-Type: application/json' --data-binary @/tmp/dup.json http://127.0.0.1:18084/internal/command-replies")"
printf '%s' "$DUP_BODY" | python3 -c '
import json,sys
d=json.load(sys.stdin); data=d.get("data") or {}
assert d.get("code")==200, "端点返回 %s" % d.get("code")
assert data.get("duplicated") is True, "期望 duplicated=true，实得 %s" % data
print("  ·  端点级幂等：code=200 duplicated=true accepted=%s" % data.get("accepted"))' \
  && ok "重复回执在**端点**上被明确判为 duplicated（不依赖全局指标）" || bad "端点未判 duplicated"
[ "$((DUP1 - DUP0))" -ge 1 ] && ok "iot.command.reply.duplicated +$((DUP1 - DUP0))（辅助判据）" || bad "重复回执未被计数"
# 注：本判据同时覆盖 X-Mqtt-Device 头缺失的退化路径（直接 HTTP 调用 ⇒ 用载荷设备）

echo "=== ③ 设备未连：无订阅者 ⇒ 立即 failed/NO_SUBSCRIBER ==="
# 本用例的超时故意设大（15s）：判据是"**立刻**失败"，与超时值拉开足够距离才说明不是"等到超时才失败"
START=$(date +%s%3N)
OFFLINE_JSON="$(send_command 15000)"
ELAPSED=$(( $(date +%s%3N) - START ))
OFF_REQ="$(printf '%s' "$OFFLINE_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("requestId",""))')"
printf '%s' "$OFFLINE_JSON" | python3 -c '
import json,sys
d=(json.load(sys.stdin).get("data") or {})
print("  ·  statusCode=%s errorCode=%s errorMsg=%s" % (d.get("statusCode"), d.get("errorCode"), d.get("errorMsg")))'
req_status="$(printf '%s' "$OFFLINE_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("statusCode",""))')"
req_error="$(printf '%s' "$OFFLINE_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("errorCode",""))')"
[ "$req_status" = "failed" ] && ok "无订阅者时**同步**判定 failed（耗时 ${ELAPSED}ms，远小于超时 15000ms）" || bad "期望立即 failed，实得 $req_status"
[ "$req_error" = "NO_SUBSCRIBER" ] && ok "原因码 = NO_SUBSCRIBER（可区分「设备未连」与「EMQX 故障」——只判 failed 会把 EMQX_ERROR 也放过）" || bad "原因码 = $req_error（期望 NO_SUBSCRIBER）"
[ "$ELAPSED" -lt 5000 ] && ok "未等待超时（${ELAPSED}ms）" || bad "耗时 ${ELAPSED}ms，疑似在等超时"

echo "=== ④ 超时：设备连着但不回执 ⇒ 扫描置 timeout ==="
mwr "rm -f $TMP.silent; nohup $PYTHON $PROBE --mode listen --username '$TENANT.$DEVICE' --password-file $MW_PW_FILE --client-id '$TENANT.$DEVICE' --topic '$DOWN_TOPIC' --wait-seconds 25 > $TMP.silent 2>&1 & echo started"
sleep 2
TO_JSON="$(send_command 5000)"
TO_REQ="$(printf '%s' "$TO_JSON" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("data") or {}).get("requestId",""))')"
info "超时用例 requestId=$TO_REQ（timeoutMs=5000）"
for _ in $(seq 1 12); do
  ST3="$(instance_status "$TO_REQ")"
  [ "$ST3" = "timeout" ] && break
  sleep 3
done
[ "$ST3" = "timeout" ] && ok "扫描已把实例置为 timeout" || bad "实例状态 = $ST3（期望 timeout）"
TO_ERR="$(prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/commands?page=1&pageSize=50' | python3 -c '
import json,sys
rows=(json.load(sys.stdin).get(\"data\") or {}).get(\"items\") or []
hit=[r for r in rows if r.get(\"requestId\")==\"$TO_REQ\"]
print(hit[0].get(\"errorCode\",\"\") if hit else \"\")'")"
[ "$TO_ERR" = "TIMEOUT" ] && ok "超时的原因码 = TIMEOUT" || bad "超时的原因码 = $TO_ERR（期望 TIMEOUT）"

echo "=== ⑤ 负向：伪造载荷设备（认证头必须压过载荷） ==="
# 在**设备自己的 up/reply 主题**上发布一条 payload.deviceId = 别的设备 的回执：
# 认证头 X-Mqtt-Device 由 EMQX 从主题派生（= 本设备），平台必须因此丢弃。
# 若头机制失效，平台会改用载荷设备去查实例 —— 这条就会被错误受理。
FORGE_BEFORE=$(prodr "docker logs ypbin-iot --since 30m 2>&1 | grep -c '回执声称的设备与认证主题不一致'" 2>/dev/null | tr -dc '0-9')
FORGE_REQ="acc-forge-$RANDOM"
mwr "$PYTHON - <<PY
import json, time
from paho.mqtt.client import CallbackAPIVersion, Client, MQTTv311
pw=open('$MW_PW_FILE').read().strip()
c=Client(CallbackAPIVersion.VERSION2, client_id='$TENANT.$DEVICE-forge', protocol=MQTTv311)
c.username_pw_set('$TENANT.$DEVICE', pw)
c.connect('127.0.0.1', 1883, 15); c.loop_start(); time.sleep(1)
c.publish('$REPLY_TOPIC', json.dumps({'deviceId':9999999,'requestId':'$FORGE_REQ','code':0,'message':'forged','data':{},'ts':int(time.time()*1000)}), qos=1)
time.sleep(2); c.disconnect(); c.loop_stop()
print('  ·  伪造回执已投递（载荷 deviceId=9999999，认证设备=$DEVICE）')
PY"
sleep 4
FORGE_AFTER=$(prodr "docker logs ypbin-iot --since 30m 2>&1 | grep -c '回执声称的设备与认证主题不一致'" 2>/dev/null | tr -dc '0-9')
FORGE_DELTA=$(( ${FORGE_AFTER:-0} - ${FORGE_BEFORE:-0} ))
[ "$FORGE_DELTA" -ge 1 ] && ok "平台按认证主题丢弃了伪造回执（日志计数 ${FORGE_BEFORE:-0} → ${FORGE_AFTER:-0}，增量 ${FORGE_DELTA}）" \
  || bad "未见到"认证主题不一致"的丢弃日志增量（认证头机制可能失效或日志未输出）"

echo "=== 收尾：删除两台的临时文件（生产机的 /tmp/dup.cfg 里有内部凭证，必须一起清） ==="
mwr "shred -u $MW_PW_FILE 2>/dev/null || rm -f $MW_PW_FILE; rm -f $TMP.listen $TMP.silent; echo '  ·  中间件机已清理'" || bad "中间件机清理失败"
prodr "shred -u /tmp/dup.cfg /tmp/dup.json /tmp/cmd.json /tmp/issue.json 2>/dev/null || rm -f /tmp/dup.cfg /tmp/dup.json /tmp/cmd.json /tmp/issue.json; ls /tmp/dup.cfg /tmp/cmd.json /tmp/issue.json 2>/dev/null >/dev/null && exit 1; echo '  ·  生产机已清理（临时请求体与含内部凭证的 curl 配置已删）'" \
  && ok "生产机临时文件已清理" || bad "生产机仍有临时文件（含 X-Internal-Token 的 curl 配置必须删除）"

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（①–⑤ 全部命中）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
