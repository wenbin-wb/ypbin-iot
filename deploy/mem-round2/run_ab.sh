#!/usr/bin/env bash
# run_ab.sh —— 等 Leg A 结束 → 加 MALLOC_ARENA_MAX=2 → 重建 ypbin-iot → 跑 Leg B
# 全程把关键断言写入 stdout（由调用方重定向到 run_ab.log）
set -uo pipefail
D=/root/mem-round2-20260926
cd /opt/ypbin/ypbin-iot/deploy || exit 1
say() { echo "[run_ab $(date -u +%FT%TZ)] $*"; }

# ── 1. 等 A 腿结束 ────────────────────────────────────────────────────────────
say "等待 Leg A 结束…"
while ! grep -q "DONE" "$D/legA.tsv" 2>/dev/null; do sleep 15; done
say "Leg A 已结束"

# ── 2. A 腿延迟旁证 ──────────────────────────────────────────────────────────
"$D/lat.sh" http://127.0.0.1:18084/actuator/health 30 > "$D/legA-lat-18084.txt" 2>&1
"$D/lat.sh" http://127.0.0.1:18080/ 30             > "$D/legA-lat-18080.txt" 2>&1
"$D/lat.sh" http://127.0.0.1:19000/ 30             > "$D/legA-lat-19000.txt" 2>&1
say "A 腿延迟旁证完成"

# ── 3. 加 MALLOC_ARENA_MAX=2（先备份）────────────────────────────────────────
cp -a docker-compose.override.yml "$D/docker-compose.override.yml.bak-before-B"
md5sum "$D/docker-compose.override.yml.bak-before-B"
python3 "$D/add_arena.py" || { say "add_arena.py 失败，中止"; exit 1; }

# ── 4. 落盘断言（不信"我刚写了"，读回文件 + compose 合并结果）──────────────
say "--- 读回 override 中的 ypbin-iot 块 ---"
awk '/^  ypbin-iot:/{f=1} f&&/^  [a-z]/&&!/^  ypbin-iot:/{f=0} f' docker-compose.override.yml
if ! grep -q 'MALLOC_ARENA_MAX: "2"' docker-compose.override.yml; then
  say "断言失败：override 里没有 MALLOC_ARENA_MAX"; exit 1
fi
say "--- compose 合并后的 env（只取 iot 的 MALLOC_ARENA_MAX / JAVA_OPTS）---"
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml config --format json \
  | python3 -c '
import json,sys
s=json.load(sys.stdin)["services"]["ypbin-iot"]
e=s.get("environment",{})
print("  MALLOC_ARENA_MAX =", repr(e.get("MALLOC_ARENA_MAX")))
print("  JAVA_OPTS        =", repr(e.get("JAVA_OPTS")))
print("  mem_limit        =", s.get("mem_limit"))
assert e.get("MALLOC_ARENA_MAX") == "2", "compose 合并结果里 MALLOC_ARENA_MAX != 2"
' || { say "compose config 断言失败"; exit 1; }

# ── 5. 只重建 ypbin-iot（--no-deps）─────────────────────────────────────────
say "--- 重建 ypbin-iot（B 腿）---"
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml \
  up -d --no-build --no-deps --force-recreate ypbin-iot 2>&1

# ── 6. 运行期断言 ───────────────────────────────────────────────────────────
ENVS=$(docker inspect ypbin-iot --format '{{range .Config.Env}}{{println .}}{{end}}')
AV=$(printf '%s\n' "$ENVS" | grep '^MALLOC_ARENA_MAX=' || true)
say "运行期 $AV"
if [ "$AV" != "MALLOC_ARENA_MAX=2" ]; then say "断言失败：运行期 MALLOC_ARENA_MAX 不是 2"; exit 1; fi
IMGB=$(docker inspect -f '{{.Image}}' ypbin-iot)
say "B 腿 imageID=$IMGB"
{
  echo "LegB 容器: id=$(docker inspect -f '{{.Id}}' ypbin-iot) created=$(docker inspect -f '{{.Created}}' ypbin-iot)"
  echo "LegB imageID=$IMGB"
  echo "LegB $AV"
} >> "$D/artifact-triple.txt"

# ── 7. B 腿采样（与 A 完全同参数）──────────────────────────────────────────
say "启动 Leg B 采样（warmup 300s + 600s @60s）"
"$D/ab_leg.sh" B 300 600 60 "$D/legB.tsv"

# ── 8. B 腿延迟旁证（与 A 同一相对时点：采样结束后）────────────────────────
"$D/lat.sh" http://127.0.0.1:18084/actuator/health 30 > "$D/legB-lat-18084.txt" 2>&1
"$D/lat.sh" http://127.0.0.1:18080/ 30             > "$D/legB-lat-18080.txt" 2>&1
"$D/lat.sh" http://127.0.0.1:19000/ 30             > "$D/legB-lat-19000.txt" 2>&1
say "ALL DONE"
