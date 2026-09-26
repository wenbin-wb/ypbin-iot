#!/bin/bash
# ============================================================
# IoTDB 一次性初始化执行器（由 deploy/docker-compose.yml 的 iotdb-init 服务调用）
#
# 为什么单独放一个脚本而不是把 shell 写进 compose 的 entrypoint：
#   compose 会对 entrypoint/command 里的 `$` 做变量插值（要写 `$$` 才当字面量），
#   shell 逻辑一多就极易踩坑；独立脚本还能直接 `bash -n` 静态检查。
#
# 职责：等 IoTDB 的 RPC 口可用 → 逐行执行 /init/iotdb-init.sql 里的语句 → 回读元数据
# 退出码：0 = 全部语句执行成功**且**元数据回读成功；非 0 = 失败（重试到上限后**显式**退出非 0，不静默通过）
#
# 环境前提：运行在官方 apache/iotdb 镜像内 —— PATH 已含 /iotdb/sbin 与 /iotdb/tools，
#           start-cli.sh 可直接调用（官方 Dockerfile-1.0.0-standalone，2026-09-24 核实）。
# ⚠️ 口令是本项目的**第三处**（另两处见 deploy/nacos/ypbin-iot.yaml 与 IoTDB 内的 ALTER USER）：
#    compose 通过 IOTDB_USER / IOTDB_PASSWORD（来自 deploy/.env）注入，
#    改口令时三处必须一起改，否则这里会连续 801。
# ⚠️ 口令**不进 argv**（2026-09-26 起）：`-pw <值>` 会把明文口令写进进程 args，
#    被 `docker top` / `/proc/<pid>/cmdline` / `docker events` 看到。start-cli.sh 的 `-pw`
#    在**后面不跟值**时会交互式提示口令，而这个提示读的是 **stdin**（一手实测，2026-09-26）：
#    口令经管道送入 ⇒ 进程 args 里只剩一个裸 `-pw`。
#    同时**仍然带 `-e`**（批处理模式）：它的退出码语义正是本脚本依赖的
#    （实测：口令错 ⇒ 1、SQL 失败 ⇒ 1；交互模式则无论语句成败都退 0，会毁掉本脚本的失败判定）。
# ============================================================

set -u

SQL_FILE="${SQL_FILE:-/init/iotdb-init.sql}"
IOTDB_HOST="${IOTDB_HOST:-iotdb}"
IOTDB_PORT="${IOTDB_PORT:-6667}"
IOTDB_USER="${IOTDB_USER:-root}"
IOTDB_PASSWORD="${IOTDB_PASSWORD:-}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-30}"
RETRY_INTERVAL_SECONDS="${RETRY_INTERVAL_SECONDS:-6}"

# ⚠️ 口令**没有默认值**：这里以前是 `:-root`（官方公开默认口令）⇒「口令没配上」会以
#    「静默用公开默认口令」而不是「显式报错」呈现。现在缺失即 fail-fast（退出码 1 + 明确指引）。
# ⚠️ 为什么**不**在 compose 用 `${IOTDB_PASSWORD:?}` 做同一件事（2026-09-26 复核指出）：
#    compose 对**整份文件**做变量插值 ⇒ 该键缺失时同目录下**任何** compose 命令都失败
#    （连只读的 `docker compose ps`，以及 install.sh 里更早的 `up -d nacos redis mysql` 都被拦），
#    波及面远大于本服务。故把 fail-fast 放在**真正需要口令的地方**（本脚本）。
if [ -z "$IOTDB_PASSWORD" ]; then
  echo "[iotdb-init] 错误：未提供 IOTDB_PASSWORD（拒绝回退到官方公开默认口令 'root'）"
  echo "[iotdb-init] 请在 deploy/.env 设置 IOTDB_PASSWORD = IoTDB 当前生效口令；见 docs/DEPLOY-TIMESERIES.md §6"
  exit 1
fi

# ⚠️ 挂载缺失会让下面的 while 循环一次都不执行、然后"看起来成功"——先显式拦掉
if [ ! -s "$SQL_FILE" ]; then
  echo "[iotdb-init] 错误：$SQL_FILE 缺失或为空（检查 compose volumes 挂载）"
  exit 1
fi

# 单条语句执行：官方 CLI 的批处理模式（-e）；口令经 stdin 送入（裸 `-pw` 触发交互式提示）
# 管道的退出码 = start-cli.sh 的退出码（未开 pipefail，printf 的 SIGPIPE 不影响判定）
run_statement() {
  printf '%s\n' "$IOTDB_PASSWORD" \
    | start-cli.sh -h "$IOTDB_HOST" -p "$IOTDB_PORT" -u "$IOTDB_USER" -pw \
        -sql_dialect table -e "${1%;}"
}

# 先数一遍可执行语句：文件存在但"全是空行/注释"时主循环会零次执行并顺利退出 0 —— 同样是静默通过。
# `|| [ -n "$stmt" ]` 是必需的：read 会丢弃末尾没有换行符的最后一行。
statement_count=0
while IFS= read -r stmt || [ -n "$stmt" ]; do
  case "$stmt" in '' | \#* | --*) continue ;; esac
  statement_count=$((statement_count + 1))
done < "$SQL_FILE"
if [ "$statement_count" -eq 0 ]; then
  echo "[iotdb-init] 错误：$SQL_FILE 里没有可执行语句（只有空行/注释？）——拒绝以退出码 0 通过"
  exit 1
fi
echo "[iotdb-init] 待执行语句数：$statement_count"

attempt=1
while :; do
  echo "[iotdb-init] 第 $attempt/$MAX_ATTEMPTS 次尝试：对 $IOTDB_HOST:$IOTDB_PORT 执行 $SQL_FILE"
  all_ok=1
  # 按行读取：一行一条语句；空行与行首 # / -- 为注释
  while IFS= read -r stmt || [ -n "$stmt" ]; do
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

echo "[iotdb-init] DDL 全部执行完毕。回读元数据："
if ! run_statement "SHOW TABLES FROM iot"; then
  # 回读失败也必须是失败：否则"DDL 看着成功、其实库/表没建对"会被退出码 0 掩盖
  echo "[iotdb-init] 元数据回读失败（SHOW TABLES FROM iot 非 0 退出）"
  exit 1
fi
echo "[iotdb-init] 完成（本次退出码 0 只代表 CLI 未报失败；请按 docs/DEPLOY-TIMESERIES.md §3 做一次人工验证）"
