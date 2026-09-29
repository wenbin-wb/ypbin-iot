#!/usr/bin/env bash
# =============================================================================
# gen-certs.sh —— 为 EMQX 8883（MQTT over TLS）生成**自签 CA + 服务端证书**（幂等）
# =============================================================================
# 在**中间件机**上运行（产物落 /opt/emqx/certs，宿主侧 700/600，只读挂载进容器）。
# 设计依据：docs/EMQX-DEPLOY.md §3.2（TLS 拓扑 / 证书方案 / 生产替换步骤）。
#
# 🔴🔴 **自签证书仅限开发测试期使用，不得用于生产**：
#   · 客户端默认**不信任**该 CA ⇒ 真设备必须显式导入 ca.crt（或 --insecure 关校验，
#     而 --insecure 等于放弃服务端身份验证 = 可被中间人替换，**绝不可**用于生产）；
#   · 自签 CA 的私钥就在本机 ⇒ 一旦泄露可签发任意"合法"证书；
#   · 无吊销（CRL/OCSP）、无自动轮换、有效期需人工看守。
#   ⇒ **生产必须换正式 CA（如 Let's Encrypt / 云厂商 DV 证书）签发的服务端证书**，
#     替换步骤见 docs/EMQX-DEPLOY.md §3.2「从自签换成正式 CA 证书」。本脚本产出的文件
#     与正式证书**同名同路径**，替换时只换文件、不改 EMQX 配置。
#
# 幂等语义（**重要，别读错**）：
#   · 目标文件全部存在且 `--force` 未给 ⇒ **什么都不做**并退出 0（不会静默换证书）；
#   · 要轮换/重签 ⇒ 显式 `--force`（旧文件被覆盖，CA 也重签）。
#   ⇒ 也就是说：本脚本**不会**在证书过期时自动续期；到期前由巡检告警（emqx-tls-selfcheck.sh
#     的 T5 / emqx-mqtt-expose-watch.sh），人工决定重签。
#
# 用法（**在中间件机上**）：
#   bash gen-certs.sh                 # 首次生成；已存在则跳过
#   bash gen-certs.sh --force         # 强制重签（含 CA）—— 客户端需重新导入 ca.crt
#   bash gen-certs.sh --days 825 --san-ip 43.242.200.8,10.0.0.5
#
# 退出码：0 = 证书就绪（新建或已存在）；1 = 失败
# =============================================================================
set -uo pipefail

CERT_DIR="${CERT_DIR:-/opt/emqx/certs}"
# 有效期（天）。825 ≈ 27 个月，单张叶子证书的通行上限口径；自签下无 CA/B 论坛约束，
# 但刻意取这个值以免日后有人误以为"自签就可以签 10 年"。
DAYS="${DAYS:-825}"
# SAN：**公网 IP 必含**，否则客户端按 IP 连接时主机名校验必失败（官方文档亦明示 CN 不匹配需 --insecure）。
# 中间件机还有 172.28.0.1（emqx-edge 网桥网关）与容器名/容器 FQDN；
# 同网桥内的**其它容器**按容器名连它时，需要的正是 ypbin-emqx / ypbin-emqx.local。
SAN_IP="${SAN_IP:-43.242.200.8,172.28.0.1}"
SAN_DNS="${SAN_DNS:-ypbin-emqx,ypbin-emqx.local,localhost}"
KEY_BITS="${KEY_BITS:-rsa:2048}"
SUBJ_CA="${SUBJ_CA:-/CN=ypbin-EMQX-SelfSigned-CA/O=ypbin-dev-test}"
SUBJ_SRV="${SUBJ_SRV:-/CN=43.242.200.8/O=ypbin-dev-test}"
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --force)   FORCE=1; shift ;;
    --days)    DAYS="$2"; shift 2 ;;
    --san-ip)  SAN_IP="$2"; shift 2 ;;
    --san-dns) SAN_DNS="$2"; shift 2 ;;
    --dir)     CERT_DIR="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

CERT="$CERT_DIR/server.crt"
KEY="$CERT_DIR/server.key"
CA_CERT="$CERT_DIR/ca.crt"
CA_KEY="$CERT_DIR/ca.key"
CSR="$CERT_DIR/server.csr"
EXT="$CERT_DIR/server.ext"

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info(){ printf '  ·  %s\n' "$1"; }

command -v openssl >/dev/null 2>&1 || { echo "找不到 openssl" >&2; exit 1; }

echo "=== 自签证书生成（$CERT_DIR）==="

# ── 幂等闸门 ─────────────────────────────────────────────────────────────────
if [ "$FORCE" -eq 0 ] && [ -s "$CERT" ] && [ -s "$KEY" ] && [ -s "$CA_CERT" ]; then
  ok "证书已存在，跳过生成（要重签请显式 --force）"
  info "服务端证书到期：$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)"
  openssl x509 -in "$CERT" -noout -checkend 0 >/dev/null 2>&1 \
    && ok "当前证书未过期" \
    || bad "当前证书**已过期** ⇒ 需要 --force 重签（并同步更新客户端信任的 ca.crt）"
  exit "$fail"
fi

# ── 落目录（700：私钥所在目录不给同机其它用户遍历）────────────────────────────
# ⚠️ 该机还有别人的项目，权限宁紧勿松：700 目录 + 600 文件；
#    容器内以 uid 1000(emqx) 读 → 属主设为 EMQX_UID（默认 1000）。
EMQX_UID="${EMQX_UID:-1000}"
install -d -m 700 "$CERT_DIR" || { echo "建 $CERT_DIR 失败" >&2; exit 1; }
umask 077

# ── SAN 扩展文件（**不写进 openssl.cnf，避免动系统配置**）─────────────────────
{
  echo "basicConstraints = CA:FALSE"
  echo "keyUsage = critical, digitalSignature, keyEncipherment"
  echo "extendedKeyUsage = serverAuth"
  echo "subjectKeyIdentifier = hash"
  echo "authorityKeyIdentifier = keyid,issuer"
  echo "subjectAltName = @alt_names"
  echo
  echo "[alt_names]"
  n=1
  IFS=',' read -ra ips <<<"$SAN_IP"
  for ip in "${ips[@]}"; do [ -n "$ip" ] && echo "IP.$n = $ip" && n=$((n+1)); done
  n=1
  IFS=',' read -ra dns <<<"$SAN_DNS"
  for d in "${dns[@]}"; do [ -n "$d" ] && echo "DNS.$n = $d" && n=$((n+1)); done
} >"$EXT" || { echo "写 SAN 扩展失败" >&2; exit 1; }
chmod 600 "$EXT"

# ── CA（自签）────────────────────────────────────────────────────────────────
openssl req -x509 -newkey "$KEY_BITS" -nodes -sha256 -days "$DAYS" \
  -subj "$SUBJ_CA" -keyout "$CA_KEY" -out "$CA_CERT" >/dev/null 2>&1 \
  && ok "自签 CA 已生成（ca.crt / ca.key）" \
  || { bad "CA 生成失败"; exit 1; }

# ── 服务端私钥 + CSR + 签发 ──────────────────────────────────────────────────
openssl req -newkey "$KEY_BITS" -nodes -sha256 -subj "$SUBJ_SRV" \
  -keyout "$KEY" -out "$CSR" >/dev/null 2>&1 \
  && ok "服务端私钥 + CSR 已生成" \
  || { bad "服务端 CSR 生成失败"; exit 1; }

openssl x509 -req -in "$CSR" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$CERT" -days "$DAYS" -sha256 -extfile "$EXT" >/dev/null 2>&1 \
  && ok "服务端证书已签发（$DAYS 天）" \
  || { bad "签发失败"; exit 1; }

# 服务端证书链：单层（自签 CA 直签），故 server.crt 本身即完整链；
# 若将来换成中间 CA，需按官方口径把中间证书**追加**在 server.crt 之后（官方 Listener 文档原文）。
rm -f "$CSR" "$CERT_DIR/ca.srl"

chmod 600 "$KEY" "$CA_KEY" "$CA_CERT" "$CERT"
chown "$EMQX_UID:$EMQX_UID" "$KEY" "$CA_KEY" "$CA_CERT" "$CERT" 2>/dev/null \
  && ok "属主已设为 $EMQX_UID（容器内 emqx 用户）" \
  || info "chown 失败（容器内可能读不到私钥；请确认 EMQX_UID）"
chmod 700 "$CERT_DIR"

echo
echo "=== 产物（**不打印私钥内容**）==="
ls -la "$CERT_DIR"
echo
echo "  服务端证书指纹(SHA256)：$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"
echo "  CA 证书指纹(SHA256)   ：$(openssl x509 -in "$CA_CERT" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"
echo "  有效期至             ：$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)"
echo "  SAN                  ：$(openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | tail -1 | sed 's/^ *//')"
echo
echo "  ⚠️ 自签仅开发测试期可用；生产必须换正式 CA 证书（docs/EMQX-DEPLOY.md §3.2）。"
echo "  ⚠️ 客户端要连 8883 必须导入：$CA_CERT（否则会因不受信任而失败）。"
exit "$fail"
