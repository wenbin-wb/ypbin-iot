#!/usr/bin/env bash
# ============================================================
# 身份模式配置齐备性校验（#6b 部署阻塞面的机器守门）
#
# 背景（一手核实，2026-09-29）：
#   ypbin-starter 3.6.0 起引入 SF-5 入口侧校验，`cn.ypbin.starter.security.identity.
#   IdentityAutoConfiguration#identityHeaderFilterRegistration` 在
#   `ypbin.security.identity.enabled=true` 且 `ypbin.security.identity.trusted-source-token`
#   为空时抛 IllegalStateException ⇒ **应用启动即失败**。
#
# 为什么需要本脚本：
#   本仓 `deploy/nacos/ypbin-common.yaml` 显式开了 identity（enabled=true），
#   却又只配置了**旧命名空间**的 `ypbin.cloud.feign.trusted-source-token`
#   （那是 Feign 出站透传开关，不是入口侧校验密钥）。两者名字相似、位置不同，
#   一旦漏配，症状是"服务起不来"，且只在**真正部署时**才暴露
#   ——本仓升级到 3.6.0 后恰恰长期没部署过，直到上一批才发现。
#   部署链路上 `install.sh` 会把 `deploy/nacos/*.yaml` **整体覆盖**到 Nacos，
#   所以"只在运行中的 Nacos 上手工补键"必然在下次重跑 install.sh 时退化。
#   本脚本把该约束钉在**仓内模板**上，让回归在 CI 就红，而不是在生产上炸。
#
# 校验的是「渲染后的配置」（用占位符替换成假值），而不是模板文本本身，
# 以免"注释里写了键名"被误判为已配置。
#
# 用法：tools/check-identity-config.sh [仓库根]
# ============================================================
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
NACOS_DIR="$ROOT/deploy/nacos"

# 具名常量（杜绝魔法值）
COMMON_CFG="ypbin-common.yaml"
GATEWAY_CFG="ypbin-gateway.yaml"
# 与 install.sh 的 sed 替换键名一致
PLACEHOLDER_SIGN='${GATEWAY_SIGN_TOKEN}'
PLACEHOLDER_INTERNAL='${INTERNAL_TOKEN}'
PLACEHOLDER_DB='${MYSQL_ROOT_PASSWORD}'
PLACEHOLDER_REDIS='${REDIS_PASSWORD}'
FAKE_SIGN='fake-gateway-sign-token-for-check'

fail=0
err() { echo "::error::$*" >&2; fail=1; }
info() { echo "  $*"; }

for f in "$COMMON_CFG" "$GATEWAY_CFG"; do
  [ -f "$NACOS_DIR/$f" ] || { err "缺少配置模板 deploy/nacos/$f"; }
done
[ "$fail" -eq 0 ] || exit 1

# install.sh 的渲染口径：只替换**非注释行**（注释里的占位符只是文档写法）。
# 这里复刻同一口径，保证校验对象 = 真正会被导入 Nacos 的内容。
render() {
  sed -e "/^[[:space:]]*#/! s|${PLACEHOLDER_SIGN}|${FAKE_SIGN}|g" \
      -e "/^[[:space:]]*#/! s|${PLACEHOLDER_INTERNAL}|fake-internal-token|g" \
      -e "/^[[:space:]]*#/! s|${PLACEHOLDER_DB}|fake-db-pass|g" \
      -e "/^[[:space:]]*#/! s|${PLACEHOLDER_REDIS}|fake-redis-pass|g" \
      "$1"
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
render "$NACOS_DIR/$COMMON_CFG" > "$TMP_DIR/$COMMON_CFG"
render "$NACOS_DIR/$GATEWAY_CFG" > "$TMP_DIR/$GATEWAY_CFG"

# 用 Python 解析 YAML 取**真实结构**（不靠 grep 文本匹配：缩进写错、键写进注释、
# 键名拼错（如 trusted_source_token / trustedSourceToken）都会被 grep 放过，被解析器抓住）。
python3 - "$TMP_DIR" "$COMMON_CFG" "$GATEWAY_CFG" "$FAKE_SIGN" <<'PY'
import json
import re
import sys

tmp_dir, common_name, gateway_name, fake_sign = sys.argv[1:5]

try:
    import yaml
except ImportError:
    print("::error::需要 PyYAML 来解析 Nacos YAML（CI 用 actions/setup-python + pip install pyyaml）")
    sys.exit(2)

def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return yaml.safe_load(fh)
    except Exception as exc:  # noqa: BLE001 - 解析失败必须出声（禁静默）
        print(f"::error file={path}::YAML 解析失败：{exc}")
        sys.exit(2)

def assert_no_duplicate_keys(path, name):
    """YAML 顶层/次级重复键是**静默失效**重灾区：PyYAML 与 Spring 的绑定都取后者，
    前者被无声丢弃。本脚本编写时即踩过——新增的 `ypbin:` 块被文件末尾既有的同键块
    整体覆盖，配置看起来"写了"但实际完全不生效。故显式检出。"""
    seen, dup = set(), []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            # 只关心**顶层**键（列 0 起、非注释、形如 `key:`）
            m = re.match(r"^([A-Za-z0-9_.-]+):(\s|$)", line)
            if not m:
                continue
            key = m.group(1)
            if key in seen:
                dup.append(f"{key}（第 {lineno} 行重复）")
            seen.add(key)
    if dup:
        print(
            f"::error file={name}::顶层重复键 {dup} —— YAML 取后一个，前一个被静默丢弃"
            "（配置「写了但不生效」）。"
        )
        return 1
    return 0

common_raw = f"{tmp_dir}/{common_name}"
gateway_raw = f"{tmp_dir}/{gateway_name}"
dup_fail = 0
dup_fail += assert_no_duplicate_keys(common_raw, "deploy/nacos/" + common_name)
dup_fail += assert_no_duplicate_keys(gateway_raw, "deploy/nacos/" + gateway_name)
if dup_fail == 0:
    print("[ok] 无顶层重复键（避免「写了但不生效」）")

common = load(common_raw) or {}
gateway = load(gateway_raw) or {}

def dig(doc, *keys):
    cur = doc
    for k in keys:
        if not isinstance(cur, dict) or k not in cur:
            return None, False
        cur = cur[k]
    return cur, True

fail = 0
fail += dup_fail

# ---- 断言 1：identity.enabled=true 时，入口侧密钥必须存在且非空 ----
enabled, has_enabled = dig(common, "ypbin", "security", "identity", "enabled")
entry_token, has_entry = dig(common, "ypbin", "security", "identity", "trusted-source-token")

if enabled is True:
    if not has_entry or not entry_token:
        print(
            "::error file=deploy/nacos/ypbin-common.yaml::"
            "ypbin.security.identity.enabled=true 但 ypbin.security.identity.trusted-source-token "
            "缺失/为空 —— starter 3.6.0 起这会直接导致启动失败"
            "（IdentityAutoConfiguration 抛 IllegalStateException）。"
            "注意别与 ypbin.cloud.feign.trusted-source-token 混淆：那是 Feign 出站透传开关，"
            "不能替代本键。"
        )
        fail = 1
    elif entry_token != fake_sign:
        print(
            "::error file=deploy/nacos/ypbin-common.yaml::"
            f"identity.trusted-source-token 渲染后为 {entry_token!r}，"
            "而部署侧期望它由 ${GATEWAY_SIGN_TOKEN} 占位符填充（真值只在 deploy/.env，禁入仓）。"
        )
        fail = 1
    else:
        print("[ok] ypbin-common.yaml: ypbin.security.identity.{enabled=true, trusted-source-token=${GATEWAY_SIGN_TOKEN}}")

# ---- 断言 2：网关签发侧密钥必须存在且是同一占位符（=同串来源）----
gw_token, has_gw = dig(gateway, "ypbin", "gateway", "auth", "trusted-source-token")
if not has_gw or not gw_token:
    print(
        "::error file=deploy/nacos/ypbin-gateway.yaml::"
        "ypbin.gateway.auth.trusted-source-token 缺失 —— 网关不会签发 X-Gateway-Signed 标记，"
        "下游 IdentityHeaderFilter 会把所有经网关的请求判为非法来源并拒绝（全站 403）。"
    )
    fail = 1
elif gw_token != fake_sign:
    print(
        "::error file=deploy/nacos/ypbin-gateway.yaml::"
        f"网关签发侧渲染后为 {gw_token!r}，应以 ${{GATEWAY_SIGN_TOKEN}} 填充。"
    )
    fail = 1
else:
    print("[ok] ypbin-gateway.yaml: ypbin.gateway.auth.trusted-source-token 与下游同用 ${GATEWAY_SIGN_TOKEN}")

# ---- 断言 3：两处必须是同一来源占位符（否则「配了但不同串」= 全站 403）----
if enabled is True and has_entry and has_gw:
    if entry_token != gw_token:
        print(
            "::error::网关签发侧与下游入口侧的 trusted-source-token **不同串**："
            "下游会拒绝一切经网关的合法请求（表现为全站 403），且日志刷「非法身份来源」。"
        )
        fail = 1
    else:
        print("[ok] 网关签发侧与下游入口侧同串（同渲染自 ${GATEWAY_SIGN_TOKEN}）")

# ---- 断言 4：网关侧 trusted-source-header（若显式配置）必须与下游默认头名一致 ----
gw_header, has_gw_header = dig(gateway, "ypbin", "gateway", "auth", "trusted-source-header")
dn_header, has_dn_header = dig(common, "ypbin", "security", "identity", "trusted-source-header")
if has_gw_header or has_dn_header:
    eff_gw = gw_header if has_gw_header else "X-Gateway-Signed"
    eff_dn = dn_header if has_dn_header else "X-Gateway-Signed"
    if eff_gw != eff_dn:
        print(
            f"::error::签名头名不一致：网关签发 {eff_gw!r}，下游校验 {eff_dn!r} ⇒ 全站 403。"
        )
        fail = 1
    else:
        print(f"[ok] 签名头名一致：{eff_gw}")

# ---- 断言 5：网关清洗表必须覆盖「全部身份头 + 来源标记头」（SF-5 纵深防御）----
# starter 默认表不含 X-Gateway-Signed（GatewayProperties$HeaderSanitize:180-181），
# 且该键一旦显式设置是**整体覆盖**（不是追加）⇒ 必须逐项校验，漏一个就是静默取消清洗。
IDENTITY_HEADERS = ["X-User-Id", "X-User-Name", "X-Tenant-Id", "X-Dept-Id", "X-Roles",
                    "X-Gateway-Signed"]
san, has_san = dig(gateway, "ypbin", "gateway", "header-sanitize", "headers")
if not has_san:
    print(
        "::error file=deploy/nacos/ypbin-gateway.yaml::"
        "未配置 ypbin.gateway.header-sanitize.headers ⇒ 走 starter 默认表，"
        "其中**不含 X-Gateway-Signed**：客户端可自带该头穿透到下游（SF-5 纵深防御缺口）。"
        "请显式列出默认 5 个身份头 + X-Gateway-Signed。"
    )
    fail = 1
elif not isinstance(san, list):
    print("::error file=deploy/nacos/ypbin-gateway.yaml::header-sanitize.headers 必须是列表")
    fail = 1
else:
    lowered = {str(h).strip().lower() for h in san}
    missing = [h for h in IDENTITY_HEADERS if h.lower() not in lowered]
    if missing:
        print(
            "::error file=deploy/nacos/ypbin-gateway.yaml::"
            f"header-sanitize.headers 缺少 {missing} —— 该键是**整体覆盖**语义（非追加），"
            "漏项等于取消对该头的清洗。"
        )
        fail = 1
    else:
        print(f"[ok] 网关清洗表覆盖 {len(IDENTITY_HEADERS)} 个身份/标记头（含 X-Gateway-Signed）")

# ---- 断言 6：清洗不得被整块关掉（enabled=false ⇒ 过滤器根本不装配）----
# 独立复核（2026-09-29）发现的缺口：`ypbin.gateway.header-sanitize.enabled` 是
# **条件装配开关**（GatewayAutoConfiguration:64 的 @ConditionalOnProperty(havingValue="true",
# matchIfMissing=true)）⇒ 一旦显式写成 false，HeaderSanitizeGlobalFilter **整个 bean 都不装配**，
# 清洗静默消失、身份头全部裸奔。断言 5 只看 headers 列表，会放过这种写法。
san_enabled, has_san_enabled = dig(gateway, "ypbin", "gateway", "header-sanitize", "enabled")
if has_san_enabled and san_enabled is not True:
    print(
        "::error file=deploy/nacos/ypbin-gateway.yaml::"
        f"ypbin.gateway.header-sanitize.enabled={san_enabled!r} ⇒ HeaderSanitizeGlobalFilter "
        "根本不会被装配（@ConditionalOnProperty havingValue=true），外部可自带 "
        "X-User-Id/X-Gateway-Signed 等头直达下游。清洗不可关闭。"
    )
    fail = 1
else:
    print("[ok] 网关清洗已启用（enabled 未显式关闭）")

sys.exit(1 if fail else 0)
PY
