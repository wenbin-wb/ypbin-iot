#!/usr/bin/env python3
"""A/B 结果汇总：解析 legA.tsv / legB.tsv，比对主判据（≥1MB 匿名映射的数量与总大小）与辅助量。

用法: analyze_ab.py legA.tsv legB.tsv
"""
import re
import statistics as st
import sys

FIELDS = {
    "big1mb_n": r"big1mb_n=(\d+)",
    "big1mb_mb": r"big1mb_mb=(\d+)",
    "anon_maps": r"anon_maps=(\d+)",
    "anon_mb": r"anon_mb=(\d+)",
    "biggest_anon_mb": r"biggest_anon_mb=(\d+)",
    "pss_mb": r"pss_mb=(\d+)",
    "rss_mb": r"rss_mb=(\d+)",
}
COLS = ["avail_mb", "cg_mem_mb", "nonheap_committed_mb", "direct_mb", "heap_used_mb", "threads"]


def parse(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        if not line.startswith(("A\t", "B\t")):
            continue
        p = line.rstrip("\n").split("\t")
        rec = {"ts": p[1], "elapsed": int(p[2])}
        for k, rx in FIELDS.items():
            m = re.search(rx, p[3])
            rec[k] = int(m.group(1)) if m else None
        for i, c in enumerate(COLS):
            rec[c] = int(p[4 + i])
        rows.append(rec)
    return rows


def agg(rows, key, skip=3):
    """丢弃前 skip 个样本（去启动瞬态），返回 (mean, min, max, n)。"""
    vals = [r[key] for r in rows[skip:] if r.get(key) is not None]
    if not vals:
        return None
    return (st.mean(vals), min(vals), max(vals), len(vals))


def fmt(v):
    if v is None:
        return "     n/a"
    return f"{v[0]:8.1f} [{v[1]:.0f}-{v[2]:.0f}] n={v[3]}"


def main():
    a = parse(sys.argv[1])
    b = parse(sys.argv[2])
    print(f"Leg A 样本数={len(a)}  时间 {a[0]['ts']} .. {a[-1]['ts']}")
    print(f"Leg B 样本数={len(b)}  时间 {b[0]['ts']} .. {b[-1]['ts']}")
    print("（统计丢弃每条腿前 3 个样本以避开启动瞬态）\n")
    keys = list(FIELDS) + COLS
    print(f"{'指标':<22}{'Leg A 均值[min-max]':<34}{'Leg B 均值[min-max]':<34}{'B-A':>10}{'变化%':>10}")
    for k in keys:
        va, vb = agg(a, k), agg(b, k)
        if va is None or vb is None:
            print(f"{k:<22}{fmt(va):<34}{fmt(vb):<34}{'':>10}{'':>10}")
            continue
        d = vb[0] - va[0]
        pct = (d / va[0] * 100) if va[0] else float("nan")
        print(f"{k:<22}{fmt(va):<34}{fmt(vb):<34}{d:>10.1f}{pct:>9.1f}%")
    print()
    # 主判据的判定
    na, nb = agg(a, "big1mb_n"), agg(b, "big1mb_n")
    ma, mb = agg(a, "big1mb_mb"), agg(b, "big1mb_mb")
    aa, ab = agg(a, "anon_mb"), agg(b, "anon_mb")
    if all([na, nb, ma, mb, aa, ab]):
        print("主判据（≥1MB 匿名映射）：")
        print(f"  数量  {na[0]:.1f} -> {nb[0]:.1f}  ({(nb[0]-na[0])/na[0]*100:+.1f}%)")
        print(f"  总大小 {ma[0]:.1f}MB -> {mb[0]:.1f}MB  ({(mb[0]-ma[0])/ma[0]*100:+.1f}%)")
        print(f"  匿名总量 {aa[0]:.1f}MB -> {ab[0]:.1f}MB  ({(ab[0]-aa[0])/aa[0]*100:+.1f}%)")
        print()
        drop = (ab[0] - aa[0]) / aa[0] * 100
        print(f"判定：非堆匿名总量变化 {drop:+.1f}%（阈值：≤-10% 才算『有效』）")


main()
