#!/usr/bin/env bash
# =============================================================================
# accept-emqx-ingress.sh —— MQTT 入站端到端验收（**可复跑**，一次跑完打印五项判据）
# =============================================================================
# 在**运维机（本仓所在机器）**上运行：脚本自己 ssh 到两台机器取证据，判据全部是「差值」与「真值查询」，
# 不依赖任何人工眼看。
#
# 五项验收判据（与任务书 ①–⑤ 一一对应）：
#   ① 平台侧数值落库：IoTDB `iot.reading` 出现本条消息的 time+value_double（按 ts 精确归属）
#   ② 曲线点：`GET /devices/{id}/series?propertyId=…` 返回该点
#   ③ 指标增长：`iot.timeseries.write.rows` / `iot.mqtt.ingest.accepted` 增长
#   ④ 幂等：同一 requestId 重投 ⇒ duplicated、IoTDB 不新增行、MySQL 回执仍 1 行
#   ⑤ ACL 负向：匿名连接与「发别人主题」被拒（**用 EMQX 指标差值判定**）
#
# 为什么 ⑤ 必须用指标：`authorization.deny_action=ignore`（设计 H1 要求）⇒ 越权 publish 在
# MQTT 3.1.1 下**不会断开、也不会返回错误**（客户端看到的是正常 PUBACK），只能看
# `packets.publish.auth_error` / `authorization.nomatch` 的增量。
#
# 凭据纪律：设备口令由本脚本现签现用，明文只经 `ssh | ssh` 管道落到中间件机 600 文件，
# 不打印、不进 argv；EMQX API Key 只在中间件机的 600 curl 配置文件里；退出时清理临时口令文件。
#
# ⚠️ **副作用（每次运行都会发生，知情后使用）**：脚本第 0 步会**轮换该设备的凭据**（version +1、
#   旧口令立即失效），收尾 `shred` 掉明文 ⇒ 跑完后这台设备**没有"已知口令"**。若现场有真设备在用，
#   跑完请重新签发一次并把明文交付设备；CI/复跑场景无影响。
#
# 用法：
#   bash deploy/emqx/accept-emqx-ingress.sh \
#        --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
#        --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test" \
#        --device 9300012 --tenant 1 --property temperature
# =============================================================================
set -uo pipefail

MW_SSH=""; PROD_SSH=""
DEVICE=9300012; TENANT=1; PROPERTY=temperature; OTHER_DEVICE=9300013
PROD_ENV=/opt/ypbin/ypbin-iot/deploy/.env
MW_PW_FILE=/opt/emqx/ingress/device-password
VALUE=23.4; FEEDER_VALUE=23.5

while [ $# -gt 0 ]; do
  case "$1" in
    --mw-ssh) MW_SSH="$2"; shift 2 ;;
    --prod-ssh) PROD_SSH="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --tenant) TENANT="$2"; shift 2 ;;
    --property) PROPERTY="$2"; shift 2 ;;
    --other-device) OTHER_DEVICE="$2"; shift 2 ;;
    --prod-env) PROD_ENV="$2"; shift 2 ;;
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

# 平台指标取值（只读回数）
metric() {
  prodr "curl -s -m 8 'http://127.0.0.1:18084/actuator/metrics/$1' | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(int(sum(x[\"value\"] for x in d.get(\"measurements\",[]))))
except Exception: print(-1)'"
}
# IoTDB 行数（设备维度）
iotdb_rows() {
  prodr "PW=\$(sed -n 's/^IOTDB_PASSWORD=//p' $PROD_ENV); docker exec ypbin-iotdb bash -c \"start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw \\\"\$PW\\\" -sql_dialect table -e \\\"SELECT count(*) FROM iot.reading WHERE device_id='$DEVICE'\\\"\" 2>/dev/null | grep -oE '^[|][[:space:]]*[0-9]+[[:space:]]*[|]' | head -1 | tr -dc '0-9'"
}
# EMQX 指标取值（中间件机）
emqx_metric() {
  mwr "set -a; . /opt/emqx/.env; set +a; P=\$(mktemp -d); chmod 700 \$P; printf 'user = \"%s:%s\"\n' \"\$EMQX_API_KEY\" \"\$EMQX_API_SECRET\" > \$P/c; chmod 600 \$P/c; curl -s --max-time 10 -K \$P/c 'http://127.0.0.1:18093/api/v5/metrics?aggregate=true' | python3 -c 'import json,sys;print(json.load(sys.stdin).get(\"$1\",-1))'; rm -rf \$P"
}

echo "=== 0) 前置：签发设备凭据（含 EMQX 同步）==="
prodr "umask 077; curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Name: ops-accept' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' http://127.0.0.1:18084/devices/$DEVICE/credential > /tmp/issue.json; chmod 600 /tmp/issue.json; python3 -c '
import json,hashlib
d=json.load(open(\"/tmp/issue.json\")); data=d.get(\"data\") or {}; pw=data.get(\"password\") or \"\"
print(\"R.code=%s username=%s version=%s pwlen=%d pw_sha8=%s\" % (d.get(\"code\"), data.get(\"username\"), data.get(\"credentialVersion\"), len(pw), hashlib.sha256(pw.encode()).hexdigest()[:8]))
'" >/dev/null 2>&1 || true
prodr "python3 -c 'import json,hashlib
d=json.load(open(\"/tmp/issue.json\")); data=d.get(\"data\") or {}; pw=data.get(\"password\") or \"\"
print(\"R.code=%s username=%s version=%s pwlen=%d pw_sha8=%s\" % (d.get(\"code\"), data.get(\"username\"), data.get(\"credentialVersion\"), len(pw), hashlib.sha256(pw.encode()).hexdigest()[:8]))'" \
  | sed 's/^/  ·  /'
prodr "python3 -c 'import json;print((json.load(open(\"/tmp/issue.json\")).get(\"data\") or {}).get(\"password\",\"\"),end=\"\")'" \
  | mwr "umask 077; cat > $MW_PW_FILE; chmod 600 $MW_PW_FILE; echo \"  ·  明文口令已落到中间件机 600 文件（长度 \$(wc -c < $MW_PW_FILE)）\""
# EMQX 侧账号存在性（只打印用户名）
mwr "set -a; . /opt/emqx/.env; set +a; P=\$(mktemp -d); chmod 700 \$P; printf 'user = \"%s:%s\"\n' \"\$EMQX_API_KEY\" \"\$EMQX_API_SECRET\" > \$P/c; chmod 600 \$P/c; curl -s --max-time 10 -K \$P/c 'http://127.0.0.1:18093/api/v5/authentication/password_based%3Abuilt_in_database/users?limit=200' | python3 -c 'import json,sys;print(\"  ·  EMQX 侧账号：\", sorted(u[\"user_id\"] for u in json.load(sys.stdin).get(\"data\",[])))'; rm -rf \$P"

echo "=== 1) 基线 ==="
TS=$(date +%s%3N)
REQ="acc-e2e-$TS"
M_ACCEPT0=$(metric iot.mqtt.ingest.accepted)
M_DUP0=$(metric iot.mqtt.ingest.duplicated)
M_ROWS0=$(metric iot.timeseries.write.rows)
DB0=$(iotdb_rows)
EMQX_PE0=$(emqx_metric packets.publish.auth_error)
EMQX_NM0=$(emqx_metric authorization.nomatch)
EMQX_CE0=$(emqx_metric packets.connack.auth_error)
info "requestId=$REQ ts=$TS"
info "平台 accepted=$M_ACCEPT0 duplicated=$M_DUP0 write.rows=$M_ROWS0 IoTDB(device) rows=$DB0"
info "EMQX publish.auth_error=$EMQX_PE0 nomatch=$EMQX_NM0 connack.auth_error=$EMQX_CE0"

echo "=== 2) ⑤ 负向：匿名连接 + 发别人主题（指标差值）==="
mwr "/opt/emqx/venv/bin/python3 /opt/emqx/mqtt-device-probe.py --topic 'ypbin/v1/$TENANT/$DEVICE/up/property' --payload '{}' >/dev/null 2>&1; echo \"  ·  匿名连接退出码=\$?（2=被拒）\""
mwr "/opt/emqx/venv/bin/python3 /opt/emqx/mqtt-device-probe.py --username '$TENANT.$DEVICE' --password-file $MW_PW_FILE --client-id '$TENANT.$DEVICE' --topic 'ypbin/v1/$TENANT/$OTHER_DEVICE/up/property' --payload '{}' >/dev/null 2>&1; echo \"  ·  越权发布退出码=\$?（0=客户端看不出被拒，必须看指标）\""
EMQX_CE1=$(emqx_metric packets.connack.auth_error)
EMQX_PE1=$(emqx_metric packets.publish.auth_error)
EMQX_NM1=$(emqx_metric authorization.nomatch)
[ "$((EMQX_CE1 - EMQX_CE0))" -ge 1 ] && ok "匿名连接被拒：connack.auth_error +$((EMQX_CE1 - EMQX_CE0))" || bad "匿名连接未被拒"
if [ "$((EMQX_PE1 - EMQX_PE0))" -ge 1 ] && [ "$((EMQX_NM1 - EMQX_NM0))" -ge 1 ]; then
  ok "越权发布被拒：publish.auth_error +$((EMQX_PE1 - EMQX_PE0))、nomatch +$((EMQX_NM1 - EMQX_NM0))"
else
  bad "越权发布未被拒（auth_error +$((EMQX_PE1 - EMQX_PE0))、nomatch +$((EMQX_NM1 - EMQX_NM0))）"
fi

echo "=== 3) 正向：发自己的 topic（数值落库 + 曲线 + 指标）==="
PAYLOAD="{\"requestId\":\"$REQ\",\"propertyId\":\"$PROPERTY\",\"value\":\"$VALUE\",\"quality\":\"GOOD\",\"ts\":$TS,\"pollIntervalMs\":30000}"
mwr "/opt/emqx/venv/bin/python3 /opt/emqx/mqtt-device-probe.py --username '$TENANT.$DEVICE' --password-file $MW_PW_FILE --client-id '$TENANT.$DEVICE' --topic 'ypbin/v1/$TENANT/$DEVICE/up/property' --payload '$PAYLOAD'; echo \"  ·  发布退出码=\$?\""
sleep 6
M_ACCEPT1=$(metric iot.mqtt.ingest.accepted); M_ROWS1=$(metric iot.timeseries.write.rows); DB1=$(iotdb_rows)
[ "$((M_ACCEPT1 - M_ACCEPT0))" -ge 1 ] && ok "③ iot.mqtt.ingest.accepted +$((M_ACCEPT1 - M_ACCEPT0))" || bad "入站受理计数未增长"
# ⚠️ 下面两条**刻意不作为 PASS 判据**（独立复核指出不特异）：access 通道每 2s 也在写同一设备同一点位，
#    设备总行数与 write.rows 会被它推高 —— 入站断掉时这两条依然可能为绿。承重判据是紧随其后的
#    「按 ts+value 精确定位恰好 1 行」（以及 accepted 与 series）。
info "③ iot.timeseries.write.rows +$((M_ROWS1 - M_ROWS0))、IoTDB 设备总行数 +$((DB1 - DB0))（含 access 并行写入，仅供参照）"
prodr "PW=\$(sed -n 's/^IOTDB_PASSWORD=//p' $PROD_ENV); docker exec ypbin-iotdb bash -c \"start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw \\\"\$PW\\\" -sql_dialect table -e \\\"SELECT time, tenant_id, device_id, property_id, value_double, quality FROM iot.reading WHERE device_id='$DEVICE' AND time >= $((TS-1500)) AND time <= $((TS+1500)) ORDER BY time\\\"\" 2>/dev/null | grep -E '\\|' | tail -6 | sed 's/^/  ·  /'"
hit=$(prodr "PW=\$(sed -n 's/^IOTDB_PASSWORD=//p' $PROD_ENV); docker exec ypbin-iotdb bash -c \"start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw \\\"\$PW\\\" -sql_dialect table -e \\\"SELECT count(*) FROM iot.reading WHERE device_id='$DEVICE' AND time >= $((TS-1500)) AND time <= $((TS+1500)) AND value_double=$VALUE\\\"\" 2>/dev/null | grep -oE '^[|][[:space:]]*[0-9]+[[:space:]]*[|]' | head -1 | tr -dc '0-9'")
[ "${hit:-0}" = "1" ] && ok "① 本消息在 IoTDB 恰好 1 行（ts+value 精确归属，与 access 通道的 $FEEDER_VALUE 可区分）" || bad "① 按 ts+value 定位到 $hit 行（期望 1）"
SERIES=$(prodr "curl -s -H 'X-User-Id: 1' -H 'X-Tenant-Id: $TENANT' -H 'X-Roles: super-admin' 'http://127.0.0.1:18084/devices/$DEVICE/series?propertyId=$PROPERTY&from=$((TS-1500))&to=$((TS+1500))'")
printf '%s' "$SERIES" | grep -q "\"$TS\"" && ok "② series 接口返回该点：$(printf '%s' "$SERIES" | head -c 220)" || bad "② series 未返回该点：$(printf '%s' "$SERIES" | head -c 220)"

echo "=== 4) ④ 幂等：同一 requestId 重投 ==="
mwr "/opt/emqx/venv/bin/python3 /opt/emqx/mqtt-device-probe.py --username '$TENANT.$DEVICE' --password-file $MW_PW_FILE --client-id '$TENANT.$DEVICE' --topic 'ypbin/v1/$TENANT/$DEVICE/up/property' --payload '$PAYLOAD' >/dev/null 2>&1; echo \"  ·  重投退出码=\$?\""
sleep 6
M_DUP1=$(metric iot.mqtt.ingest.duplicated)
# 重投后重新按 ts+value 精确定位：**必须仍为 1 行**（设备总行数会被 access 通道并行写入推高，不能当判据）
hit2=$(prodr "PW=\$(sed -n 's/^IOTDB_PASSWORD=//p' $PROD_ENV); docker exec ypbin-iotdb bash -c \"start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw \\\"\$PW\\\" -sql_dialect table -e \\\"SELECT count(*) FROM iot.reading WHERE device_id='$DEVICE' AND time >= $((TS-1500)) AND time <= $((TS+1500)) AND value_double=$VALUE\\\"\" 2>/dev/null | grep -oE '^[|][[:space:]]*[0-9]+[[:space:]]*[|]' | head -1 | tr -dc '0-9'")
RECEIPT=$(prodr "umask 077; printf \"SELECT count(*) FROM iot_mqtt_ingest_receipt WHERE request_id='%s';\" '$REQ' > /tmp/q.sql; chmod 600 /tmp/q.sql; docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" ypbin_admin -N' < /tmp/q.sql 2>/dev/null | tr -dc '0-9'")
[ "$((M_DUP1 - M_DUP0))" -ge 1 ] && ok "④ iot.mqtt.ingest.duplicated +$((M_DUP1 - M_DUP0))" || bad "幂等计数未增长"
[ "${hit2:-0}" = "1" ] && ok "④ 重投后按 ts+value 精确定位仍为 1 行（未新增时序行；设备总行数增长来自 access 通道并行写入）" || bad "④ 重投后精确定位到 ${hit2:-?} 行（期望 1）"
[ "${RECEIPT:-0}" = "1" ] && ok "④ MySQL 幂等回执行数=1" || bad "④ 回执行数=${RECEIPT:-?}（期望 1）"

echo "=== 5) 收尾：清理临时口令文件 ==="
mwr "shred -u $MW_PW_FILE 2>/dev/null || rm -f $MW_PW_FILE; ls $MW_PW_FILE 2>/dev/null && echo '  ❌ 口令文件仍在' || echo '  ✅ 已删除'" || bad "清理口令文件失败"

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（①–⑤ 全部命中）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
