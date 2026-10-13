#!/usr/bin/env bash
# =============================================================================
# 安装 actuator-exporter 到本机（systemd）—— issue #222
# 用法：sudo bash install.sh [安装目录，默认 /opt/observability/actuator-exporter]
# 幂等：脚本已在目标目录时（SRC==DEST）跳过自我拷贝，仍会安装/刷新 systemd 单元。
# =============================================================================
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-/opt/observability/actuator-exporter}"

command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }
mkdir -p "$DEST"
if [ "$(readlink -f "$SRC/actuator-exporter.py")" != "$(readlink -f "$DEST/actuator-exporter.py" 2>/dev/null || echo __none__)" ]; then
  install -m 0755 "$SRC/actuator-exporter.py" "$DEST/actuator-exporter.py"
else
  chmod 0755 "$DEST/actuator-exporter.py"
  echo "脚本已在目标目录，跳过拷贝：$DEST/actuator-exporter.py"
fi
if [ ! -f "$DEST/config.json" ]; then
  install -m 0644 "$SRC/config.json" "$DEST/config.json"
else
  echo "保留已存在的 $DEST/config.json（如需更新请手工对比）"
fi
install -m 0644 "$SRC/ypbin-actuator-exporter.service" /etc/systemd/system/ypbin-actuator-exporter.service
systemctl daemon-reload
systemctl enable --now ypbin-actuator-exporter.service
sleep 1
systemctl --no-pager --full status ypbin-actuator-exporter.service | head -12
echo "自检：curl -s http://127.0.0.1:9110/metrics | head"
