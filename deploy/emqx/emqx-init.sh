#!/usr/bin/env bash
# =============================================================================
# emqx-init.sh —— EMQX 5.8.9 部署初始化 / 配置红线断言（**幂等**，失败即显式报错）
# =============================================================================
# 在**中间件机**上运行（需要 /opt/emqx/.env 与已起好的 ypbin-emqx 容器）。
#
# 做两件事：
#   A. **断言**（不回显凭据；只回显"生效值"）：健康、认证链、授权红线 `no_match=deny`、授权源
#   B. **幂等 upsert** ACL 规则（设备模板 + 服务账号通配）并清授权缓存
#      —— 规则本体不在 HOCON 里（官方口径：经 Dashboard/HTTP API 添加）
#
# 设计依据：ypbin-iot/docs/EMQX-INGRESS-DESIGN.md §5.2 / §8.5 / H1
# 用法：
#   bash emqx-init.sh                     # 断言 + upsert 规则 + 跑自检
#   bash emqx-init.sh --no-probe          # 只做 A + B（不建临时测试账号）
#   bash emqx-init.sh --env-file /opt/emqx/.env
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
CONTAINER=ypbin-emqx
BASE_URL=http://127.0.0.1:18093
SELFCHECK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/emqx-selfcheck.sh"
RUN_PROBE=1

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --no-probe) RUN_PROBE=0; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE（先跑 gen-env.sh）" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"

fail=0
ok()   { printf '  ✅ %s\n' "$1"; }
bad()  { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# api <method> <path> [json]  → 把「HTTP 状态码」写进全局 API_CODE，正文进 API_BODY
api() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-s -o /tmp/.emqx-init-body -w '%{http_code}' -X "$method"
              -u "$EMQX_API_KEY:$EMQX_API_SECRET" -H 'Content-Type: application/json')
  [ -n "$data" ] && args+=(-d "$data")
  API_CODE="$(curl "${args[@]}" "$BASE_URL$path")"
  API_BODY="$(cat /tmp/.emqx-init-body)"
  rm -f /tmp/.emqx-init-body
}
expect() { # expect <desc> <want-code>
  if [ "$API_CODE" = "$2" ]; then ok "$1（HTTP $API_CODE）"; else bad "$1：期望 HTTP $2，实得 $API_CODE；响应=$API_BODY"; fi
}

echo "=== A. 配置红线断言（$(date -u +%FT%TZ) UTC）==="

# ── A0 容器与节点健康（官方健康检查命令）──────────────────────────────────────
if docker exec "$CONTAINER" /opt/emqx/bin/emqx ctl status >/tmp/.emqx-status 2>&1; then
  ok "容器内 emqx ctl status：$(tr '\n' ' ' </tmp/.emqx-status)"
else
  bad "emqx ctl status 失败：$(cat /tmp/.emqx-status)"
fi
rm -f /tmp/.emqx-status

health="$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null || echo unknown)"
[ "$health" = "healthy" ] && ok "docker healthcheck = healthy" || bad "docker healthcheck = $health"

# ── A1 授权红线（H1：官方默认 allow ⇒ 必须显式 deny）──────────────────────────
api GET /api/v5/authorization/settings
expect "GET /authorization/settings" 200
nomatch="$(printf '%s' "$API_BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("no_match",""))' 2>/dev/null)"
denyact="$(printf '%s' "$API_BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("deny_action",""))' 2>/dev/null)"
[ "$nomatch" = "deny" ] && ok "authorization.no_match = deny（红线 H1）" || bad "authorization.no_match = '$nomatch'（必须 deny）"
[ "$denyact" = "ignore" ] && ok "authorization.deny_action = ignore" || bad "authorization.deny_action = '$denyact'（期望 ignore）"

# ── A2 认证链（内置库 + sha256 + 盐后缀）──────────────────────────────────────
api GET /api/v5/authentication
expect "GET /authentication" 200
printf '%s' "$API_BODY" | python3 -c '
import json,sys
d=json.load(sys.stdin)
want={"mechanism":"password_based","backend":"built_in_database","user_id_type":"username"}
hit=[c for c in d if all(c.get(k)==v for k,v in want.items())]
if not hit: print("  ❌ 未找到 password_based:built_in_database 认证链"); sys.exit(3)
c=hit[0]; a=c.get("password_hash_algorithm") or {}
print("  ✅ 认证链 id=%s mechanism=%s backend=%s enable=%s" % (c.get("id"),c.get("mechanism"),c.get("backend"),c.get("enable")))
if a.get("name")=="sha256" and a.get("salt_position")=="suffix":
    print("  ✅ password_hash_algorithm = sha256 + salt_position=suffix")
else:
    print("  ❌ password_hash_algorithm = %s（期望 sha256 + suffix）" % a); sys.exit(3)
' || fail=1

# ── A3 授权源：built_in_database ──────────────────────────────────────────────
api GET /api/v5/authorization/sources
expect "GET /authorization/sources" 200
printf '%s' "$API_BODY" | python3 -c '
import json,sys
s=json.load(sys.stdin).get("sources",[])
b=[x for x in s if x.get("type")=="built_in_database" and x.get("enable")]
if b: print("  ✅ 授权源 built_in_database enable=true max_rules=%s" % b[0].get("max_rules"))
else: print("  ❌ 缺少启用的 built_in_database 授权源：%s" % s); sys.exit(3)
' || fail=1

echo
echo "=== B. ACL 规则幂等 upsert（设计 §5.2）==="

# 设备规则：一条模板服务全部设备（靠 client_attrs 展开成"它自己"的主题）
# ⚠️ 注意 body 形状的实测差异（5.8.9）：
#    · /rules/all   → `{"rules":[...]}`（**对象**；传数组会 400 bad_value_for_struct）
#    · /rules/users → `[{"username":...,"rules":[...]}, ...]`（**数组**）
api DELETE /api/v5/authorization/sources/built_in_database/rules/all
case "$API_CODE" in 204|404) ok "DELETE rules/all（幂等前置清理，HTTP $API_CODE）";; *) bad "DELETE rules/all：HTTP $API_CODE";; esac

DC='${client_attrs.tenant}'
DD='${client_attrs.device}'
DEV_BODY="$(printf '{"rules":[{"action":"publish","permission":"allow","topic":"ypbin/v1/%s/%s/up/#"},{"action":"subscribe","permission":"allow","topic":"ypbin/v1/%s/%s/down/#"}]}' "$DC" "$DD" "$DC" "$DD")"
api POST /api/v5/authorization/sources/built_in_database/rules/all "$DEV_BODY"
expect "POST rules/all（设备模板：只能发自己 up/、只能订自己 down/）" 204

api GET /api/v5/authorization/sources/built_in_database/rules/all
printf '%s' "$API_BODY" | python3 -c '
import json,sys
r=json.load(sys.stdin).get("rules",[])
want=2
if len(r)==want:
    print("  ✅ rules/all 读回 %d 条：%s" % (len(r), [(x["action"],x["topic"]) for x in r]))
    sys.exit(0)
print("  ❌ rules/all 读回 %d 条（期望 %d）：%s" % (len(r),want,r))
sys.exit(3)
' || fail=1

# 服务账号规则（username 分组）：入站订阅 / 下行发布通配
for u in svc-ingress svc-egress; do
  api DELETE "/api/v5/authorization/sources/built_in_database/rules/users/$u"
  # 404 = 本来就没有 ⇒ 幂等成功
  case "$API_CODE" in 204|404) ok "DELETE rules/users/$u（HTTP $API_CODE）";; *) bad "DELETE rules/users/$u：HTTP $API_CODE";; esac
done
SVC_BODY='[{"username":"svc-ingress","rules":[{"action":"subscribe","permission":"allow","topic":"ypbin/v1/+/+/up/#"}]},{"username":"svc-egress","rules":[{"action":"publish","permission":"allow","topic":"ypbin/v1/+/+/down/#"}]}]'
api POST /api/v5/authorization/sources/built_in_database/rules/users "$SVC_BODY"
expect "POST rules/users（服务账号通配规则）" 204

api DELETE /api/v5/authorization/cache
expect "DELETE /authorization/cache（清授权缓存）" 204

api GET /api/v5/authorization/sources/built_in_database/rules/users
printf '%s' "$API_BODY" | python3 -c '
import json,sys
d=json.load(sys.stdin).get("data",[])
print("  ✅ rules/users 读回：" + ", ".join("%s(%d 条)"%(x["username"],len(x["rules"])) for x in d))
' || true

echo
if [ "$RUN_PROBE" -eq 1 ]; then
  echo "=== C. 负向/正向自检（证明 ACL 真的生效，而不是「绿色通过」）==="
  if [ -x "$SELFCHECK" ] || [ -f "$SELFCHECK" ]; then
    bash "$SELFCHECK" --env-file "$ENV_FILE" --base-url "$BASE_URL" || fail=1
  else
    bad "找不到自检脚本：$SELFCHECK"
  fi
else
  echo "=== C. 自检已按 --no-probe 跳过（**本次运行不算完成验收**）==="
fi

echo
if [ "$fail" -eq 0 ]; then echo "结论: PASS（配置红线与 ACL 规则均已生效）"; exit 0; fi
echo "结论: FAIL（见上面的 ❌）"; exit 1
