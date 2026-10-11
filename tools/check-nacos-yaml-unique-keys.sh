#!/usr/bin/env bash
# Nacos yaml 顶层键唯一性检查：YAML 语法**不报**顶层重复键，但语义上后者覆盖前者
# ⇒ 前面整块配置被静默丢弃。本工具守住这条"静默降级"。
#
# 用法: check-nacos-yaml-unique-keys.sh [目录]   (默认 deploy/nacos)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=${1:-"$ROOT/deploy/nacos"}
python3 - "$DIR" <<'PY'
import sys, pathlib
try:
    import yaml
except ImportError:
    print("需要 python3-yaml"); sys.exit(3)

class Dup(Exception):
    pass

class UniqueLoader(yaml.SafeLoader):
    pass

def unique(loader, node, deep=False):
    seen = {}
    for k, v in node.value:
        key = loader.construct_object(k, deep=deep)
        if key in seen:
            raise Dup(f"第 {k.start_mark.line + 1} 行重复顶层键: {key!r}（首次出现在第 {seen[key]} 行）")
        seen[key] = k.start_mark.line + 1
    return loader.construct_mapping(node, deep)

UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique)

bad = 0
for f in sorted(pathlib.Path(sys.argv[1]).glob('*.yaml')):
    try:
        yaml.load(f.read_text(encoding='utf-8'), Loader=UniqueLoader)
        print(f"OK   {f.name}")
    except Dup as e:
        print(f"FAIL {f.name}: {e}"); bad += 1
sys.exit(1 if bad else 0)
PY
