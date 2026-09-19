#!/usr/bin/env bash
# 校验 deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql
# 与 deploy/sql/006-iot-schema.sql + deploy/sql/007-iot-data.sql 的**语句**等价。
#
# 为什么需要它：迁移脚本与全新安装脚本是同一份 DDL/DML 的两个副本，人工同步必然漂移；
# 注释允许不同，语句必须逐字相同（去 -- 注释、去空行、压缩连续空白后比较；**不做分号级归一化**）。
set -euo pipefail

cd "$(dirname "$0")/.."

norm() {
  # 去 -- 行注释 → 压缩连续空白 → 去空行
  sed -E 's/--.*$//' "$@" | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//' | grep -v '^$'
}

fresh="$(mktemp)"
migration="$(mktemp)"
trap 'rm -f "$fresh" "$migration"' EXIT

norm deploy/sql/006-iot-schema.sql deploy/sql/007-iot-data.sql > "$fresh"
norm deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql > "$migration"

if diff -u "$fresh" "$migration"; then
  echo "OK: IoT 迁移脚本与全新安装脚本的语句等价"
else
  echo "::error::IoT 迁移脚本与 006+007 的语句不一致——请同步修改（注释可不同，语句必须相同）" >&2
  exit 1
fi
