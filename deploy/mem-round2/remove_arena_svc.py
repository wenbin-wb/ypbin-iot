#!/usr/bin/env python3
"""从 compose override 里移除指定服务的 MALLOC_ARENA_MAX 键及其紧邻的说明注释行（幂等）。

用法: remove_arena_svc.py <service> [<service> ...]
测试: OVERRIDE_PATH=/tmp/copy.yml remove_arena_svc.py ypbin-gateway
"""
import os
import pathlib
import re
import sys

P = pathlib.Path(os.environ.get("OVERRIDE_PATH",
                                "/opt/ypbin/ypbin-iot/deploy/docker-compose.override.yml"))


def strip_block(block: str) -> str:
    """删掉块内 MALLOC_ARENA_MAX 行 + 它上方连续的注释行。"""
    lines = block.splitlines(keepends=True)
    keep: list[str] = []
    for ln in lines:
        if "MALLOC_ARENA_MAX" in ln:
            while keep and keep[-1].lstrip().startswith("#"):
                keep.pop()
            continue
        keep.append(ln)
    return "".join(keep)


def main() -> int:
    src = P.read_text(encoding="utf-8")
    changed = False
    for svc in sys.argv[1:]:
        pat = re.compile(rf"(^  {re.escape(svc)}:\n)(.*?)(?=^  \S|\Z)", re.S | re.M)
        m = pat.search(src)
        if not m:
            print(f"SKIP {svc}: 未找到块")
            continue
        block = m.group(2)
        if "MALLOC_ARENA_MAX" not in block:
            print(f"SKIP {svc}: 块内没有 MALLOC_ARENA_MAX")
            continue
        newblock = strip_block(block)
        assert "MALLOC_ARENA_MAX" not in newblock, svc
        assert newblock.strip(), f"{svc}: 剥完变空了，拒绝改动"
        src = src[: m.start()] + m.group(1) + newblock + src[m.end():]
        print(f"OK   {svc}: 已移除 MALLOC_ARENA_MAX（注释行一并清理）")
        changed = True
    if changed:
        P.write_text(src, encoding="utf-8")
    return 0


raise SystemExit(main())
