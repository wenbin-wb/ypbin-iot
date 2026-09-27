#!/usr/bin/env bash
# 设备凭据四端点 + 校验路径的生产验收（2026-09-27 轮次）
#
# 只在服务器上跑。凭据卫生：
#   · 输出一律脱敏——口令只给长度与 sha256[:8]，哈希/盐只给长度；
#   · 内部 token 与明文口令**都不进 curl 命令行**（token 走 600 的 curl 配置文件 `-K`，
#     请求体走 600 的临时文件 `--data-binary @`），与 docs/DEPLOY-BACKEND.md §5.6.2 同一口径；
#   · 临时文件退出时删除。
#
# 用法：bash accept-credential-probe.sh
BASE_IOT=http://127.0.0.1:18084
DEV=9300012
TEN=1
H_USER=(-H "X-User-Id: 1" -H "X-User-Name: ops-accept" -H "X-Tenant-Id: 1" -H "X-Roles: super-admin")

WORK=$(mktemp -d)
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

PYREDACT='import sys,json,hashlib
d=json.load(sys.stdin)
def red(o):
    if isinstance(o,dict):
        r={}
        for k,v in o.items():
            if k.lower() in ("password","passwordhash","passwordsalt"):
                s=str(v) if v is not None else ""
                r[k]={"len":len(s),"sha256_8":hashlib.sha256(s.encode()).hexdigest()[:8]}
            else:
                r[k]=red(v)
        return r
    if isinstance(o,list): return [red(x) for x in o]
    return o
print(json.dumps(red(d),ensure_ascii=False,indent=2))'
redact() { python3 -c "$PYREDACT"; }

# 内部 token：从 deploy/.env 读（**iot 容器的 env 里没有它**——compose 只给 access 注入了 INTERNAL_TOKEN，
# 2026-09-27 实测：从容器 env 读会拿到空值，守卫 fail-closed 回 401）。值不打印，只写进 600 的 curl 配置文件
sed -n 's/^INTERNAL_TOKEN=//p' /opt/ypbin/ypbin-iot/deploy/.env > "$WORK/token"
chmod 600 "$WORK/token"
printf 'header = "X-Internal-Token: %s"\n' "$(cat "$WORK/token")" > "$WORK/curl.cfg"
chmod 600 "$WORK/curl.cfg"

# username/password 经 600 的请求体文件传入，不进命令行
verify() {
  python3 -c 'import json,sys;print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "$1" "$2" > "$WORK/body.json"
  chmod 600 "$WORK/body.json"
  curl -s -X POST -K "$WORK/curl.cfg" -H 'Content-Type: application/json' \
    --data-binary "@$WORK/body.json" "$BASE_IOT/internal/device-credential/verify"
}

mysql_t() {
  docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t ypbin_admin -e "'"$1"'"' 2>/dev/null
}

echo "########## 0) 前置：设备与凭据状态 ##########"
mysql_t "SELECT id, tenant_id, credential_version, credential_issued_at, credential_revoked_at,
       CHAR_LENGTH(credential_ref) AS ref_len FROM iot_device WHERE id=$DEV;"

echo
echo "########## 1) POST /devices/$DEV/credential —— 签发（明文一次性，仅给长度/指纹） ##########"
ISSUE1=$(curl -s -X POST "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential")
echo "$ISSUE1" | redact
P1=$(printf '%s' "$ISSUE1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["password"])')
echo "→ 口令1 已取到（不打印）；长度=${#P1}"

echo
echo "########## 2) GET /devices/$DEV/credential —— 只回元信息 ##########"
curl -s "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential" | redact

echo
echo "########## 3) GET /devices/$DEV/connection —— 接入信息（无口令） ##########"
curl -s "${H_USER[@]}" "$BASE_IOT/devices/$DEV/connection" | redact

echo
echo "########## 4) 校验路径（内部端点）：口令1 应 allow ##########"
verify "$TEN.$DEV" "$P1" | redact

echo
echo "########## 5) 重置：再次 POST（版本+1），旧口令必须失效、新口令可用 ##########"
# 签发端点带 @Idempotent（默认窗口 5s）：连点两次会被判 409，故此处等过窗口
echo "（等待 6 秒以越过签发端点的幂等窗口）"; sleep 6
echo "5a) 签发响应（新元信息；明文只给指纹）"
ISSUE2=$(curl -s -X POST "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential")
echo "$ISSUE2" | redact
P2=$(printf '%s' "$ISSUE2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["password"])')
echo "5b) 用【旧口令】校验（期望 deny + bad-password）"
verify "$TEN.$DEV" "$P1" | redact
echo "5c) 用【新口令】校验（期望 allow）"
verify "$TEN.$DEV" "$P2" | redact

echo
echo "########## 6) 吊销：DELETE ##########"
curl -s -X DELETE "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential" | redact
echo "6a) 吊销后用【最新口令】校验（期望 deny + revoked）"
verify "$TEN.$DEV" "$P2" | redact
echo "6b) 重复 DELETE（幂等，期望 code=0）"
curl -s -X DELETE "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential" | redact
echo "6c) 吊销后的元信息"
curl -s "${H_USER[@]}" "$BASE_IOT/devices/$DEV/credential" | redact

echo
echo "########## 7) 跨租户、不存在设备、非法 username ##########"
# 7a 与 6b 是**同一个方法 + 同一个参数**（deviceId）⇒ 必须等过签发端点的幂等窗口，
# 否则拿到的是 409「请勿重复提交」而不是真正的跨租户判据（本轮第一次跑就踩到了）
echo "（等待 6 秒以越过幂等窗口，让 7a 拿到真正的跨租户判据）"; sleep 6
echo "7a) 同一设备换租户头（X-Tenant-Id: 2）POST credential"
curl -s -X POST -H "X-User-Id: 1" -H "X-Tenant-Id: 2" -H "X-Roles: super-admin" \
  "$BASE_IOT/devices/$DEV/credential" | redact
echo "7b) 不存在的设备（id=99999999）GET credential"
curl -s "${H_USER[@]}" "$BASE_IOT/devices/99999999/credential" | redact
echo "7c) 跨租户 username 走校验（username=2.$DEV，期望 deny + device-not-found）"
verify "2.$DEV" "whatever-value" | redact
echo "7d) 非法 username（含通配符，期望 deny + malformed-username）"
verify "1.$DEV/#" "whatever-value" | redact

echo
echo "########## 8) DB 真值（不含口令/哈希：只给长度） ##########"
mysql_t "SELECT id, credential_version, credential_issued_at, credential_revoked_at,
       CHAR_LENGTH(credential_ref) AS ref_len FROM iot_device WHERE id=$DEV;
SELECT device_id, credential_version, username, password_algo,
       CHAR_LENGTH(password_salt) AS salt_len, CHAR_LENGTH(password_hash) AS hash_len
  FROM iot_device_credential WHERE device_id=$DEV;"

echo
echo "########## 9) 服务健康（部署后） ##########"
curl -s -m 8 "$BASE_IOT/actuator/health"; echo
