#!/usr/bin/env python3
"""给 compose override 里指定服务的 environment 加上 MALLOC_ARENA_MAX: "2"（幂等）。

用法: add_arena_svc.py <service> [<service> ...]
输出会对每个服务打印 OK / SKIP，并做锚点出现次数断言（必须恰好 1 次）。
"""
import pathlib
import sys

P = pathlib.Path("/opt/ypbin/ypbin-iot/deploy/docker-compose.override.yml")

TMPL = """  {svc}:
    environment:
      JAVA_OPTS: "-XX:+UseZGC -XX:+ZGenerational -Xms128m -Xmx384m"
    mem_limit: 1280m
"""

NEW = """  {svc}:
    environment:
      JAVA_OPTS: "-XX:+UseZGC -XX:+ZGenerational -Xms128m -Xmx384m"
      # ── MALLOC_ARENA_MAX = 2（2026-09-26 推广；依据见 MEM-TUNING-REPORT-20260926-R2.md §2）──
      # 限制 glibc 每进程 malloc arena 数量（默认上限 = 8 × CPU 核数）：实测 ypbin-iot 上
      # 非堆匿名 −23.8%、≥1MB 匿名映射数量 −38.5%、总大小 −23.4%，延迟无一致劣化。
      # 回滚：删掉本键 + `docker compose ... up -d --no-deps --force-recreate <svc>`。
      MALLOC_ARENA_MAX: "2"
    mem_limit: 1280m
"""


def main() -> int:
    src = P.read_text(encoding="utf-8")
    changed = False
    for svc in sys.argv[1:]:
        anchor = TMPL.format(svc=svc)
        if anchor in src:
            n = src.count(anchor)
            assert n == 1, f"{svc}: 锚点出现 {n} 次（期望 1），拒绝改动"
            src = src.replace(anchor, NEW.format(svc=svc), 1)
            print(f"OK   {svc}: 已加入 MALLOC_ARENA_MAX=2")
            changed = True
        else:
            print(f"SKIP {svc}: 未找到干净锚点（可能已加过该键，或格式变了）")
    if changed:
        P.write_text(src, encoding="utf-8")
    return 0


raise SystemExit(main())
