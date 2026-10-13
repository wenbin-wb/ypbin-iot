#!/usr/bin/env bash
# =============================================================================
# 生成 EMQX 实验床自签 CA + 服务端证书（issue #218 自签演练；仅实验用）
# 用法：bash gen-lab-certs.sh [输出目录，默认 ./certs]
# =============================================================================
set -euo pipefail
OUT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/certs}"
mkdir -p "$OUT"
SAN="subjectAltName=DNS:emqx-lab-1.local,DNS:emqx-lab-2.local,IP:172.29.0.2,IP:172.29.0.3,IP:127.0.0.1"

if [ ! -f "$OUT/ca.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$OUT/ca.key" -out "$OUT/ca.crt" \
    -subj "/CN=ypbin-EMQX-Lab-SelfSigned-CA/O=ypbin-lab" 2>/dev/null
fi
openssl req -newkey rsa:2048 -nodes -keyout "$OUT/server.key" -out "$OUT/server.csr" \
  -subj "/CN=emqx-lab-1.local/O=ypbin-lab" 2>/dev/null
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
  -CAcreateserial -out "$OUT/server.crt" -days 825 -extfile <(printf "%s\n" "$SAN") 2>/dev/null
rm -f "$OUT/server.csr"
# 实验容器以 uid 1000(emqx) 运行：目录可进入、证书可读、私钥仅属主(1000)可读。
# （这正是生产 8883 失效的根因类别：宿主 certs 若 root:root 700/600，容器读不到 → 握手失败）
chmod 755 "$OUT"; chmod 644 "$OUT/ca.crt" "$OUT/server.crt"; chmod 600 "$OUT/server.key" "$OUT/ca.key"
if [ "$(id -u)" = "0" ]; then chown -R 1000:1000 "$OUT"; fi
echo "generated in $OUT:"
ls -l "$OUT"
openssl x509 -in "$OUT/server.crt" -noout -subject -issuer -dates -ext subjectAltName
