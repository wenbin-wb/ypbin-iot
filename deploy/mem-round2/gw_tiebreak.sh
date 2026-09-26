#!/usr/bin/env bash
# gw_tiebreak.sh —— gateway 的 p95 定论实验：arena2（当前） vs noenv（回退后），各 n=600
#
# 背景：pair2 的 T+600s 显示 gateway p95 5.4ms（基线）→ 7.4ms（arena2），+2.0ms 正好落在本机
#       已证实的 run-to-run 噪声带边缘（§2.4.1）。按"p95 恶化就不改"的规则必须给出定论，
#       所以做一次**同配置对称 n=600** 的回退对照（样本量比 pair 的 n=200 大 3 倍）。
# 结束态：由作者看结果决定是否重新加回（本脚本只负责把两态都测出来并停在 noenv）。
set -uo pipefail
D=/root/mem-round2-20260926
DEP=/opt/ypbin/ypbin-iot/deploy
OUT=$D/gw-tiebreak.txt
cd "$DEP" || exit 1

say() { echo "[gw_tiebreak $(date -u +%FT%TZ)] $*"; }
{
  echo "##### gateway p95 定论实验 开始 $(date -u +%FT%TZ)"
  echo "### 阶段 1：arena2（当前状态，容器 $(docker inspect -f '{{.Id}}' ypbin-gateway | cut -c1-12)）"
} >> "$OUT"

for i in 1 2 3; do
  echo -n "  arena2 lat[$i]: " >> "$OUT"; "$D/lat.sh" http://127.0.0.1:18080/ 600 >> "$OUT" 2>&1
done
jp=$(p1=$(docker inspect -f '{{.State.Pid}}' ypbin-gateway); cat "/proc/$p1/task/$p1/children")
echo -n "  arena2 smaps: " >> "$OUT"; python3 "$D/anonmap.py" "$jp" 1024 >> "$OUT" 2>&1

say "阶段 1 完成，开始回退 gateway"
python3 "$D/remove_arena_svc.py" ypbin-gateway >> "$OUT" 2>&1
grep -q 'MALLOC_ARENA_MAX' <(awk '/^  ypbin-gateway:/{f=1} f&&/^  [a-z]/&&!/^  ypbin-gateway:/{f=0} f' docker-compose.override.yml) \
  && { say "回退断言失败：gateway 块仍有 MALLOC_ARENA_MAX"; exit 1; }
echo "  回退断言 OK：gateway 块已无该键" >> "$OUT"
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml config -q >> "$OUT" 2>&1
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml \
  up -d --no-build --no-deps --force-recreate ypbin-gateway >> "$OUT" 2>&1
echo -n "  运行期 arena: " >> "$OUT"
docker inspect ypbin-gateway --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^MALLOC_ARENA_MAX=' >> "$OUT" 2>&1 || echo "MALLOC_ARENA_MAX=UNSET" >> "$OUT"

say "warm-up 300s…"; sleep 300
{
  echo "### 阶段 2：noenv（回退后，容器 $(docker inspect -f '{{.Id}}' ypbin-gateway | cut -c1-12)）"
} >> "$OUT"
for i in 1 2 3; do
  echo -n "  noenv lat[$i]: " >> "$OUT"; "$D/lat.sh" http://127.0.0.1:18080/ 600 >> "$OUT" 2>&1
done
jp=$(p1=$(docker inspect -f '{{.State.Pid}}' ypbin-gateway); cat "/proc/$p1/task/$p1/children")
echo -n "  noenv smaps: " >> "$OUT"; python3 "$D/anonmap.py" "$jp" 1024 >> "$OUT" 2>&1
echo "##### 结束 $(date -u +%FT%TZ)（当前状态 = noenv，等作者决定是否加回）" >> "$OUT"
say "ALL DONE"
