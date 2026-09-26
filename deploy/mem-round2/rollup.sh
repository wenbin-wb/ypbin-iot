#!/usr/bin/env bash
# rollup.sh —— 在「相对该腿 t0 的固定偏移」处采样 smaps_rollup，作为 Pss_Anon 的**同时点**旁证
# 用法: rollup.sh <leg> <该腿的 tsv> <偏移秒> <输出文件>
set -uo pipefail
LEG="$1"; TSV="$2"; TGT="$3"; OUT="$4"
t0=$(grep -oE 't0=[0-9TZ:.-]+' "$TSV" | head -1 | cut -d= -f2)
t0e=$(date -u -d "$t0" +%s)
nowe=$(date -u +%s)
slp=$(( t0e + TGT - nowe ))
[ "$slp" -gt 0 ] && sleep "$slp"
{
  echo "### leg=$LEG elapsed_target=${TGT}s sample_at=$(date -u +%FT%TZ) t0=$t0"
  for _ in 1 2 3 4 5; do
    p1=$(docker inspect -f '{{.State.Pid}}' ypbin-iot 2>/dev/null)
    jp=$(cat "/proc/$p1/task/$p1/children" 2>/dev/null | awk '{print $1}')
    if [ -n "${jp:-}" ] && [ -r "/proc/$jp/smaps_rollup" ]; then
      printf 't=%s pid=%s %s\n' "$(date -u +%FT%TZ)" "$jp" \
        "$(grep -E '^(Rss|Pss|Pss_Anon|Pss_File|Pss_Shmem|Anonymous|AnonHugePages):' /proc/$jp/smaps_rollup | tr '\n' ' ')"
    else
      printf 't=%s PID_RESOLVE_FAIL\n' "$(date -u +%FT%TZ)"
    fi
    sleep 10
  done
} >> "$OUT"
