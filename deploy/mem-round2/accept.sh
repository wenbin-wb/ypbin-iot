#!/usr/bin/env bash
# accept.sh —— 只读端到端验收检查（重启前后各跑一次，逐项核对）
#
# 判据（刻意不用 18080/18081/18082 的 /actuator/health：那是**业务 404 包在 HTTP 200 里**，无鉴别力）：
#   [1] 容器全 Up（列出非 running 的）
#   [2] 每服务日志里有 `Started XxxApplication in N seconds`
#   [3] Nacos 注册实例数（只报 token 长度，不打印凭据）
#   [4] 19000 = 200（iot-ui→gateway→nginx 真链路）；18084 = 真健康端点 {"status":"UP"}
#   [5] IoTDB 指标端点 127.0.0.1:9091/9092 可读
#   [6] zram + swappiness 成对就位
#   [7] 采集链路：iot.timeseries.write.rows 在增长（连续两次采样对比）
#   [8] free / df
#   [9] 内核累计 OOM 次数
set -uo pipefail
ENVF=/opt/ypbin/ypbin-iot/deploy/.env
STAMP=$(date -u +%FT%TZ)
echo "==================== accept.sh @ $STAMP (UTC) ===================="

echo "--- [1] 容器状态 ----"
docker ps --format "{{.Names}}\t{{.Status}}" | sort
echo "非 running 的容器（正常情况只应有 ypbin-iotdb-init Exited(0)）:"
docker ps -a --format "{{.Names}}\t{{.Status}}" | grep -vE "Up " || echo "  （无）"
echo

echo "--- [2] Started XxxApplication ----"
for c in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  printf '  %-16s ' "$c"
  docker logs "$c" 2>&1 | grep -oE 'Started [A-Za-z0-9_]+Application in [0-9.]+ seconds' | tail -1 || true
  echo
done
echo

echo "--- [3] Nacos 实例数 ----"
# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
TOK=$(curl -s --max-time 10 -X POST 'http://127.0.0.1:8848/nacos/v3/auth/user/login' \
        --data-urlencode "username=${NACOS_ADMIN_USERNAME:-nacos}" \
        --data-urlencode "password=${NACOS_ADMIN_PASSWORD:-}" \
      | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("accessToken",""))
except Exception: print("")')
echo "  accessToken 长度 = ${#TOK}  （按纪律只报长度，不打印内容）"
curl -s --max-time 10 "http://127.0.0.1:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId=public&accessToken=${TOK}" \
  | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
    data=d.get("data") or {}
    print("  totalCount =", data.get("totalCount"))
    for s in (data.get("pageItems") or []):
        print("   - %-16s healthyInstanceCount=%s ipCount=%s" % (s.get("name"), s.get("healthyInstanceCount"), s.get("ipCount")))
except Exception as e: print("  nacos 查询失败:", e)'
echo

echo "--- [4] 19000 / 18084 ----"
printf '  19000 http=%s\n' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:19000/)"
printf '  18084 health='; curl -s --max-time 8 http://127.0.0.1:18084/actuator/health; echo
echo

echo "--- [5] IoTDB 指标端点 ----"
for p in 9091 9092; do
  printf '  %s/metrics http=%s bytes=%s\n' "$p" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1:$p/metrics)" \
    "$(curl -s --max-time 15 http://127.0.0.1:$p/metrics | wc -c)"
done
docker ps --filter name=ypbin-iotdb --format "  iotdb: {{.Status}} {{.Ports}}"
echo

echo "--- [6] zram / swappiness ----"
swapon --show | sed 's/^/  /'
printf '  /proc/sys/vm/swappiness = %s\n' "$(cat /proc/sys/vm/swappiness)"
printf '  zram-swap.service: active=%s enabled=%s\n' "$(systemctl is-active zram-swap.service)" "$(systemctl is-enabled zram-swap.service)"
printf '  mm_stat = %s\n' "$(cat /sys/block/zram0/mm_stat 2>/dev/null || echo 'N/A')"
echo

echo "--- [7] 采集链路活性（两次采样，间隔 20s）----"
act() { curl -s --max-time 8 "http://127.0.0.1:18084/actuator/metrics/$1" | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); m=d.get("measurements") or []
    print(int(m[0]["value"]) if m else "NA")
except Exception: print("NA")'; }
for m in iot.timeseries.write.rows iot.timeseries.points.collected iot.timeseries.db.rows; do
  v1=$(act "$m"); sleep 20; v2=$(act "$m")
  printf '  %-36s %s -> %s  (delta=%s)\n' "$m" "$v1" "$v2" "$((v2 - v1))"
done
for m in iot.timeseries.write.failed iot.timeseries.db.probe.failed; do
  printf '  %-36s %s\n' "$m" "$(act "$m")"
done
echo

echo "--- [8] free / df ----"
free -m | sed 's/^/  /'
df -h / | sed 's/^/  /'
echo

echo "--- [9] 内核累计 OOM 次数 ----"
printf '  Out of memory: Killed = %s\n' "$(dmesg 2>/dev/null | grep -c 'Out of memory: Killed')"
echo "==================== accept.sh END ===================="
