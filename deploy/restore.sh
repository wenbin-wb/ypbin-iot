#!/usr/bin/env bash
# ============================================================
# ypbin-iot 数据恢复（MySQL / IoTDB）——**默认只打印计划，不执行**
#
# 用法：
#   deploy/restore.sh --mysql <backup.sql> [--iotdb <backup.tar>]          # 只打印将要做什么
#   deploy/restore.sh --mysql <backup.sql> --iotdb <backup.tar> --yes      # 真正执行（破坏性）
#   deploy/restore.sh --mysql <backup.sql> --yes --dry-run                 # 等价于不加 --yes
#
# 安全纪律：
#   * **必须**显式传 `--yes` 才执行破坏性动作（覆盖库表 / 清空 IoTDB 数据卷）；
#   * 恢复前打印来源文件绝对路径 + sha256 校验结果（有同名 .sha256 才比对，没有则如实提示「无参照」）；
#   * MySQL 口令经 env（MYSQL_PWD）转发，不进 argv；IoTDB 恢复会先停容器再替换数据卷。
#
# ⚠️ 未覆盖的边界（如实登记）：
#   * MySQL：用 `mysql < dump` 重放 SQL。dump 内含 DROP TABLE/CREATE TABLE ⇒ 会覆盖同名表；
#     不涉及用户/权限/GTID 位点，也不做「恢复到某个时间点」的 PITR（需要 binlog）。
#   * IoTDB：用「停容器 → 清空具名卷 → docker cp 解包备份 tar → 起容器」整卷替换。
#     - 只支持 backup.sh 产出的 /iotdb/data 整卷 tar；**不支持增量/合并**；
#     - 若容器用的是 bind mount（不是具名卷），脚本**拒绝自动清空**并打印手工步骤（避免误删宿主目录）；
#     - 恢复一致性依赖备份本身的边界（见 backup.sh 脚本头）。
#   * 两个组件都能恢复 ≠ 跨组件事务一致：MySQL 与 IoTDB 是分别备份/恢复的，时间点可能不同。
#   * 真机演练步骤见 deploy/PROD-OPS-NOTES.md「数据备份/恢复」一节。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${YPBIN_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ypbin-mysql}"
IOTDB_CONTAINER="${IOTDB_CONTAINER:-ypbin-iotdb}"
IOTDB_APP_CONTAINER="${IOTDB_APP_CONTAINER:-ypbin-iot}"

info()  { printf '\033[36m==> %s\033[0m\n' "$*"; }
ok()    { printf '\033[32m✓  %s\033[0m\n' "$*"; }
warn()  { printf '\033[33m!  %s\033[0m\n' "$*"; }
die()   { printf '\033[31m✗  %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; }

MYSQL_SRC=""; IOTDB_SRC=""; CONFIRM=0; DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --mysql) [ $# -ge 2 ] || die "--mysql 需要一个文件参数"; MYSQL_SRC="$2"; shift 2 ;;
    --iotdb) [ $# -ge 2 ] || die "--iotdb 需要一个文件参数"; IOTDB_SRC="$2"; shift 2 ;;
    --yes) CONFIRM=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage; exit 2 ;;
  esac
done
[ -n "$MYSQL_SRC" ] || [ -n "$IOTDB_SRC" ] || die "至少要用 --mysql <file> 或 --iotdb <file> 指定恢复来源（本脚本不做「猜最新备份」）"
[ "$DRY_RUN" = "1" ] && CONFIRM=0

verify_sha() { # $1=文件；存在同名 .sha256 时比对，否则只打印实际值
  local f="$1" want got
  [ -f "$f" ] || die "恢复来源不存在：$f"
  got="$(sha256sum "$f" | cut -d' ' -f1)"
  if [ -f "$f.sha256" ]; then
    want="$(cut -d' ' -f1 "$f.sha256" | head -1)"
    if [ "$want" = "$got" ]; then
      ok "sha256 校验通过：$(basename "$f") = $got"
    else
      die "sha256 不匹配：$(basename "$f") 实际=$got 参照=$want —— 恢复中止（文件可能损坏/被替换）"
    fi
  else
    warn "$(basename "$f") 无同名 .sha256 参照；实际 sha256=$got（无法比对，请自行确认来源可信）"
  fi
}

plan() {
  info "恢复计划（来源已核 sha256）："
  echo "  ENV_FILE=$ENV_FILE"
  [ -n "$MYSQL_SRC" ] && {
    echo "  MySQL → 覆盖容器 $MYSQL_CONTAINER 内 ypbin_admin/xxl_job（重放 $MYSQL_SRC 的 DROP/CREATE/INSERT）"
    echo "          口令经 env MYSQL_PWD 转发；命令：docker exec -i -e MYSQL_PWD $MYSQL_CONTAINER sh -c 'mysql -uroot' < <file>"
  }
  [ -n "$IOTDB_SRC" ] && {
    echo "  IoTDB → 破坏性整卷替换：停 $IOTDB_APP_CONTAINER/$IOTDB_CONTAINER → 清空具名卷 → docker cp 解包 $IOTDB_SRC → 起 $IOTDB_CONTAINER"
  }
  echo "  强制执行需在命令末尾加 --yes"
}

echo "============================================================"
echo " ypbin-iot 数据恢复"
echo "============================================================"
[ -n "$MYSQL_SRC" ] && verify_sha "$MYSQL_SRC"
[ -n "$IOTDB_SRC" ] && verify_sha "$IOTDB_SRC"
plan
if [ "$CONFIRM" != "1" ]; then
  echo
  warn "未传 --yes：以上只是计划，**未执行任何破坏性动作**。确认无误后加 --yes 重跑。"
  exit 0
fi

command -v docker >/dev/null 2>&1 || die "找不到 docker"
echo
warn "开始执行破坏性恢复（--yes 已确认）"

# ---------- MySQL ----------
if [ -n "$MYSQL_SRC" ]; then
  info "恢复 MySQL：$MYSQL_SRC"
  [ -f "$ENV_FILE" ] || die "找不到 ENV_FILE=$ENV_FILE（需要 MYSQL_ROOT_PASSWORD）"
  MYSQL_ROOT_PASSWORD="$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' "$ENV_FILE" | head -1)"
  [ -n "$MYSQL_ROOT_PASSWORD" ] || die "$ENV_FILE 里没有非空的 MYSQL_ROOT_PASSWORD"
  docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -qx true \
    || die "MySQL 容器 $MYSQL_CONTAINER 不在运行 —— 无法恢复"
  # 口令经 env 转发；SQL 经 stdin 送入容器（不经 argv）。
  if ! MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -i -e MYSQL_PWD "$MYSQL_CONTAINER" \
        sh -c "mysql --default-character-set=utf8mb4 -uroot" < "$MYSQL_SRC"; then
    die "MySQL 恢复失败（dump 重放非 0 退出）——请按上方 mysql 输出定位；库可能处于半恢复状态"
  fi
  ok "MySQL 恢复完成（$MYSQL_SRC）"
fi

# ---------- IoTDB ----------
if [ -n "$IOTDB_SRC" ]; then
  info "恢复 IoTDB（停容器 → 清空具名卷 → 解包 → 起容器）"
  image="$(docker inspect -f '{{.Config.Image}}' "$IOTDB_CONTAINER" 2>/dev/null || true)"
  vol="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/iotdb/data"}}{{.Name}}{{end}}{{end}}' "$IOTDB_CONTAINER" 2>/dev/null || true)"
  [ -n "$image" ] || die "取不到 $IOTDB_CONTAINER 的镜像名 —— 恢复中止"
  [ -n "$vol" ] || die "取不到 /iotdb/data 对应的**具名卷**（可能是 bind mount）——拒绝自动清空，避免误删宿主目录。
     请手工：停 $IOTDB_APP_CONTAINER/$IOTDB_CONTAINER → 备份并清空该目录 → 
       docker cp - $IOTDB_CONTAINER:/iotdb/ < $IOTDB_SRC
     后启动容器。"
  stopped_app=0
  if docker inspect -f '{{.State.Running}}' "$IOTDB_APP_CONTAINER" >/dev/null 2>&1 \
     && [ "$(docker inspect -f '{{.State.Running}}' "$IOTDB_APP_CONTAINER")" = "true" ]; then
    info "停止 $IOTDB_APP_CONTAINER（避免恢复期间写入）"; docker stop "$IOTDB_APP_CONTAINER" >/dev/null; stopped_app=1
  fi
  info "停止 $IOTDB_CONTAINER"; docker stop "$IOTDB_CONTAINER" >/dev/null
  info "清空数据卷 $vol"
  docker run --rm -v "$vol":/v --entrypoint /bin/sh "$image" -c 'rm -rf /v/* /v/.[!.]* 2>/dev/null || true'
  info "解包备份到 $IOTDB_CONTAINER:/iotdb/"
  docker cp - "$IOTDB_CONTAINER:/iotdb/" < "$IOTDB_SRC"
  info "启动 $IOTDB_CONTAINER"; docker start "$IOTDB_CONTAINER" >/dev/null
  if [ "$stopped_app" = "1" ]; then
    info "启动 $IOTDB_APP_CONTAINER"; docker start "$IOTDB_APP_CONTAINER" >/dev/null
  fi
  ok "IoTDB 恢复完成（$IOTDB_SRC → 卷 $vol）"
  warn "请按 docs/DEPLOY-TIMESERIES.md §3 做一次人工验证（SHOW TABLES FROM iot + 写入/查询往返）"
fi

echo
ok "恢复流程结束。建议核验："
info "  MySQL：进容器核对 ypbin_admin 表数与关键表（见 deploy/PROD-OPS-NOTES.md「数据备份/恢复」）"
info "  IoTDB：按 docs/DEPLOY-TIMESERIES.md §3 做 SHOW TABLES FROM iot + 写入/查询往返"
warn "跨组件一致性：MySQL 与 IoTDB 是分别备份/恢复的，时间点可能不同；恢复后请做一次业务面冒烟"
