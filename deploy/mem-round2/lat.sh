#!/usr/bin/env bash
# lat.sh —— 端点延迟分位（p50/p95/max），用于 A/B 的副作用旁证
# 用法: lat.sh <url> <次数>
set -uo pipefail
URL="$1"; N="${2:-30}"
for _ in $(seq 1 "$N"); do
  curl -s -o /dev/null -w '%{time_total}\n' --max-time 10 "$URL" 2>/dev/null || echo 10
done | sort -n | awk -v u="$URL" '
{ a[NR]=$1 }
END{
  if (NR==0) { print u" n=0"; exit }
  p50=a[int(NR*0.50)]; p95=a[int(NR*0.95)]; if (p95=="") p95=a[NR]; if (p50=="") p50=a[1]
  printf "%s n=%d p50=%.1fms p95=%.1fms max=%.1fms\n", u, NR, p50*1000, p95*1000, a[NR]*1000
}'
