#!/usr/bin/env bash
# ============================================================
# 废弃配置键门禁（issue #297）
#
# 背景（一手核实，2026-10-18）：
#   #291 把 iot 全量切到 ypbin-starter 4.0.0 / ypbin-iot-starter 0.4.0 时，**配置键也换了命名空间**：
#   `ypbin.internal.token` → `ypbin.security.internal-token.token`（入站守卫）+
#   `ypbin.cloud.feign.internal-token`（出站携带）。但本地 `application.yml` 里还留着旧键，
#   而 starter 4.0.0 与业务仓**都已无任何消费方**（原 InternalProperties 已删）。
#
# 留着废弃键的危害不是「多一行配置」：
#   1) **误导运维**：以为在这里配就能出站携带凭证，实际必须配新键 ⇒ 漏配的后果是 iot 的
#      /internal/** 守卫 **fail-closed 全拒**（租约、读数上报全挂），且症状与「配置正确但服务端
#      没读到」极难区分；
#   2) Nacos 里配了正确的键、本地 yml 里配了错的键，**两处同名不同前缀**，排障时误判；
#   3) 与 SYNC.md / CHANGELOG.md 已登记的「旧键已废弃」相矛盾——文档说废弃、代码里还留着。
#
# 第二类陷阱（真实教训，见 deploy/nacos/ypbin-iot.yaml:29-30 的注释）：
#   Sa-Token 的开关**不在** `ypbin.sa-token.*`，而是 starter 的 `ypbin.security.interceptor`。
#   写成 `ypbin.sa-token.enabled` 是**不存在的键**：不报错、不生效（差分实测：传
#   `ypbin.sa-token.enabled=false` 时 saTokenWebConfigurer 仍 matched）。这类「静默无效」比报错更坏。
#
# 本脚本守的判据：
#   **YAML 解析后的真实键路径**（不是 grep 文本）。原因：grep 会把**注释里**的键名算成「存在」
#   （本仓多个 yaml 特意用注释记录「旧键已废弃」的教训，那正是我们要保留的文档），也会漏掉
#   「键名分两段写」「用 YAML 锚点」等写法。故解析后按键路径比对，注释天然不参与。
#
# 扫描范围：
#   * 仓内所有 `application*.yml` / `bootstrap*.yml` / `application*.properties`
#   * `deploy/nacos/*.yaml`（Nacos 模板；install.sh 会把它们整体覆盖到 Nacos）
#   跳过 `target/`、`node_modules/`、`.git/`。
#
# 用法：
#   bash tools/check-deprecated-config-keys.sh [仓库根]
# 退出码：0 = 无废弃键；1 = 命中废弃键（或配置解析失败——解析失败不静默放行）。
# 依赖：python3 + PyYAML（CI runner 自带 python3；本仓既有门禁 check-identity-config.sh 同样依赖）。
# ============================================================
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
[ -d "$ROOT" ] || { echo "::error::仓库根不存在：$ROOT" >&2; exit 1; }

python3 - "$ROOT" <<'PY'
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("::error::需要 PyYAML 来解析 YAML 配置（CI 用 actions/setup-python + pip install pyyaml）")
    sys.exit(2)

root = pathlib.Path(sys.argv[1]).resolve()

# ---------------------------------------------------------------------------
# 废弃键清单（**唯一来源**：issue #297 登记的两类）
#
# 每项：(键路径元组, 说明)
#   * 键路径按 YAML 解析后的映射层级逐段比对（精确键路径匹配，不做前缀模糊匹配——
#     模糊匹配会误伤 `ypbin.sa-token-x` 这种无关键，也会漏掉「配在更深一层」的真实残留）。
#   * 新增条目前必须确认「已无任何消费方」（starter + 业务仓全仓 grep 无读取方），并在
#     SYNC.md/CHANGELOG.md 登记迁移去向；否则不得加进来（禁把「想禁的键」当「已废弃的键」）。
# ---------------------------------------------------------------------------
DEPRECATED_KEYS = [
    (("ypbin", "internal", "token"),
     "已迁移为 ypbin.security.internal-token.token（入站守卫）+ ypbin.cloud.feign.internal-token"
     "（出站 Feign 携带）；starter 4.0.0 起旧键无任何消费方"),
    (("ypbin", "sa-token"),
     "不存在的命名空间：Sa-Token 开关的真实键是 ypbin.security.interceptor（写 ypbin.sa-token.* 静默无效，"
     "见 deploy/nacos/ypbin-iot.yaml 的差分实测记录）"),
]

SKIP_DIR_NAMES = {"target", "node_modules", ".git", ".flattened", ".mvn"}
# 待扫的配置文件形态（相对仓库根匹配）
CONFIG_GLOBS = [
    "**/application*.yml",
    "**/application*.yaml",
    "**/application*.properties",
    "**/bootstrap*.yml",
    "**/bootstrap*.yaml",
    "deploy/nacos/*.yaml",
]

def describe(key_path):
    return ".".join(key_path)

def walk(node, path, sink):
    """递归收集 (键路径, 值) —— 映射逐层下钻；列表里的映射同样下钻（如 a: [{b: 1}]）。"""
    if isinstance(node, dict):
        for key, value in node.items():
            child = path + (str(key),)
            sink.append((child, value))
            walk(value, child, sink)
    elif isinstance(node, list):
        for item in node:
            walk(item, path, sink)

def is_deprecated(key_path):
    for deprecated, reason in DEPRECATED_KEYS:
        # 精确命中「废弃键本身」或「废弃键之下的任一子键」（`ypbin.sa-token.enabled` 这类整棵子树
        # 都是不存在的命名空间 ⇒ 子树也一并算违规）。
        if key_path[:len(deprecated)] == deprecated:
            return deprecated, reason
    return None

def candidates():
    seen = {}
    for pattern in CONFIG_GLOBS:
        for path in root.glob(pattern):
            if not path.is_file():
                continue
            if any(part in SKIP_DIR_NAMES for part in path.relative_to(root).parts):
                continue
            seen[path.resolve()] = path
    return [seen[key] for key in sorted(seen)]

def properties_pairs(path):
    """application*.properties 的 key=value 展平成键路径（点分）。"""
    pairs = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            continue
        key = line.split("=", 1)[0].strip()
        if key:
            pairs.append((tuple(key.split(".")), lineno, line))
    return pairs

files = candidates()
if not files:
    print("::error::未找到任何配置文件（application*.yml / deploy/nacos/*.yaml）——"
          "扫描范围写错会让门禁永远绿（禁静默降级）")
    sys.exit(1)

print(f"扫描 {len(files)} 份配置文件：")
for path in files:
    print(f"  - {path.relative_to(root)}")

failures = []
checked_keys = 0
for path in files:
    relative = path.relative_to(root).as_posix()
    if path.suffix == ".properties":
        for key_path, lineno, line in properties_pairs(path):
            checked_keys += 1
            hit = is_deprecated(key_path)
            if hit:
                deprecated, reason = hit
                failures.append(
                    f"{relative}:{lineno} → 键 {describe(key_path)} 命中废弃键 "
                    f"{describe(deprecated)}：{reason}"
                )
        continue
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - 解析失败必须出声（禁静默放行）
        failures.append(f"{relative} → YAML 解析失败，无法判定是否含废弃键：{exc}")
        continue
    if document is None:
        continue
    entries = []
    walk(document, (), entries)
    checked_keys += len(entries)
    for key_path, _value in entries:
        hit = is_deprecated(key_path)
        if hit:
            deprecated, reason = hit
            failures.append(
                f"{relative} → 键 {describe(key_path)} 命中废弃键 {describe(deprecated)}：{reason}"
            )

if failures:
    print("")
    print("::error::发现已登记废弃的配置键（配置「写了但不生效」，会误导运维与排障）：")
    for failure in failures:
        print(f"  ✗ {failure}")
    print("")
    print("处置：删除该键，改用替代键（迁移去向见 SYNC.md「2026-10-18 一揽子下沉」与 "
          "CHANGELOG.md 同批次「配置键迁移」条）。若替代键确实不适用，先评估再登记，"
          "不得把已废弃的键留在仓内。")
    sys.exit(1)

print(f"[ok] 无废弃配置键（检查了 {checked_keys} 个键路径，废弃清单 {len(DEPRECATED_KEYS)} 项）")
PY
