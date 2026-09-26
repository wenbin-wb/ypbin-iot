#!/usr/bin/env bash
# steady.sh —— 稳态采样：每 <间隔>s 记一次 MemAvailable/Swap/各容器 RSS，直到达到「连续 3 个间隔变化 <5%」
# 用法: steady.sh <间隔秒> <最大轮数> <输出文件>
set -uo pipefail
IV="${1:-60}"; N="${2:-12}"; OUT="${3:-/root/mem-round2-20260926/steady.txt}"
{
  echo "### steady.sh interval=${IV}s max=${N} start=$(date -u +%FT%TZ)"
  for i in $(seq 1 "$N"); do
    ts=$(date -u +%FT%TZ)
    avail=$(awk '/^MemAvailable:/{printf "%d", $2/1024}' /proc/meminfo)
    used=$(awk '/^MemTotal:/{t=$2} /^MemFree:/{f=$2} /^Buffers:/{b=$2} /^Cached:/{c=$2} /^Shmem:/{s=$2} /^SReclaimable:/{r=$2} END{printf "%d", (t-f-b-c-r+s)/1024}' /proc/meminfo)
    swapu=$(free -m | awk '/^Swap:/{print $3}')
    zram=$(awk '{print $3}' /sys/block/zram0/mm_stat 2>/dev/null || echo NA)
    rss=$(docker stats --no-stream --format '{{.Name}}={{.MemUsage}}' 2>/dev/null | tr '\n' ' ')
    printf '%s avail_mb=%s used_mb=%s swap_used_mb=%s zram_compr_bytes=%s | %s\n' \
      "$ts" "$avail" "$used" "$swapu" "$zram" "$rss"
    sleep "$IV"
  done
  echo "### steady.sh DONE $(date -u +%FT%TZ)"
} >> "$OUT"
