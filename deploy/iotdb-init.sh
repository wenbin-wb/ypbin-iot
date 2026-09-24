#!/bin/bash
# ============================================================
# IoTDB 一次性初始化执行器（由 deploy/docker-compose.yml 的 iotdb-init 服务调用）
#
# 为什么单独放一个脚本而不是把 shell 写进 compose 的 entrypoint：
#   compose 会对 entrypoint/command 里的 `$` 做变量插值（要写 `$$` 才当字面量），
#   shell 逻辑一多就极易踩坑；独立脚本还能直接 `bash -n` 静态检查。
#
# 职责：等 IoTDB 的 RPC 口可用 → 逐行执行 /init/iotdb-init.sql 里的语句 → 回读元数据
# 退出码：0 = 全部语句执行成功；非 0 = 失败（失败会重试到上限后**显式**退出非 0，不静默通过）
#
# 环境前提：运行在官方 apache/iotdb 镜像内 —— PATH 已含 /iotdb/sbin 与 /iotdb/tools，
#           start-cli.sh 可直接调用（官方 Dockerfile-1.0.0-standalone，2026-09-24 核实）。
# ============================================================

set -u

SQL_FILE="${SQL_FILE:-/init/iotdb-init.sql}"
IOTDB_HOST="${IOTDB_HOST:-iotdb}"
IOTDB_PORT="${IOTDB_PORT:-6667}"
IOTDB_USER="${IOTDB_USER:-root}"
IOTDB_PASSWORD="${IOTDB_PASSWORD:-root}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-30}"
RETRY_INTERVAL_SECONDS="${RETRY_INTERVAL_SECONDS:-6}"

# ⚠️ 挂载缺失会让下面的 while 循环一次都不执行、然后"看起来成功"——先显式拦掉
if [ ! -s "$SQL_FILE" ]; then
  echo "[iotdb-init] 错误：$SQL_FILE 缺失或为空（检查 compose volumes 挂载）"
  exit 1
fi

# 单条语句执行：用官方 CLI 的非交互批处理模式（-e，官方 CLI 文档）
run_statement() {
  start-cli.sh -h "$IOTDB_HOST" -p "$IOTDB_PORT" -u "$IOTDB_USER" -pw "$IOTDB_PASSWORD" \
    -sql_dialect table -e "${1%;}"
}

attempt=1
while :; do
  echo "[iotdb-init] 第 $attempt/$MAX_ATTEMPTS 次尝试：对 $IOTDB_HOST:$IOTDB_PORT 执行 $SQL_FILE"
  all_ok=1
  # 按行读取：一行一条语句；空行与行首 # / -- 为注释
  while IFS= read -r stmt; do
    case "$stmt" in '' | \#* | --*) continue ;; esac
    echo "[iotdb-init] -> $stmt"
    if ! run_statement "$stmt"; then
      echo "[iotdb-init] 语句执行失败，稍后整体重试"
      all_ok=0
      break
    fi
  done < "$SQL_FILE"

  if [ "$all_ok" -eq 1 ]; then
    break
  fi
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "[iotdb-init] 初始化失败：已重试 $MAX_ATTEMPTS 次仍未成功（按上面的 CLI 输出定位原因）"
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep "$RETRY_INTERVAL_SECONDS"
done

echo "[iotdb-init] DDL 全部执行完毕。回读元数据供人工核对："
run_statement "SHOW TABLES FROM iot"
echo "[iotdb-init] 完成（本次退出码 0 只代表 CLI 未报失败；请按 docs/DEPLOY-TIMESERIES.md §3 做一次人工验证）"
