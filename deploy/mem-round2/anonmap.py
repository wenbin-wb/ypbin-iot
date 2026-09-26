#!/usr/bin/env python3
# anonmap.py —— 统计 /proc/<pid>/smaps 中「Anonymous >= THRESH_KB」的匿名映射**数量与总大小**，
#              并汇总 Pss / Rss / Anonymous 总量 / 最大单块。
#
# 为什么用这个量作主判据（不要用 available 或 Pss_Anon 单值）：
#   · `available` 会被「JVM 变热 / ZGC 提交策略」左右（上一轮踩过两次），重启后虚高约 300MB；
#   · glibc malloc arena 的表现是**大块匿名 mmap**（每个 arena 是一个 HEAP_MAX_SIZE 级映射），
#     所以「≥1MB 匿名映射的数量与总大小」对 arena 变化敏感，且**不受"刚重启 vs 变热"影响**。
#   只读，不改变目标进程任何状态。
import re
import sys


def main() -> None:
    pid = sys.argv[1]
    thresh_kb = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    hdr = re.compile(r"^[0-9a-f]+-[0-9a-f]+ ")

    big_n = big_kb = 0
    anon_n = anon_kb = 0
    pss = rss = 0
    maps = 0
    biggest = 0
    cur_anon = None

    def flush() -> None:
        nonlocal big_n, big_kb, anon_n, anon_kb, biggest
        if cur_anon is None:
            return
        if cur_anon > 0:
            anon_n += 1
            anon_kb += cur_anon
            biggest = max(biggest, cur_anon)
        if cur_anon >= thresh_kb:
            big_n += 1
            big_kb += cur_anon

    with open(f"/proc/{pid}/smaps", encoding="ascii", errors="replace") as fh:
        for line in fh:
            if hdr.match(line):
                flush()
                cur_anon = 0
                maps += 1
                continue
            if cur_anon is None:
                continue
            if line.startswith("Anonymous:"):
                cur_anon = int(line.split()[1])
            elif line.startswith("Pss:"):
                pss += int(line.split()[1])
            elif line.startswith("Rss:"):
                rss += int(line.split()[1])
    flush()

    print(
        f"pid={pid} maps={maps} anon_maps={anon_n} anon_mb={anon_kb // 1024} "
        f"big{thresh_kb // 1024}mb_n={big_n} big{thresh_kb // 1024}mb_mb={big_kb // 1024} "
        f"biggest_anon_mb={biggest // 1024} pss_mb={pss // 1024} rss_mb={rss // 1024}"
    )


main()
