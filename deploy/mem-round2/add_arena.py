#!/usr/bin/env python3
"""把 MALLOC_ARENA_MAX: "2" 加入 compose override 的 ypbin-iot 服务（幂等；已存在则跳过）。"""
import pathlib
import sys

P = pathlib.Path("/opt/ypbin/ypbin-iot/deploy/docker-compose.override.yml")
src = P.read_text(encoding="utf-8")

if "MALLOC_ARENA_MAX" in src:
    print("SKIP: override 里已存在 MALLOC_ARENA_MAX；不改动")
    sys.exit(0)

ANCHOR = """  ypbin-iot:
    environment:
      JAVA_OPTS: "-XX:+UseZGC -XX:+ZGenerational -Xms128m -Xmx384m"
    mem_limit: 1280m
"""
NEW = """  ypbin-iot:
    environment:
      JAVA_OPTS: "-XX:+UseZGC -XX:+ZGenerational -Xms128m -Xmx384m"
      # ── MALLOC_ARENA_MAX = 2（2026-09-26 本轮 A/B 的 B 腿）──
      # 目的：限制 glibc 每进程 malloc arena 数量（默认上限 = 8 × CPU 核数，最多 64 个，
      #   每个 arena 可达 HEAP_MAX_SIZE 级），针对上一轮实测的"非堆匿名内存 430–550MB/容器"。
      # ⚠️ 是否保留取决于本轮 A/B 结论；若结论是"无效"，必须删掉本键（见报告「任务 2」）。
      # 只加这一个键，JAVA_OPTS / mem_limit / 镜像 / 卷 均不变。
      MALLOC_ARENA_MAX: "2"
    mem_limit: 1280m
"""
n = src.count(ANCHOR)
assert n == 1, f"锚点出现 {n} 次（期望 1），拒绝改动"
P.write_text(src.replace(ANCHOR, NEW), encoding="utf-8")
print("OK: 已写入 MALLOC_ARENA_MAX=2")
