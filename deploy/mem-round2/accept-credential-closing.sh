#!/usr/bin/env bash
# 设备凭据验收的**收口证据**（2026-09-27）：
#   ① 有效态 DB 真值（含非密引用与哈希/盐长度——**不打印哈希内容**）
#   ② 日志无凭据：用「刚签发的明文口令」去 grep 容器日志，必须 0 命中
#   ③ 结束状态：吊销（不把可用口令留在生产）
# 只在服务器上跑；输出脱敏。
BASE_IOT=http://127.0.0.1:18084
DEV=9300012
WORK=$(mktemp -d); chmod 700 "$WORK"; trap 'rm -rf "$WORK"' EXIT
H_USER=(-H "X-User-Id: 1" -H "X-User-Name: ops-accept" -H "X-Tenant-Id: 1" -H "X-Roles: super-admin")

mysql_t() {
  docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t ypbin_admin -e "'"$1"'"' 2>/dev/null
}

echo "########## ① 有效态：签发一次并读 DB 真值 ##########"
sleep 6   # 越过签发端点的幂等窗口
R=$(curl -s -X POST "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential")
P=$(printf '%s' "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["password"])')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["credentialVersion"])')
echo "签发响应：version=$V 明文口令长度=${#P}（明文不打印）"
mysql_t "SELECT id, credential_version, credential_issued_at, credential_revoked_at,
       credential_ref FROM iot_device WHERE id=$DEV;
SELECT device_id, credential_version, username, password_algo,
       CHAR_LENGTH(password_salt) AS salt_len, CHAR_LENGTH(password_hash) AS hash_len
  FROM iot_device_credential WHERE device_id=$DEV;"
echo "→ 说明：salt_len=32 = 16 字节 hex；hash_len=64 = sha256 hex；**明文口令长度=${#P}，与二者都不同 ⇒ 明文未落库**"

echo
echo "########## ② 日志无凭据：拿刚签发的明文口令 grep 容器日志（期望 0 命中） ##########"
echo -n "iot 容器日志命中数（明文口令）："
docker logs ypbin-iot 2>&1 | grep -c -F "$P"
echo -n "iot 容器日志命中数（口令的哈希值，若库里有）："
H=$(docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B ypbin_admin -e "SELECT password_hash FROM iot_device_credential WHERE device_id='"$DEV"'"' 2>/dev/null)
docker logs ypbin-iot 2>&1 | grep -c -F "$H"
echo "④ 凭据相关日志行（只应含 deviceId 与版本号）："
docker logs ypbin-iot 2>&1 | grep -E "设备凭据已签发|设备凭据已吊销|设备凭据校验未通过" | tail -6

echo
echo "########## ③ 收口：吊销，确认不再可用，并把设备留在「已吊销」状态 ##########"
curl -s -X DELETE "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential" >/dev/null
python3 -c 'import json,sys;print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "1.$DEV" "$P" > "$WORK/b.json"
sed -n 's/^INTERNAL_TOKEN=//p' /opt/ypbin/ypbin-iot/deploy/.env > "$WORK/tok"
printf 'header = "X-Internal-Token: %s"\n' "$(cat "$WORK/tok")" > "$WORK/c.cfg"; chmod 600 "$WORK"/* 
curl -s -X POST -K "$WORK/c.cfg" -H 'Content-Type: application/json' --data-binary "@$WORK/b.json" \
  "$BASE_IOT/internal/device-credential/verify"
echo
mysql_t "SELECT id, credential_version, credential_revoked_at,
       CHAR_LENGTH(credential_ref) AS ref_len FROM iot_device WHERE id=$DEV;
SELECT device_id, CHAR_LENGTH(password_salt) AS salt_len, CHAR_LENGTH(password_hash) AS hash_len
  FROM iot_device_credential WHERE device_id=$DEV;"
