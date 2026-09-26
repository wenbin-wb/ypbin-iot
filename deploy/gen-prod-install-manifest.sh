#!/usr/bin/env bash
# gen-prod-install-manifest.sh —— 生成「生产机已安装文件 ↔ 仓库版本」的 sha256 对账清单
#
# 为什么需要**清单**而不是直接 git：
#   生产机 **连不上 GitHub**（实战测试：`git ls-remote origin` 超时），且它那份 `/opt/ypbin/ypbin-iot`
#   检出的 `origin/main` 是**旧 ref**（`ad80e3f`，还没有本轮交付物）⇒ 在机器上"跟 origin/main 比"必然误报。
#   所以对账必须**离线可用**：仓库侧在提交时生成清单，机器侧只做 sha256 比对。
#
# 用法（**仓库根**）：
#   bash deploy/gen-prod-install-manifest.sh > deploy/prod-install-manifest.sha256
# 每次改动这些交付物后**必须重跑**，否则清单会过期并产生假 DRIFT。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1     # → ypbin-iot 仓库根

echo "# 生产机「已安装文件」与仓库版本的 sha256 对账清单（**离线可用**）"
echo "# 生成：bash deploy/gen-prod-install-manifest.sh > deploy/prod-install-manifest.sha256"
echo "# 对账：bash /usr/local/sbin/reconcile-prod-vs-main.sh"
echo "# 格式：<sha256>  <生产机安装路径>  <仓库内路径>"
echo "# 注意：本清单必须与代码**同一次提交**；改动下列任一文件后未重跑本脚本 ⇒ 对账会报假 DRIFT。"
echo "#"
while IFS='|' read -r inst rel; do
  [ -n "$inst" ] || continue
  [ -f "$rel" ] || { echo "# !! 仓库缺文件：$rel" >&2; exit 1; }
  printf '%s  %s  %s\n' "$(sha256sum "$rel" | cut -d' ' -f1)" "$inst" "$rel"
done <<'MAP'
/usr/local/sbin/zram-swap-up.sh|deploy/zram-swap-up.sh
/usr/local/sbin/zram-swap-down.sh|deploy/zram-swap-down.sh
/usr/local/sbin/zram-swap-verify.sh|deploy/zram-swap-verify.sh
/etc/systemd/system/zram-swap.service|deploy/zram-swap.service
/etc/sysctl.d/99-zram-swap.conf|deploy/99-zram-swap.conf
/etc/modules-load.d/zram.conf|deploy/modules-load-zram.conf
/etc/systemd/system/access-tcp-simulator.service|deploy/access-tcp-simulator.service
/usr/local/sbin/access-tcp-simulator.py|docs/tools/access-tcp-simulator.py
/usr/local/sbin/feeder-watch.sh|deploy/feeder-watch.sh
/etc/systemd/system/feeder-watch.service|deploy/feeder-watch.service
/etc/systemd/system/feeder-watch.timer|deploy/feeder-watch.timer
/usr/local/sbin/post-reboot-check.sh|deploy/post-reboot-check.sh
/usr/local/sbin/rollback-asset-inventory.sh|deploy/rollback-asset-inventory.sh
MAP
