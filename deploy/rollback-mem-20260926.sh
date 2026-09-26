#!/usr/bin/env bash
# rollback-mem-20260926.sh —— 回滚 2026-09-26 的内存调参（JAVA_OPTS / JVM_* / mem_limit）
#
# 生产机路径：/opt/ypbin/ypbin-iot/deploy/rollback-mem-20260926.sh
#
# 回滚范围：**仅**本次改动（docker-compose.override.yml 的 JAVA_OPTS / JVM_XMS / JVM_XMX /
#           JVM_XMN 键）。不触碰 IoTDB 的 MEMORY_SIZE（那是 2026-09-25 的既有覆盖）。
# 不动：容器集合、数据卷、镜像、1Panel openresty、其它人的容器。
#
# ⚠️ 本脚本**不覆盖 zram**：请另外执行 `zram-swap-down.sh`（两件事互相独立）。
#
# ⚠️ 回滚 = 把机器退回**当初发生 OOM 的那套状态**：
#     无任何 cgroup 内存上限 / nacos `-Xmx1g -Xmn512m` / swap=0。
#    2026-09-26 10:35:55 的全局 OOM 就是在这套状态下发生的（被杀的是 ypbin-nacos）。
#    回滚前请确认你真的要退回该状态。
#
# 重启顺序（**严格串行，禁止并发**）：先叶子依赖，再被依赖者，nacos 最后。
#   ⚠️ 每次 `up -d` 必须带 `--no-deps`，否则 compose 会连带重建依赖服务。
#   ⚠️ 实测教训：即使串行重启，ZGC 的 ZUncommitDelay=300s 会让每个 JVM 在启动后 5 分钟内
#      把堆保持在接近 -Xmx 的高水位 ⇒ 连续重启之间**内存需求会重叠**，仍可能形成峰值。
set -euo pipefail

DIR=/opt/ypbin/ypbin-iot/deploy
cd "$DIR"
COMPOSE=(docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml)

BAK="${1:-}"
if [ -z "$BAK" ]; then
  # 缺陷修正：备份由 `cp -a` 生成，**mtime 被保留为源文件时间**（当前这份仍是 09-25 01:18），
  # 因此不能用 `ls -t`（按 mtime）挑最新 —— 必须按**文件名里的时间戳**排序。
  BAK="$(ls -1 docker-compose.override.yml.bak-* 2>/dev/null | sort | tail -1 || true)"
fi
[ -n "$BAK" ] && [ -f "$BAK" ] || { echo "找不到备份文件，请显式传入路径" >&2; exit 1; }

echo "== 将用备份覆盖 override：$BAK"
echo "== 当前 override 的 md5：$(md5sum docker-compose.override.yml | awk '{print $1}')"
echo "== 备份的 md5：        $(md5sum "$BAK" 2>/dev/null | awk '{print $1}')"
echo "== 备份内容（应只含 iotdb MEMORY_SIZE）："
grep -vE '^\s*#|^\s*$' "$BAK" || true
read -r -p "确认回滚？(yes/N) " a; [ "$a" = "yes" ] || { echo "已取消"; exit 1; }

cp -a docker-compose.override.yml "docker-compose.override.yml.prerollback-$(date -u +%Y%m%d-%H%M%S)"
cp -a "$BAK" docker-compose.override.yml

echo "== 合并后残留的内存键（应为空）："
"${COMPOSE[@]}" config 2>/dev/null | grep -nE "JAVA_OPTS|JVM_XMS|JVM_XMX|JVM_XMN|mem_limit" || echo "  (无 —— 配置层已回到原始状态)"

# 就绪轮询（替代固定 sleep）
wait_ready() { # $1=container  $2=timeout_s
  local c=$1 t=${2:-120} i=0
  while [ $i -lt "$t" ]; do
    local st; st=$(docker inspect "$c" --format '{{.State.Health.Status}}' 2>/dev/null || echo none)
    if [ "$st" = "healthy" ] || [ "$st" = "none" ]; then
      # 无 healthcheck 的服务用日志里的 "Started ...Application" 判就绪
      if docker logs "$c" 2>&1 | grep -q "Started .*Application in"; then return 0; fi
    fi
    sleep 5; i=$((i+5))
  done
  return 1
}

for svc in ypbin-access ypbin-iot ypbin-system ypbin-auth ypbin-gateway nacos; do
  echo "== 重建 $svc（--no-deps，串行）"
  "${COMPOSE[@]}" up -d --no-build --no-deps "$svc"
  wait_ready "$svc" 180 && echo "   $svc ready" || echo "   ⚠️ $svc 未在 180s 内就绪"
  # 缺陷修正：校验**运行期** HostConfig.Memory，而不只是配置层
  echo "   runtime MemLimit=$(docker inspect "$svc" --format '{{.HostConfig.Memory}}') OOM=$(docker inspect "$svc" --format '{{.State.OOMKilled}}')"
  free -m | sed -n 2p | sed 's/^/   /'
done

echo
echo "== 回滚完成校验 =="
# 缺陷修正：原脚本只查 18080/18081/18082 的 /actuator/health，而这三个端口返回的是
# 「业务 404 包在 HTTP 200 里」（{"code":404,...,"success":false}）—— 对"坏没坏"毫无鉴别力。
# 真正的判据：
#   ① 容器日志里的 Started xxxApplication；② Nacos 各服务 healthyInstanceCount；
#   ③ 18084(iot) 的 /actuator/health 是**真**健康文档；④ IoTDB 行数是否仍在增长。
echo "-- ① Started Application --"
for c in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  printf '   %-16s %s\n' "$c" "$(docker logs "$c" 2>&1 | grep -oE 'Started [A-Za-z]+Application in [0-9.]+ seconds' | tail -1)"
done
echo "-- ② 真健康端点（18084 iot；18086 access 的 GET health 在本机已知会挂起） --"
echo -n "   18084 /actuator/health: "; curl -s -m 10 http://127.0.0.1:18084/actuator/health; echo
echo -n "   19000 (iot-ui -> gateway): "; curl -s -o /dev/null -w "%{http_code}\n" -m 8 http://127.0.0.1:19000/
echo "-- ③ Nacos 注册（token 只报长度） --"
U=$(grep -E "^NACOS_ADMIN_USERNAME=" .env | cut -d= -f2-); P=$(grep -E "^NACOS_ADMIN_PASSWORD=" .env | cut -d= -f2-)
TK=$(curl -s -m 8 -X POST "http://127.0.0.1:8848/nacos/v3/auth/user/login" -d "username=$U&password=$P" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
echo "   token_len=${#TK}"
curl -s -m 8 -H "accessToken: $TK" "http://127.0.0.1:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId=public" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('   totalCount=',d['totalCount']);[print('    ',i['name'],'healthy=',i['healthyInstanceCount'],'/',i['ipCount']) for i in d['pageItems']]" 2>/dev/null || echo "   (解析失败)"
echo "-- ④ zram 未回滚（如需要请另跑 zram-swap-down.sh） --"
swapon --show || echo "   (无 swap)"
