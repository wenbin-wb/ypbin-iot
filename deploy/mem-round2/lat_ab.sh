#!/usr/bin/env bash
# lat_ab.sh —— **延迟专项 A/B（对称 n=300，产物落盘）**
# 目的：补上报告 §2.4 中"B 侧 n=300 无一手产物、且 A/B 样本量不对称"的证据缺口。
#
# 设计：只改 MALLOC_ARENA_MAX 这一个键，两腿**同历时**（重建 → warm-up 300s → 每端点 300 次请求）。
#   Leg C(noenv)  : 从 docker-compose.override.yml.bak-before-B 恢复（无 MALLOC_ARENA_MAX）
#   Leg D(arena2) : 再用 add_arena.py 加回 MALLOC_ARENA_MAX=2
# 结束态 = Leg D = 与报告一致的最终生产状态。
set -uo pipefail
D=/root/mem-round2-20260926
DEP=/opt/ypbin/ypbin-iot/deploy
OUT="$D/lat-ab.txt"
C=ypbin-iot
cd "$DEP" || exit 1

say() { echo "[lat_ab $(date -u +%FT%TZ)] $*"; }

record_identity() { # $1 = leg
  {
    echo "### leg=$1 recreate_at=$(date -u +%FT%TZ)"
    echo -n "container: "; docker inspect "$C" --format 'id={{.Id}} created={{.Created}} image={{.Image}} mem={{.HostConfig.Memory}}'
    echo -n "arena: "; docker inspect "$C" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^MALLOC_ARENA_MAX=' || echo "MALLOC_ARENA_MAX=UNSET"
    echo -n "javaopts: "; docker inspect "$C" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^JAVA_OPTS='
  } >> "$OUT"
}

recreate() {
  docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml \
    up -d --no-build --no-deps --force-recreate "$C" >> "$OUT" 2>&1
}

run_leg() { # $1 = leg 标签
  local leg="$1"
  say "=== $leg 重建 ==="
  record_identity "$leg"
  say "$leg warm-up 300s…"
  sleep 300
  say "$leg 开始延迟采样（每端点 300 次）"
  for u in "http://127.0.0.1:18084/actuator/health" "http://127.0.0.1:18080/" "http://127.0.0.1:19000/"; do
    "$D/lat.sh" "$u" 300 >> "$OUT" 2>&1
  done
  # 同点旁证：非堆匿名映射（确认这一腿确实处于期望的 arena 状态）
  p1=$(docker inspect -f '{{.State.Pid}}' "$C")
  jp=$(cat "/proc/$p1/task/$p1/children" 2>/dev/null | awk '{print $1}')
  echo -n "smaps: " >> "$OUT"
  python3 "$D/anonmap.py" "$jp" 1024 >> "$OUT" 2>&1
  say "$leg 完成"
}

{
  echo "##### lat_ab.sh 开始 $(date -u +%FT%TZ)（对称 n=300 延迟 A/B）"
} >> "$OUT"

# ── Leg C：无 MALLOC_ARENA_MAX ────────────────────────────────────────────────
cp -a "$D/docker-compose.override.yml.bak-before-B" "$DEP/docker-compose.override.yml"
if grep -q 'MALLOC_ARENA_MAX' "$DEP/docker-compose.override.yml"; then
  say "断言失败：Leg C 的 override 里仍有 MALLOC_ARENA_MAX"; exit 1
fi
echo "### Leg C override 已还原为 bak-before-B（无 MALLOC_ARENA_MAX），md5=$(md5sum "$DEP/docker-compose.override.yml" | awk '{print $1}')" >> "$OUT"
recreate
run_leg "C-noenv"

# ── Leg D：MALLOC_ARENA_MAX=2 ────────────────────────────────────────────────
python3 "$D/add_arena.py" >> "$OUT" 2>&1 || { say "add_arena.py 失败"; exit 1; }
if ! grep -q 'MALLOC_ARENA_MAX: "2"' "$DEP/docker-compose.override.yml"; then
  say "断言失败：Leg D 的 override 里没有 MALLOC_ARENA_MAX"; exit 1
fi
recreate
run_leg "D-arena2"

echo "##### lat_ab.sh ALL DONE $(date -u +%FT%TZ)（最终状态 = Leg D = MALLOC_ARENA_MAX=2）" >> "$OUT"
say "ALL DONE"
