#!/usr/bin/env bash
# ============================================================
# ypbin-iot 数据备份：MySQL（逻辑全量）+ IoTDB（数据目录热拷贝）
#
# 用法：
#   deploy/backup.sh              # 备份一次，落 $ROOT/backups
#   deploy/backup.sh --dry-run    # 只打印将要做什么，不碰 docker、不落盘
#   BACKUP_DIR=/data/backups BACKUP_KEEP=7 deploy/backup.sh
#
# 可覆盖的环境变量：
#   YPBIN_ROOT         部署根（默认：本脚本的上一级目录）
#   BACKUP_DIR         输出目录（默认 $YPBIN_ROOT/backups）
#   BACKUP_KEEP        保留最近 N 份（默认 14；<=0 表示不轮转）
#   ENV_FILE           含 MYSQL_ROOT_PASSWORD 的 .env（默认 $YPBIN_ROOT/deploy/.env，要求 600）
#   MYSQL_CONTAINER    MySQL 容器名（默认 ypbin-mysql）
#   IOTDB_CONTAINER    IoTDB 容器名（默认 ypbin-iotdb）
#
# 产物（每次 2 份 + 各自 .sha256，文件名带时间戳）：
#   ypbin-admin-<TS>.sql      mysqldump --single-transaction（含 routines/triggers/events）
#   iotdb-data-<TS>.tar       docker cp 出来的 /iotdb/data 目录 tar
#   *.sha256                  sha256sum 校验值（restore.sh 恢复前会核对）
#
# ⚠️ 边界与局限（如实登记，别当没有）：
#   1) MySQL 侧：`--single-transaction` 只对 InnoDB 提供「事务一致快照」，**不锁表**；
#      但 DDL（如备份期间有人 ALTER）不参与事务 ⇒ 逻辑备份对并发 DDL 下的一致性无保证。
#      另外 `mysqldump` 是逻辑备份，恢复是「重放 SQL」，大库恢复慢，且不覆盖 binlog/GTID 位点。
#   2) IoTDB 侧：`docker cp ypbin-iotdb:/iotdb/data -` 是**运行中热拷贝**——不保证崩溃一致性
#      （正在写的文件可能处于中间态）。它是「整卷粗粒度副本」，**不支持增量**，也不等同于官方
#      备份工具（IoTDB Tools System）。可接受的做法：备份窗口内尽量无重负载；
#      要强一致请停 ypbin-iotdb 后再跑（代价是停机），恢复见 restore.sh。
#   3) 恢复演练：见 deploy/PROD-OPS-NOTES.md「数据备份/恢复」一节；**未在真机验证**的部分已登记。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${YPBIN_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ypbin-mysql}"
IOTDB_CONTAINER="${IOTDB_CONTAINER:-ypbin-iotdb}"

info()  { printf '\033[36m==> %s\033[0m\n' "$*"; }
ok()    { printf '\033[32m✓  %s\033[0m\n' "$*"; }
warn()  { printf '\033[33m!  %s\033[0m\n' "$*"; }
die()   { printf '\033[31m✗  %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; }

DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage; exit 2 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
DB_FILE="$BACKUP_DIR/ypbin-admin-$TS.sql"
IOTDB_FILE="$BACKUP_DIR/iotdb-data-$TS.tar"

if [ "$DRY_RUN" = "1" ]; then
  info "[dry-run] 计划（不执行、不落盘）"
  echo "  ROOT=$ROOT"
  echo "  BACKUP_DIR=$BACKUP_DIR（保留最近 $BACKUP_KEEP 份）"
  echo "  ENV_FILE=$ENV_FILE（只读 MYSQL_ROOT_PASSWORD，600）"
  echo "  1) MySQL: MYSQL_PWD=<从 .env 读，经 env 转发> docker exec -e MYSQL_PWD $MYSQL_CONTAINER \\"
  echo "             sh -c 'mysqldump --single-transaction --routines --triggers --events --databases ypbin_admin [xxl_job]' > $DB_FILE"
  echo "  2) IoTDB: docker cp $IOTDB_CONTAINER:/iotdb/data - > $IOTDB_FILE   # 热拷贝，边界见脚本头"
  echo "  3) sha256sum 生成 .sha256；4) 按时间戳轮转，删除第 $BACKUP_KEEP 份之后的分组"
  exit 0
fi

[ -f "$ENV_FILE" ] || die "找不到 ENV_FILE=$ENV_FILE（含 MYSQL_ROOT_PASSWORD）。用 ENV_FILE=... 指定，或先跑 deploy/install.sh"
MYSQL_ROOT_PASSWORD="$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' "$ENV_FILE" | head -1)"
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
  die "$ENV_FILE 里没有非空的 MYSQL_ROOT_PASSWORD —— 拒绝以空口令连接 MySQL"
fi
case "$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '?')" in
  600|400|*00) : ;;  # 只要不是 group/other 可读就行
  *) warn "$ENV_FILE 权限是 $(stat -c '%a' "$ENV_FILE" 2>/dev/null)，建议 chmod 600（含 MySQL root 口令）" ;;
esac

command -v docker >/dev/null 2>&1 || die "找不到 docker"
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -qx true \
  || die "MySQL 容器 $MYSQL_CONTAINER 不在运行 —— 备份中止（不静默跳过）"
docker inspect -f '{{.State.Running}}' "$IOTDB_CONTAINER" 2>/dev/null | grep -qx true \
  || die "IoTDB 容器 $IOTDB_CONTAINER 不在运行 —— 备份中止（不静默跳过）"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true
# 默认 BACKUP_DIR 在仓库内（$ROOT/backups）时避免污染 git 工作区：放一个自忽略文件。
# （不改根 .gitignore——它是 admin 既有文件，改它要进 Sync Whitelist。）
if [ ! -f "$BACKUP_DIR/.gitignore" ] && [ -e "$ROOT/.git" ]; then
  printf '*\n' > "$BACKUP_DIR/.gitignore"
fi

# ---------- MySQL ----------
info "备份 MySQL（--single-transaction --routines --triggers --events）"
DBS="ypbin_admin"
if MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD "$MYSQL_CONTAINER" \
     mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='xxl_job';" 2>/dev/null | grep -qx 1; then
  DBS="ypbin_admin xxl_job"
fi
# 口令只经 env 转发（-e MYSQL_PWD 只写变量名）；mysqldump 命令串静态，不含口令。
# shellcheck disable=SC2086
if ! MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD "$MYSQL_CONTAINER" \
      sh -c "mysqldump --default-character-set=utf8mb4 --single-transaction --routines --triggers --events --databases $DBS" > "$DB_FILE"; then
  rm -f "$DB_FILE"
  die "mysqldump 失败（MySQL 备份中止）"
fi
[ -s "$DB_FILE" ] || { rm -f "$DB_FILE"; die "mysqldump 输出为空（$DB_FILE）——拒绝产出空备份"; }
ok "MySQL 已备份：$DB_FILE（$(du -h "$DB_FILE" | cut -f1)，库：$DBS）"

# ---------- IoTDB ----------
info "备份 IoTDB 数据目录（docker cp 热拷贝；一致性边界见脚本头）"
if ! docker cp "$IOTDB_CONTAINER:/iotdb/data" - > "$IOTDB_FILE"; then
  rm -f "$IOTDB_FILE"
  die "docker cp $IOTDB_CONTAINER:/iotdb/data 失败（IoTDB 备份中止）"
fi
[ -s "$IOTDB_FILE" ] || { rm -f "$IOTDB_FILE"; die "IoTDB 备份为空（$IOTDB_FILE）——拒绝产出空备份"; }
ok "IoTDB 已备份：$IOTDB_FILE（$(du -h "$IOTDB_FILE" | cut -f1)）"

# ---------- 校验值 ----------
for f in "$DB_FILE" "$IOTDB_FILE"; do
  ( cd "$(dirname "$f")" && sha256sum "$(basename "$f")" > "$(basename "$f").sha256" )
  ok "已生成 $(basename "$f").sha256：$(cut -d' ' -f1 "$f.sha256")"
done

# ---------- 轮转 ----------
if [ "${BACKUP_KEEP:-0}" -gt 0 ] 2>/dev/null; then
  mapfile -t KEEP_TS < <(ls -1t "$BACKUP_DIR"/ypbin-admin-*.sql 2>/dev/null \
    | sed -E 's#.*/ypbin-admin-([0-9]{8}-[0-9]{6})\.sql$#\1#' \
    | grep -E '^[0-9]{8}-[0-9]{6}$' | head -n "$BACKUP_KEEP" || true)
  if [ "${#KEEP_TS[@]}" -gt 0 ]; then
    KEEP_RE="$(printf '%s\n' "${KEEP_TS[@]}" | paste -sd'|' -)"
    # 删除所有时间戳不在保留集合里的备份组（sql/tar/sha256 一并删）
    removed=0
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      base="$(basename "$f")"
      ts="$(printf '%s' "$base" | sed -E 's/^(ypbin-admin|iotdb-data)-([0-9]{8}-[0-9]{6})\..*$/\2/')"
      if ! printf '%s' "$ts" | grep -qE "^($KEEP_RE)$"; then
        rm -f "$f" "$f.sha256"; removed=$((removed + 1))
      fi
    done < <(ls -1 "$BACKUP_DIR"/ypbin-admin-*.sql "$BACKUP_DIR"/iotdb-data-*.tar "$BACKUP_DIR"/ypbin-admin-*.sql.sha256 "$BACKUP_DIR"/iotdb-data-*.tar.sha256 2>/dev/null || true)
    [ "$removed" -gt 0 ] && ok "轮转：保留最近 $BACKUP_KEEP 份，删除 $removed 个过期文件"
  fi
fi

info "备份完成（BACKUP_DIR=$BACKUP_DIR）"
ls -lh "$BACKUP_DIR"/ypbin-admin-*.sql "$BACKUP_DIR"/iotdb-data-*.tar 2>/dev/null | sed 's/^/    /' || true
