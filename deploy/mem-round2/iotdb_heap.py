#!/usr/bin/env python3
"""从 IoTDB 的 Prometheus 端点提取 JVM 堆/GC/线程关键指标（供下一步评估 MEMORY_SIZE）。
只读，只打印汇总，不打印全量。"""
import re
import sys
import urllib.request

for port, node in ((9091, "ConfigNode"), (9092, "DataNode")):
    try:
        text = urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=15).read().decode()
    except Exception as exc:  # noqa: BLE001
        print(f"--- {node} (:{port}) 读取失败: {exc}")
        continue
    pools = {}
    for line in text.splitlines():
        m = re.match(r"^(jvm_memory_(?:used|committed|max)_bytes)\{([^}]*)\} ([0-9.eE+]+)$", line)
        if not m:
            continue
        labels = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2)))
        if labels.get("area") != "heap":
            continue
        key = m.group(1).replace("jvm_memory_", "").replace("_bytes", "")
        pools.setdefault(labels.get("id"), {})[key] = float(m.group(3))

    print(f"--- {node} (:{port}) 堆池（top-level）---")
    for pool, mm in pools.items():
        if pool == "G1 Survivor Space":
            continue
        print(
            f"    heap[{pool}]: used={mm.get('used', 0)/1048576:.1f}MB "
            f"committed={mm.get('committed', 0)/1048576:.1f}MB max={mm.get('max', 0)/1048576:.1f}MB"
        )

    def one(pat, div=1048576.0, fmt="%.1f"):
        m = re.search(pat, text, re.M)
        return (fmt % (float(m.group(1)) / div)) if m else "n/a"

    print(f"    jvm_gc_max_data_size  = {one(r'^jvm_gc_max_data_size_bytes\{[^}]*\} ([0-9.eE+]+)$')} MB")
    print(f"    jvm_gc_live_data_size = {one(r'^jvm_gc_live_data_size_bytes\{[^}]*\} ([0-9.eE+]+)$')} MB")
    print(f"    jvm_threads_live      = {one(r'^jvm_threads_live_threads\{[^}]*\} ([0-9.eE+]+)$', 1.0, '%.0f')}")
    print(
        "    direct_buffer         = "
        f"{one(r'^jvm_buffer_memory_used_bytes\{[^}]*id=.direct.[^}]*\} ([0-9.eE+]+)$')} MB"
    )
    print()
