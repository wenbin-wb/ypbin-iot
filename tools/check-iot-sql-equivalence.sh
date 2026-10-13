#!/usr/bin/env bash
# 校验 deploy/sql/migration/*.sql（按文件名排序拼接）
# 与 deploy/sql/006-iot-schema.sql + deploy/sql/007-iot-data.sql 的**语句**等价。
#
# 为什么需要它：迁移脚本与全新安装脚本是同一份 DDL/DML 的两个副本，人工同步必然漂移；
# 注释允许不同，语句必须逐字相同（去 -- 注释、去空行、压缩连续空白后比较；**不做分号级归一化**）。
#
# 例外（本门禁自己维护）：
#   * 000-iot-migration-ledger.sql 是升级运行器（deploy/migrate.sh）的**登记表基础设施**，
#     不属于「006+007 全新安装镜像」，按名排除在「语句等价」比较之外；
#     但它必须真实存在、必须收录进 iot-migrations.list、且必须排在清单首位（先建账本再应用）。
set -euo pipefail

cd "$(dirname "$0")/.."

LEDGER_NAME="000-iot-migration-ledger.sql"
MANIFEST="deploy/sql/migration/iot-migrations.list"

norm() {
  # 去 -- 行注释 → 压缩连续空白 → 去空行
  sed -E 's/--.*$//' "$@" | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//' | grep -v '^$'
}

all_migrations="$(ls deploy/sql/migration/*-iot-*.sql 2>/dev/null | sort || true)"
if [ -z "$all_migrations" ]; then
  echo "::error::没找到任何 IoT 迁移文件（deploy/sql/migration/*-iot-*.sql）——本校验会变成空跑（假绿）" >&2
  exit 1
fi

# 登记表迁移不参与「006+007 语句等价」比较（它是运行器基础设施，不是全新安装镜像的一部分）
cmp_migrations="$(printf '%s\n' $all_migrations | grep -v "/$LEDGER_NAME\$" || true)"
if [ -z "$cmp_migrations" ]; then
  echo "::error::排除 $LEDGER_NAME 后没有可比对的 IoT 迁移文件" >&2
  exit 1
fi

fresh="$(mktemp)"
migration="$(mktemp)"
trap 'rm -f "$fresh" "$migration"' EXIT

norm deploy/sql/006-iot-schema.sql deploy/sql/007-iot-data.sql > "$fresh"
# 迁移目录按文件名排序拼接：新迁移文件名用日期前缀，顺序即「结构演进顺序」，与 006 的追加顺序一致
# 只比 **IoT 自己的** 迁移文件：迁移目录里还有 admin 基座自带的迁移（如 ai 的），
# 它们不属于本仓的安装脚本，混进来会让比较恒假。命名规约：IoT 迁移文件名必须含 `-iot-`。
# shellcheck disable=SC2086
norm $cmp_migrations > "$migration"

# 迁移清单（iot-migrations.list）：install.sh 在「脚本自身位置拿不到 deploy/sql/」时（README 的
# `bash <(curl ...)` 场景）按它从远端逐个下载 *-iot-*.sql。清单必须与目录内实际文件一一对应——
# 少一个 ⇒ 远端兜底安装缺迁移；多一个 ⇒ 下载不存在的文件直接失败。故在此与 glob 结果强校验。
if [ ! -f "$MANIFEST" ]; then
  echo "::error::缺少 $MANIFEST（install.sh 远端兜底依赖它逐份下载迁移）" >&2
  exit 1
fi
manifest_names="$(grep -vE '^[[:space:]]*(#|$)' "$MANIFEST" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sort)"
dir_names="$(printf '%s\n' $all_migrations | sed 's#.*/##' | sort)"
if [ "$manifest_names" != "$dir_names" ]; then
  echo "::error::$MANIFEST 与 deploy/sql/migration/*-iot-*.sql 不一致（新增迁移后请同步清单）" >&2
  diff -u <(printf '%s\n' "$dir_names") <(printf '%s\n' "$manifest_names") >&2 || true
  exit 1
fi

# 登记表迁移：必须真实存在、且排在清单首位（deploy/migrate.sh 依赖它先建账本）
if [ ! -f "deploy/sql/migration/$LEDGER_NAME" ]; then
  echo "::error::缺少登记表迁移 deploy/sql/migration/$LEDGER_NAME（deploy/migrate.sh 依赖它建立 iot_schema_migration）" >&2
  exit 1
fi
first_name="$(grep -vE '^[[:space:]]*(#|$)' "$MANIFEST" | head -1 | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
if [ "$first_name" != "$LEDGER_NAME" ]; then
  echo "::error::$LEDGER_NAME 必须排在 $MANIFEST 首位（先建账本再应用），当前首位是：$first_name" >&2
  exit 1
fi
if ! grep -q 'iot_schema_migration' "deploy/sql/migration/$LEDGER_NAME"; then
  echo "::error::$LEDGER_NAME 里没有 iot_schema_migration 建表语句" >&2
  exit 1
fi

if diff -u "$fresh" "$migration"; then
  echo "OK: IoT 迁移脚本与全新安装脚本的语句等价（已排除登记表 $LEDGER_NAME）"
else
  echo "::error::IoT 迁移脚本与 006+007 的语句不一致（比较对象：006+007 vs migration/*.sql 按名排序拼接，排除 $LEDGER_NAME）——请同步修改" >&2
  exit 1
fi
