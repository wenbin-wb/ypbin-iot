#!/usr/bin/env bash
# arena_measure.sh —— 单个服务的 MALLOC_ARENA_MAX 收敛性测量（只读；不改任何状态）
#
# 用法: arena_measure.sh <服务名> <探测端口> <探测路径> <阶段标签> <输出文件>
# 输出：容器身份 + 运行期 MALLOC_ARENA_MAX + smaps 三次采样（≥1MB 匿名映射数量/总大小）+ p95(n=200)
set -uo pipefail
SVC="$1"; PORT="$2"; PATHX="$3"; LABEL="$4"; OUT="$5"
D=/root/mem-round2-20260926

resolve_pid() { # 容器 PID1 的 java 子进程
  local p1
  p1=$(docker inspect -f '{{.State.Pid}}' "$SVC" 2>/dev/null) || return 1
  cat "/proc/$p1/task/$p1/children" 2>/dev/null | awk '{print $1}'
}

{
  echo "### [$LABEL] $SVC @ $(date -u +%FT%TZ)"
  echo -n "  identity: "; docker inspect "$SVC" --format 'id={{.Id}} created={{.Created}} image={{.Image}} mem={{.HostConfig.Memory}}'
  echo -n "  arena: "; docker inspect "$SVC" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^MALLOC_ARENA_MAX=' || echo "MALLOC_ARENA_MAX=UNSET"
  for i in 1 2 3; do
    jp=$(resolve_pid) || { echo "  smaps[$i]: PID_RESOLVE_FAIL"; sleep 20; continue; }
    echo -n "  smaps[$i]: "; python3 "$D/anonmap.py" "$jp" 1024
    sleep 20
  done
  echo -n "  latency: "; "$D/lat.sh" "http://127.0.0.1:${PORT}${PATHX}" 200
  echo -n "  avail_mb="; awk '/^MemAvailable:/{printf "%d\n", $2/1024}' /proc/meminfo
} >> "$OUT" 2>&1
