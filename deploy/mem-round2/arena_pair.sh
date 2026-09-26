#!/usr/bin/env bash
# arena_pair.sh —— 对 1~2 个服务落地 MALLOC_ARENA_MAX=2 并测量（供后台运行）
#
# 用法: arena_pair.sh <阶段标签> <svc1> <port1> <svc2> <port2> [<svc3> <port3> ...]
# 流程：加 env（落盘断言）→ 逐个 --no-deps --force-recreate（运行期断言）→ warm-up 300s
#       → 每个服务测 3×smaps + p95(n=200) → 再等 240s 复测一次（确认稳态）
set -uo pipefail
D=/root/mem-round2-20260926
DEP=/opt/ypbin/ypbin-iot/deploy
OUT=$D/arena-rollout.txt
LABEL="$1"; shift
cd "$DEP" || exit 1
say() { echo "[arena_pair $(date -u +%FT%TZ)] $*"; }

# ── 解析成 (svc,port) 对 ─────────────────────────────────────────────────────
svcs=(); ports=()
while [ $# -gt 0 ]; do svcs+=("$1"); ports+=("$2"); shift 2; done
echo "### [$LABEL] 目标服务: ${svcs[*]}  端口: ${ports[*]}  开始 $(date -u +%FT%TZ)" >> "$OUT"

# ── 1. 加 env 并落盘断言 ─────────────────────────────────────────────────────
say "加 MALLOC_ARENA_MAX=2：${svcs[*]}"
python3 "$D/add_arena_svc.py" "${svcs[@]}" | tee -a "$OUT"
for s in "${svcs[@]}"; do
  if ! awk -v svc="  $s:" '$0==svc{f=1;next} f&&/^  [a-z]/&&$0!=svc{f=0} f' docker-compose.override.yml | grep -q 'MALLOC_ARENA_MAX: "2"'; then
    say "断言失败：$s 的 override 块里没有 MALLOC_ARENA_MAX"; exit 1
  fi
done
say "override 落盘断言通过"

# compose 合并结果断言
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml config --format json \
  | python3 -c "
import json,sys
svcs='''${svcs[*]}'''.split()
c=json.load(sys.stdin)['services']
bad=[s for s in svcs if c[s].get('environment',{}).get('MALLOC_ARENA_MAX')!='2']
print('compose 合并断言:', 'PASS' if not bad else ('FAIL '+str(bad)))
sys.exit(1 if bad else 0)
" | tee -a "$OUT" || { say "compose 断言失败，中止"; exit 1; }

# ── 2. 逐个 --no-deps 重建 + 运行期断言 ──────────────────────────────────────
for i in "${!svcs[@]}"; do
  s="${svcs[$i]}"
  say "重建 $s（--no-deps --force-recreate）"
  docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml \
    up -d --no-build --no-deps --force-recreate "$s" >> "$OUT" 2>&1
  av=$(docker inspect "$s" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^MALLOC_ARENA_MAX=' || true)
  if [ "$av" != "MALLOC_ARENA_MAX=2" ]; then say "断言失败：$s 运行期 $av"; exit 1; fi
  echo "  运行期断言 OK：$s $av" >> "$OUT"
done

# ── 3. warm-up + 两轮测量 ───────────────────────────────────────────────────
say "warm-up 300s…"; sleep 300
for i in "${!svcs[@]}"; do
  "$D/arena_measure.sh" "${svcs[$i]}" "${ports[$i]}" / "$LABEL-T+300s" "$OUT"
done
say "再等 240s 复测（稳态确认）"; sleep 240
for i in "${!svcs[@]}"; do
  "$D/arena_measure.sh" "${svcs[$i]}" "${ports[$i]}" / "$LABEL-T+600s" "$OUT"
done
say "ALL DONE [$LABEL]"
echo "### [$LABEL] 完成 $(date -u +%FT%TZ)" >> "$OUT"
