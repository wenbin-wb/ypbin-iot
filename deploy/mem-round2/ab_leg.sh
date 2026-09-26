#!/usr/bin/env bash
# ab_leg.sh —— MALLOC_ARENA_MAX A/B 单腿采样（同历时：固定 warm-up + 固定采样时长/间隔）
#
# 用法: ab_leg.sh <leg标签> <warmup秒> <采样总秒> <采样间隔秒> <输出文件>
#
# 采样内容（每次一行，制表符分隔）：
#   leg / 时间戳 / 已运行秒数 / smaps 主判据(anonmap.py) / available / cgroup memory.current
#   / JVM 内部指标（nonheap committed、direct buffer、heap used、线程数）/ 写入计数器
set -uo pipefail
LEG="$1"; WARM="$2"; SAMP="$3"; IV="$4"; OUT="$5"
DIR=/root/mem-round2-20260926
C=ypbin-iot
ACT=http://127.0.0.1:18084/actuator/metrics

act() { # act <metric> [tag]
  local u="$ACT/$1"
  [ -n "${2:-}" ] && u="$u?tag=$2"
  curl -s --max-time 5 "$u" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); m=d.get("measurements") or []
    print(int(m[0]["value"]) if m else "NA")
except Exception:
    print("NA")'
}

resolve_pid() { # 容器 PID1 的子进程（= JVM），每次重新解析，防重启后 PID 变化
  local p1 c
  p1=$(docker inspect -f '{{.State.Pid}}' "$C" 2>/dev/null) || return 1
  c=$(cat "/proc/$p1/task/$p1/children" 2>/dev/null | awk '{print $1}')
  [ -n "$c" ] || return 1
  echo "$c"
}

{
  echo "### leg=$LEG warmup=${WARM}s sample=${SAMP}s interval=${IV}s t0=$(date -u +%FT%TZ)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    leg ts elapsed_s smaps avail_mb cg_mem_mb nonheap_committed_mb direct_mb heap_used_mb threads write_rows_failed write_rows
} >> "$OUT"

sleep "$WARM"
n=$(( SAMP / IV ))
for i in $(seq 1 "$n"); do
  ts=$(date -u +%FT%TZ)
  el=$(( WARM + i * IV ))
  jp=$(resolve_pid) || { echo "$LEG	$ts	$el	PID_RESOLVE_FAIL" >> "$OUT"; sleep "$IV"; continue; }
  smaps=$(python3 "$DIR/anonmap.py" "$jp" 1024)
  avail=$(awk '/^MemAvailable:/{printf "%d", $2/1024}' /proc/meminfo)
  cid=$(docker inspect -f '{{.Id}}' "$C")
  cur=$(cat "/sys/fs/cgroup/system.slice/docker-$cid.scope/memory.current" 2>/dev/null || echo 0)
  curmb=$(( cur / 1048576 ))
  nh=$(act jvm.memory.committed area:nonheap)
  d=$(act jvm.buffer.memory.used id:direct)
  hu=$(act jvm.memory.used area:heap)
  th=$(act jvm.threads.live)
  wf=$(act iot.timeseries.write.failed)
  wr=$(act iot.timeseries.write.rows)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$LEG" "$ts" "$el" "$smaps" "$avail" "$curmb" \
    "$((nh/1048576))" "$((d/1048576))" "$((hu/1048576))" "$th" "$wf" "$wr" >> "$OUT"
  sleep "$IV"
done
echo "### leg=$LEG DONE $(date -u +%FT%TZ)" >> "$OUT"
