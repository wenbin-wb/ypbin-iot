#!/usr/bin/env python3
"""提交前凭据扫描：用 deploy/.env 里**所有**凭据值逐个比对目标目录里的每个文件。

只输出：文件名 / 命中的键名 / 命中次数 / 该值的长度与 sha256[:16]。
**绝不打印凭据值本身。**
用法: scan_credentials.py <env-file> <target-dir>
"""
import hashlib
import os
import pathlib
import re
import sys

ENVF = pathlib.Path(sys.argv[1])
TARGET = pathlib.Path(sys.argv[2])
PAT = re.compile(r"(PASSWORD|PASSWD|TOKEN|SECRET|_KEY|KEY_)")

secrets = {}
for ln in ENVF.read_text(encoding="utf-8").splitlines():
    if not ln or ln.startswith("#") or "=" not in ln:
        continue
    k, v = ln.split("=", 1)
    k, v = k.strip(), v.strip().strip('"').strip("'")
    if PAT.search(k.upper()) and len(v) >= 6:
        secrets[k] = v

print(f"从 {ENVF} 读取到 {len(secrets)} 个候选凭据值（只显示键名/长度/指纹）：")
for k, v in sorted(secrets.items()):
    print(f"  {k:32s} len={len(v):3d} sha256[:16]={hashlib.sha256(v.encode()).hexdigest()[:16]}")
print()

hits = 0
scanned = 0
for p in sorted(TARGET.rglob("*")):
    if not p.is_file():
        continue
    scanned += 1
    try:
        text = p.read_text(encoding="utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        print(f"  !! 无法读取 {p}: {e}")
        continue
    for k, v in secrets.items():
        c = text.count(v)
        if c:
            hits += 1
            print(f"  **命中** {p}  key={k}  count={c}  (值 len={len(v)} sha256[:16]={hashlib.sha256(v.encode()).hexdigest()[:16]})")

print()
print(f"扫描文件数 = {scanned}；凭据命中条目数 = {hits}")

# 额外的形态扫描：疑似 32/64 位 hex 与长 base64（只报文件与计数，不报值）
print()
print("形态扫描（仅计数，不打印命中内容）：")
susp = 0
for p in sorted(TARGET.rglob("*")):
    if not p.is_file():
        continue
    try:
        text = p.read_text(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        continue
    hex32 = len(re.findall(r"\b[0-9a-f]{32}\b", text))
    hex64 = len(re.findall(r"\b[0-9a-f]{64}\b", text))
    b64 = len(re.findall(r"\b[A-Za-z0-9+/]{40,}={0,2}\b", text))
    if hex32 or hex64 or b64:
        susp += 1
        print(f"  {p}: 32hex={hex32} 64hex={hex64} 长base64={b64}")
if not susp:
    print("  （无）")
sys.exit(1 if hits else 0)
