#!/usr/bin/env bash
# =============================================================
# ypbin-iot 升级用数据库迁移运行器（登记表 + 顺序应用 + 校验和防重放）
#
# 用法：
#   deploy/migrate.sh --status                 # 只读：已应用/待应用/校验和不一致 + 计数
#   deploy/migrate.sh --dry-run                # 只打印将应用哪些，不写库
#   deploy/migrate.sh                          # 默认：按清单顺序应用全部「待应用」迁移
#   deploy/migrate.sh --baseline all --yes     # 存量库落地：把清单内迁移按「已应用」登记（不执行 DDL）
#   deploy/migrate.sh --baseline <file> --yes  # 只登记清单中到该文件为止（含）的迁移
#   deploy/migrate.sh --baseline all           # 未给 --yes：只打印将登记的内容，退出 0（dry）
#   deploy/migrate.sh --database mig_test --container mysql-test      # 临时库/临时容器
#   deploy/migrate.sh --host 127.0.0.1 --port 3306 --database mig_test  # 直连（需宿主 mysql 客户端）
#
# 参数：
#   --status | --dry-run | --baseline TARGET | (默认 apply)
#   --baseline TARGET    存量库基线：all 或清单内文件名（到该文件为止，含）；需 --yes 才写库
#   --yes                baseline 的二次确认；没有它只打印计划（dry）
#   --force              baseline 在登记表已有行时仍继续（默认拒绝，防误覆盖）
#   --skip-precheck      baseline 跳过只读前置自检
#   --database DB        目标库（默认 ypbin_admin）
#   --host HOST          直连 MySQL 主机（给了就走宿主 mysql 客户端；否则 docker exec 容器）
#   --port PORT          直连端口（默认 3306）
#   --container NAME     docker 模式下的 MySQL 容器（默认 ypbin-mysql）
#   --env-file FILE      含 MYSQL_ROOT_PASSWORD 的 .env（默认 <root>/deploy/.env，要求 600）
#   --list FILE          迁移清单（默认 <script_dir>/sql/migration/iot-migrations.list）
#   --migration-dir DIR  迁移目录（默认 <script_dir>/sql/migration）
#   -h | --help
#
# 纪律（与仓内 backup.sh/restore.sh 同）：
#   * MySQL 口令只从 deploy/.env 读，经 MYSQL_PWD 环境变量转发（docker exec -e MYSQL_PWD
#     只写变量名），**绝不进 argv**。
#   * set -euo pipefail，失败即停、不吞错。
#   * 校验和不一致 = 红：只报告 + 人工处置指引，**绝不自动重放已应用文件**。
#
# 登记表 iot_schema_migration（由 migration/000-iot-migration-ledger.sql 建立）：
#   filename 主键、checksum=sha256(文件)、applied_at、applied_by、success。
#   兼容旧账本 ypbin_schema_migrations（install.sh 早期版本）：旧账本有记录、新登记表尚无
#   success=1 记录的迁移按「已应用」处理，并在 apply 模式补登进新表（不执行 DDL）。
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="${YPBIN_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"
MIGRATION_DIR="${MIGRATION_DIR:-$SCRIPT_DIR/sql/migration}"
LIST_FILE="${LIST_FILE:-$MIGRATION_DIR/iot-migrations.list}"
DB="ypbin_admin"
HOST=""
PORT="3306"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ypbin-mysql}"
MODE="apply"
BASELINE_TARGET=""
BASELINE_YES=0
BASELINE_FORCE=0
SKIP_PRECHECK=0
LEDGER_NAME="000-iot-migration-ledger.sql"
LEDGER_TABLE="iot_schema_migration"
LEGACY_TABLE="ypbin_schema_migrations"
APPLIED_BY="migrate.sh:$(id -un)@$(hostname 2>/dev/null || echo unknown)"

C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYA=$'\033[36m'; C_RST=$'\033[0m'
info() { printf '%s==> %s%s\n' "$C_CYA" "$*" "$C_RST"; }
ok()   { printf '%s✓  %s%s\n' "$C_GRN" "$*" "$C_RST"; }
warn() { printf '%s!  %s%s\n' "$C_YEL" "$*" "$C_RST" >&2; }
die()  { printf '%s✗  %s%s\n' "$C_RED" "$*" "$C_RST" >&2; exit 1; }

# 用法文本 = 文件头注释块（从「# 用法：」到 set -euo 之前），随注释自动伸缩，避免行号漂移
usage() { sed -n '/^# 用法：/,/^set -euo pipefail/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --status)        [ "$MODE" = "apply" ] || die "--status 不能与其它模式组合"; MODE="status"; shift ;;
    --dry-run)       [ "$MODE" = "apply" ] || die "--dry-run 不能与其它模式组合"; MODE="dry-run"; shift ;;
    --baseline)      [ "$MODE" = "apply" ] || die "--baseline 不能与其它模式组合"; [ $# -ge 2 ] || die "--baseline 需要参数（all 或清单内文件名）"; MODE="baseline"; BASELINE_TARGET="$2"; shift 2 ;;
    --yes)           BASELINE_YES=1; shift ;;
    --force)         BASELINE_FORCE=1; shift ;;
    --skip-precheck) SKIP_PRECHECK=1; shift ;;
    --database)      [ $# -ge 2 ] || die "--database 需要参数"; DB="$2"; shift 2 ;;
    --host)          [ $# -ge 2 ] || die "--host 需要参数"; HOST="$2"; shift 2 ;;
    --port)          [ $# -ge 2 ] || die "--port 需要参数"; PORT="$2"; shift 2 ;;
    --container)     [ $# -ge 2 ] || die "--container 需要参数"; MYSQL_CONTAINER="$2"; shift 2 ;;
    --env-file)      [ $# -ge 2 ] || die "--env-file 需要参数"; ENV_FILE="$2"; shift 2 ;;
    --list)          [ $# -ge 2 ] || die "--list 需要参数"; LIST_FILE="$2"; shift 2 ;;
    --migration-dir) [ $# -ge 2 ] || die "--migration-dir 需要参数"; MIGRATION_DIR="$2"; shift 2 ;;
    -h|--help)       usage; exit 0 ;;
    *) die "未知参数：$1（-h 看用法）" ;;
  esac
done

if [ "$MODE" = "baseline" ]; then
  [ -n "$BASELINE_TARGET" ] || die "--baseline 需要参数（all 或清单内文件名）"
  case "$BASELINE_TARGET" in -*) die "--baseline 的目标不能以 - 开头：$BASELINE_TARGET" ;; esac
else
  [ "$BASELINE_YES" = "0" ] && [ "$BASELINE_FORCE" = "0" ] && [ "$SKIP_PRECHECK" = "0" ] \
    || die "--yes/--force/--skip-precheck 只用于 --baseline 模式"
fi
case "$DB" in *[!A-Za-z0-9_]*) die "非法库名：$DB（只允许字母/数字/下划线）" ;; esac
[ -f "$LIST_FILE" ] || die "找不到迁移清单：$LIST_FILE"
[ -f "$ENV_FILE" ] || die "找不到 ENV_FILE=$ENV_FILE（含 MYSQL_ROOT_PASSWORD）。用 --env-file 指定，或先跑 deploy/install.sh"
MYSQL_ROOT_PASSWORD="$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' "$ENV_FILE" | head -1)"
[ -n "$MYSQL_ROOT_PASSWORD" ] || die "$ENV_FILE 里没有非空的 MYSQL_ROOT_PASSWORD —— 拒绝以空口令连接 MySQL"
case "$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '?')" in
  600|400) : ;;
  *) warn "$ENV_FILE 权限是 $(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '?')，建议 chmod 600（含 MySQL root 口令）" ;;
esac

mysql_query() { # $1=SQL（单语句）；stdout
  if [ -n "$HOST" ]; then
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h "$HOST" -P "$PORT" -uroot \
      --default-character-set=utf8mb4 -N -B -e "$1"
  else
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD "$MYSQL_CONTAINER" \
      mysql -uroot --default-character-set=utf8mb4 -N -B -e "$1"
  fi
}
mysql_scalar() { mysql_query "$1" | tr -d ' \t\r\n'; }
mysql_apply_file() { # $1=宿主 SQL 路径
  if [ -n "$HOST" ]; then
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h "$HOST" -P "$PORT" -uroot \
      --default-character-set=utf8mb4 "$DB" < "$1"
  else
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -i -e MYSQL_PWD "$MYSQL_CONTAINER" \
      mysql -uroot --default-character-set=utf8mb4 "$DB" < "$1"
  fi
}
sha256_of() { sha256sum "$1" | awk '{print $1}'; }
table_exists() { [ "$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$1';")" = "1" ]; }

[ "$(mysql_scalar 'SELECT 1;')" = "1" ] || die "无法连接 MySQL（$([ -n "$HOST" ] && echo "直连 $HOST:$PORT" || echo "docker exec $MYSQL_CONTAINER")）——请检查容器/端口与 $ENV_FILE 口令"

declare -a AP_PENDING=() AP_MISMATCH=() AP_APPLIED=()
declare -A NEW_SUM=() LEGACY_HIT=()

load_ledgers() {
  NEW_SUM=(); LEGACY_HIT=()
  local fn ck
  if table_exists "$LEDGER_TABLE"; then
    while IFS=$'\t' read -r fn ck || [ -n "$fn" ]; do
      [ -n "$fn" ] || continue
      NEW_SUM["$fn"]="$ck"
    done < <(mysql_query "SELECT filename, checksum FROM $DB.$LEDGER_TABLE WHERE success=1;")
  fi
  if table_exists "$LEGACY_TABLE"; then
    while IFS= read -r fn || [ -n "$fn" ]; do
      [ -n "$fn" ] || continue
      LEGACY_HIT["$fn"]=1
    done < <(mysql_query "SELECT script_name FROM $DB.$LEGACY_TABLE;")
  fi
}

manifest_names() { grep -vE '^[[:space:]]*(#|$)' "$LIST_FILE" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'; }

classify() {
  AP_PENDING=(); AP_MISMATCH=(); AP_APPLIED=()
  local name file sum
  while IFS= read -r name || [ -n "$name" ]; do
    name="$(printf '%s' "$name" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    [ -n "$name" ] || continue
    case "$name" in \#*) continue ;; esac
    file="$MIGRATION_DIR/$name"
    [ -f "$file" ] || die "清单里的迁移文件不存在：$file（清单与目录不一致，拒绝继续）"
    sum="$(sha256_of "$file")"
    if [ -n "${NEW_SUM[$name]+x}" ]; then
      if [ "${NEW_SUM[$name]}" = "$sum" ]; then AP_APPLIED+=("$name"); else AP_MISMATCH+=("$name|${NEW_SUM[$name]}|$sum"); fi
    elif [ -n "${LEGACY_HIT[$name]+x}" ]; then
      AP_APPLIED+=("$name")
    else
      AP_PENDING+=("$name")
    fi
  done < "$LIST_FILE"
}

# 无任何可用登记、但库内已有 IoT 结构时的安全闸：拒绝把全部迁移当未应用重放（迁移非幂等）。
iot_device_exists() { [ "$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB' AND table_name='iot_device';")" = "1" ]; }
no_ledger_records() { [ "${#NEW_SUM[@]}" -eq 0 ] && [ "${#LEGACY_HIT[@]}" -eq 0 ]; }
guard_no_baseline() { # $1=1 表示 apply 模式（硬闸），0 表示仅告警
  no_ledger_records || return 0
  iot_device_exists || return 0
  warn "库内已有 IoT 结构（iot_device 存在），但既无 iot_schema_migration 记录，也无旧账本 $LEGACY_TABLE 记录。"
  if [ "$1" = "1" ]; then
    die "拒绝把全部迁移当「未应用」重放（迁移非幂等，会失败并可能留下半应用状态）。处置：
  1) 若部署曾用过 install.sh，旧账本 $LEGACY_TABLE 非空则本脚本会自动桥接——请先确认它是否存在/非空；
  2) 否则请人工建立基线：对照 deploy/sql/006-iot-schema.sql 与库内实际结构，把「确认已应用」的迁移
     逐条登记进 $LEDGER_TABLE（filename/checksum/applied_at/applied_by/success=1），再重跑本脚本；
     checksum 用：sha256sum deploy/sql/migration/<文件名>；
  3) 禁止直接重放全部迁移「碰运气」。"
  else
    warn ">>> --status/--dry-run 会把全部迁移显示为待应用；apply 模式会就此硬闸中止（需先建立基线）。"
  fi
}

print_counts() {
  printf '  已应用(applied)          : %d\n' "${#AP_APPLIED[@]}"
  printf '  待应用(pending)          : %d\n' "${#AP_PENDING[@]}"
  printf '  校验和不一致(mismatch)   : %d\n' "${#AP_MISMATCH[@]}"
}

print_mismatch_guidance() {
  printf '%s' "$C_RED"
  printf '✗ 校验和不一致（登记表记录的 sha256 与当前文件不同）：\n'
  local row name led cur
  for row in "${AP_MISMATCH[@]}"; do
    name="${row%%|*}"; row="${row#*|}"; led="${row%%|*}"; cur="${row#*|}"
    printf '    %s\n      登记表=%s\n      当前文件=%s\n' "$name" "$led" "$cur"
  done
  printf '%s' "$C_RST"
  cat >&2 <<'GUIDE'
  处置（绝不自动重放，必须人工决定）：
    1) 先确认「登记表记录的应用」是否真的生效（对照库内实际表/列）与文件为何被改动；
    2) 若改动是无意的：用 git 恢复迁移文件到应用当时的版本，再重跑本脚本；
    3) 若改动是有意的增量修订：为它新开一个迁移文件（追加到清单末尾），不要原地改已应用文件；
       确认后手工把登记表 checksum 更新为当前文件值：
       UPDATE iot_schema_migration SET checksum='<当前 sha256>' WHERE filename='<文件名>';
    4) 未经确认，禁止 UPDATE/DELETE 登记表来「消红」，也不要手工重放该文件。
GUIDE
}

print_status() {
  info "iot 迁移状态（库=$DB，清单=$LIST_FILE）"
  guard_no_baseline 0
  print_counts
  if [ "${#LEGACY_HIT[@]}" -gt 0 ]; then info "已合并旧账本 $LEGACY_TABLE 的 ${#LEGACY_HIT[@]} 条记录（新登记表未覆盖的按已应用处理）"; fi
  local n
  printf '%s\n' "-- 已应用（${#AP_APPLIED[@]}）--"
  for n in "${AP_APPLIED[@]}"; do printf '  %s\n' "$n"; done
  printf '%s\n' "-- 待应用（${#AP_PENDING[@]}）--"
  for n in "${AP_PENDING[@]}"; do printf '  %s\n' "$n"; done
  printf '%s\n' "-- 校验和不一致（${#AP_MISMATCH[@]}）--"
  for n in "${AP_MISMATCH[@]}"; do printf '  %s\n' "${n%%|*}"; done
  if [ "${#AP_MISMATCH[@]}" -gt 0 ]; then print_mismatch_guidance; return 1; fi
  if [ "${#AP_PENDING[@]}" -gt 0 ]; then return 1; fi
  return 0
}

print_dry_run() {
  info "iot 迁移计划（dry-run：不写库、不应用）"
  guard_no_baseline 0
  print_counts
  if [ "${#AP_MISMATCH[@]}" -gt 0 ]; then print_mismatch_guidance; return 1; fi
  if [ "${#AP_PENDING[@]}" -eq 0 ]; then ok "无待应用迁移（登记表与清单一致）"; return 0; fi
  printf '%s\n' "将按清单顺序应用以下 ${#AP_PENDING[@]} 个迁移："
  local n
  for n in "${AP_PENDING[@]}"; do printf '  %s\n' "$n"; done
  return 0
}

ensure_ledger_table() {
  if table_exists "$LEDGER_TABLE"; then return 0; fi
  local ledger_file="$MIGRATION_DIR/$LEDGER_NAME"
  [ -f "$ledger_file" ] || die "登记表 $LEDGER_TABLE 不存在，且找不到建表迁移 $ledger_file（清单首位应为 $LEDGER_NAME）"
  info "登记表 $LEDGER_TABLE 不存在：先应用 $LEDGER_NAME（CREATE TABLE IF NOT EXISTS，幂等）"
  mysql_apply_file "$ledger_file" || die "应用 $LEDGER_NAME 失败（登记表未建立，拒绝继续）"
}

grandfather_legacy() {
  if ! table_exists "$LEGACY_TABLE"; then return 0; fi
  local name file sum n=0
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    [ -n "${LEGACY_HIT[$name]+x}" ] || continue
    if [ -n "${NEW_SUM[$name]+x}" ]; then continue; fi
    file="$MIGRATION_DIR/$name"; [ -f "$file" ] || continue
    sum="$(sha256_of "$file")"
    mysql_query "INSERT INTO $DB.$LEDGER_TABLE (filename, checksum, applied_at, applied_by, success) VALUES ('$name','$sum',NOW(),'grandfather:$LEGACY_TABLE',1) ON DUPLICATE KEY UPDATE checksum=VALUES(checksum), applied_at=VALUES(applied_at), applied_by=VALUES(applied_by), success=1;"
    n=$((n+1))
  done < <(manifest_names)
  if [ "$n" -gt 0 ]; then
    warn "已把 $n 条旧账本（$LEGACY_TABLE）记录补登到 $LEDGER_TABLE（不执行 DDL）"
    warn ">>> 旧账本无 checksum：补登值取当前文件 sha256，无法检测这些文件的历史篡改（如实登记）"
  fi
}

apply_one() {
  local name="$1" file="$MIGRATION_DIR/$1" sum
  [ -f "$file" ] || die "迁移文件不存在：$file"
  sum="$(sha256_of "$file")"
  info "应用迁移 $name"
  mysql_query "INSERT INTO $DB.$LEDGER_TABLE (filename, checksum, applied_at, applied_by, success) VALUES ('$name','$sum',NOW(),'$APPLIED_BY',0) ON DUPLICATE KEY UPDATE checksum=VALUES(checksum), applied_at=VALUES(applied_at), applied_by=VALUES(applied_by), success=0;"
  if ! mysql_apply_file "$file"; then
    die "迁移 $name 应用失败（库可能处于半应用状态；登记表内该文件 success=0，人工核对后重跑本脚本）"
  fi
  mysql_query "UPDATE $DB.$LEDGER_TABLE SET success=1, checksum='$sum', applied_at=NOW(), applied_by='$APPLIED_BY' WHERE filename='$name';"
  if table_exists "$LEGACY_TABLE"; then
    mysql_query "INSERT IGNORE INTO $DB.$LEGACY_TABLE (script_name, applied_at) VALUES ('$name', NOW());"
  fi
  ok "已应用并登记 $name"
}

# =============================================================================
# --baseline：存量库「落地」——只登记、不执行
#
# 为什么允许「不执行就登记」：
#   本平台存在**存量库**（如 dev 的 ypbin_admin）：迁移在引入登记表（iot_schema_migration）
#   之前就已通过人工/历史 install.sh 应用到库内，但库内没有任何账本。此时：
#     * apply 会被安全闸拒绝（无账本 + iot_device 已存在 ⇒ 拒绝把非幂等迁移当未应用重放）；
#     * --status/--dry-run 会把全部迁移显示为「待应用」，无法用于升级判据。
#   唯一正确的落地方式：操作者先**人工核对库内 schema 确实已包含这些迁移的效果**，再把它们
#   按「已应用」登记进账本（checksum 取当前文件 sha256），后续升级即可正常增量。
#   这是一种**基于人工核验的信任声明**，不是自动探测——脚本不逐条判断迁移是否真的生效
#   （做不到：迁移无 IF NOT EXISTS、无版本戳、无 applied 标记），故必须有二次确认（--yes）
#   与既有账本保护（已有行默认拒绝，--force 才继续）。
#
# 边界：
#   * 只执行登记表自身的建表 DDL（000-iot-migration-ledger.sql，CREATE TABLE IF NOT EXISTS，
#     幂等的基础设施），**绝不执行任何业务迁移的 DDL**；
#   * 登记表已有行 ⇒ 默认拒绝（--force 才继续），防止把已有账本误覆盖；
#   * 重复 baseline 同一范围幂等（checksum 相同则跳过）。
# =============================================================================
declare -a BASELINE_NAMES=()
declare -A BASE_SUM=()

select_baseline_targets() { # $1=all 或清单内文件名；结果写入 BASELINE_NAMES（清单顺序，含目标）
  BASELINE_NAMES=()
  local target="$1" name seen=0
  local -a all=()
  while IFS= read -r name; do [ -n "$name" ] && all+=("$name"); done < <(manifest_names)
  [ "${#all[@]}" -gt 0 ] || die "迁移清单为空：$LIST_FILE"
  if [ "$target" = "all" ]; then BASELINE_NAMES=("${all[@]}"); return 0; fi
  for name in "${all[@]}"; do
    BASELINE_NAMES+=("$name")
    if [ "$name" = "$target" ]; then seen=1; break; fi
  done
  [ "$seen" = "1" ] || die "--baseline 的文件名不在清单内：$target（清单：$LIST_FILE）"
}

load_ledger_all() { # 读登记表**全部**行（含 success=0），供 baseline 判定新增/更新/跳过
  BASE_SUM=()
  table_exists "$LEDGER_TABLE" || return 0
  local fn ck
  while IFS=$'\t' read -r fn ck || [ -n "$fn" ]; do
    [ -n "$fn" ] || continue
    BASE_SUM["$fn"]="$ck"
  done < <(mysql_query "SELECT filename, checksum FROM $DB.$LEDGER_TABLE;")
}

precheck_baseline() { # 只读前置自检：给操作者判断「范围是否合理」的计数，不判定逐条是否已应用
  info "baseline 前置自检（只读统计；--skip-precheck 可跳过）"
  local manifest_total db_tables ledger_state iot_state last
  manifest_total="$(manifest_names | wc -l | tr -d ' ')"
  db_tables="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB';")"
  if table_exists "$LEDGER_TABLE"; then
    ledger_state="存在（当前 $(mysql_scalar "SELECT COUNT(*) FROM $DB.$LEDGER_TABLE;") 行）"
  else
    ledger_state="不存在（baseline 会先创建它）"
  fi
  iot_state="不存在"; iot_device_exists && iot_state="存在"
  last="${BASELINE_NAMES[${#BASELINE_NAMES[@]}-1]}"
  printf '  清单文件数                        : %s\n' "$manifest_total"
  printf '  本次登记范围（到 %s 为止，含）   : %d 条（%s ... %s）\n' \
    "$BASELINE_TARGET" "${#BASELINE_NAMES[@]}" "${BASELINE_NAMES[0]}" "$last"
  printf '  目标库 %s 已存在的表数          : %s\n' "$DB" "$db_tables"
  printf '  iot_device                        : %s\n' "$iot_state"
  printf '  登记表 %s          : %s\n' "$LEDGER_TABLE" "$ledger_state"
  warn "自检只给只读计数，**不会**逐条判定迁移是否已应用——迁移无 IF NOT EXISTS/版本戳，"
  warn "无法自动探测；必须人工对照 deploy/sql/006-iot-schema.sql 与库内实际表/列确认范围合理。"
}

print_baseline_plan() {
  info "baseline 计划（只登记不执行 DDL；库=$DB，登记表=$LEDGER_TABLE）"
  printf '  范围        : %s（到 %s 为止，含）\n' "$BASELINE_TARGET" "${BASELINE_NAMES[${#BASELINE_NAMES[@]}-1]}"
  printf '  将登记条数  : %d\n' "${#BASELINE_NAMES[@]}"
  printf '  checksum    : sha256(迁移文件内容)，小写十六进制（与 apply 模式一致）\n'
  printf '  applied_by  : %s\n' "$BASELINE_BY"
  printf '%s\n' "-- 将登记（文件名 + 当前 sha256）--"
  local n
  for n in "${BASELINE_NAMES[@]}"; do printf '  %s  %s\n' "$(sha256_of "$MIGRATION_DIR/$n")" "$n"; done
}

run_baseline() {
  BASELINE_BY="baseline:migrate.sh:$(id -un)@$(hostname 2>/dev/null || echo unknown)"
  select_baseline_targets "$BASELINE_TARGET"
  [ "$SKIP_PRECHECK" = "1" ] || precheck_baseline
  print_baseline_plan
  if [ "$BASELINE_YES" != "1" ]; then
    warn "未给 --yes：以上仅为将登记的内容，未写库（dry，退出码 0）。确认无误后加 --yes 重跑。"
    exit 0
  fi
  if table_exists "$LEDGER_TABLE"; then
    local rows
    rows="$(mysql_scalar "SELECT COUNT(*) FROM $DB.$LEDGER_TABLE;")"
    if [ "${rows:-0}" -gt 0 ] && [ "$BASELINE_FORCE" != "1" ]; then
      die "登记表 $LEDGER_TABLE 已有 $rows 行，默认拒绝 baseline（防误覆盖）。处置：
  1) 若这些行是本环境真实的应用历史：不要 baseline，直接 deploy/migrate.sh --status 查看；
  2) 若确需以当前文件重建/补齐基线：先人工核对 schema，确认要覆盖的范围，再加 --force 重跑
     （--force 会用当前文件 sha256 覆盖同名行的 checksum/applied_at/applied_by）；
  3) 或先备份并手工清理后重跑（DELETE FROM $LEDGER_TABLE 属高风险，务必先 mysqldump）。"
    fi
  fi
  # 只建登记表本身（幂等基础设施），不执行任何业务迁移 DDL
  ensure_ledger_table
  load_ledger_all
  local name file sum n_new=0 n_upd=0 n_skip=0
  for name in "${BASELINE_NAMES[@]}"; do
    file="$MIGRATION_DIR/$name"
    [ -f "$file" ] || die "迁移文件不存在：$file"
    sum="$(sha256_of "$file")"
    if [ -n "${BASE_SUM[$name]+x}" ] && [ "${BASE_SUM[$name]}" = "$sum" ]; then
      n_skip=$((n_skip+1)); continue
    fi
    if [ -n "${BASE_SUM[$name]+x}" ]; then
      mysql_query "UPDATE $DB.$LEDGER_TABLE SET checksum='$sum', applied_at=NOW(), applied_by='$BASELINE_BY', success=1 WHERE filename='$name';"
      n_upd=$((n_upd+1))
    else
      mysql_query "INSERT INTO $DB.$LEDGER_TABLE (filename, checksum, applied_at, applied_by, success) VALUES ('$name','$sum',NOW(),'$BASELINE_BY',1);"
      n_new=$((n_new+1))
    fi
  done
  ok "baseline 完成：登记 ${#BASELINE_NAMES[@]} 条（新增 $n_new，更新 $n_upd，跳过 $n_skip）"
  printf '  范围        : %s（%s ... %s，含）\n' "$BASELINE_TARGET" "${BASELINE_NAMES[0]}" "${BASELINE_NAMES[${#BASELINE_NAMES[@]}-1]}"
  printf '  checksum    : sha256(迁移文件内容)，小写十六进制\n'
  warn "以上迁移已按「已应用」登记；**未执行任何迁移 DDL**。"
  warn ">>> 请立刻用 deploy/migrate.sh --status 复核（应为 0 pending / 0 mismatch）。"
}

load_ledgers
classify

case "$MODE" in
  status)  print_status ;;
  dry-run) print_dry_run ;;
  baseline) run_baseline ;;
  apply)
    if [ "${#AP_MISMATCH[@]}" -gt 0 ]; then print_mismatch_guidance; die "存在校验和不一致，已中止（不自动重放）"; fi
    ensure_ledger_table
    grandfather_legacy
    load_ledgers
    classify
    if [ "${#AP_MISMATCH[@]}" -gt 0 ]; then print_mismatch_guidance; die "补登旧账本后出现校验和不一致，已中止"; fi
    if [ "${#AP_PENDING[@]}" -eq 0 ]; then ok "无待应用迁移（登记表与清单一致，重跑幂等）"; exit 0; fi
    guard_no_baseline 1
    info "待应用 ${#AP_PENDING[@]} 个迁移，按清单顺序应用"
    for name in "${AP_PENDING[@]}"; do apply_one "$name"; done
    ok "迁移完成：本次新应用 ${#AP_PENDING[@]} 个"
    ;;
esac
