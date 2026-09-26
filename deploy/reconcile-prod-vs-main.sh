#!/usr/bin/env bash
# reconcile-prod-vs-main.sh —— 生产机「已安装」单元/脚本 与仓库版本 的**漂移对账**（只读）
#
# 为什么需要它：生产**不是**从仓库部署 `deploy/` —— 那些脚本/单元是**手工 install** 到
#   `/usr/local/sbin/` 与 `/etc/systemd/system/` 的（见 PROD-OPS-NOTES §8「已知漂移」）。
#   于是"仓库里的版本"与"机器上跑的版本"可能悄悄分叉，而**没有任何东西会告诉你**。
#
# 两种模式（**自动选择**）：
#   ① 清单模式（**默认、离线可用**）：读 `deploy/prod-install-manifest.sha256`（与代码同一次提交生成），
#      逐文件比 sha256。这是本机唯一可行的方式 —— 生产机**连不上 GitHub**。
#   ② git 模式（备用）：若清单不存在且本地 ref **确实包含**目标文件，则与 `git show <REF>:<path>` 比。
#      ⚠️ 若 ref 过旧/离线取不到文件，**必须报「无法判定」而不是 DRIFT**（否则会产生一片假红）。
#
# 用法（生产机 root）：bash /usr/local/sbin/reconcile-prod-vs-main.sh
#   退出码：0 = 全部一致；1 = 有漂移；2 = 有无法判定的条目（清单缺失 / ref 过旧）
set -uo pipefail
REPO="${REPO:-/opt/ypbin/ypbin-iot}"
REF="${REF:-origin/main}"
MANIFEST="${MANIFEST:-$REPO/deploy/prod-install-manifest.sha256}"

drift=0; unknown=0; same=0

echo "=== 对账：已安装文件 vs 仓库版本 @ $(date -u +%FT%TZ) ==="

if [ -r "$MANIFEST" ]; then
  echo "  模式：**清单**（$MANIFEST）"
  echo "  清单源：$(grep -c '^/usr\|^/etc' "$MANIFEST" 2>/dev/null || echo 0) 条"
  echo
  printf '  %-10s %-46s %s\n' 状态 已安装路径 仓库路径
  while read -r sha inst rel; do
    case "$sha" in ''|'#'*) continue ;; esac
    if [ ! -e "$inst" ]; then
      printf '  %-10s %-46s %s\n' "缺失" "$inst" "$rel"; drift=$((drift+1)); continue
    fi
    a="$(sha256sum "$inst" | cut -d' ' -f1)"
    if [ "$a" = "$sha" ]; then
      printf '  %-10s %-46s %s\n' "SAME" "$inst" "$rel"; same=$((same+1))
    else
      printf '  %-10s %-46s %s  (装=%s 清单=%s)\n' "**DRIFT**" "$inst" "$rel" "${a:0:16}" "${sha:0:16}"
      drift=$((drift+1))
    fi
  done < "$MANIFEST"
elif [ -d "$REPO/.git" ]; then
  echo "  模式：git（清单不存在；REF=$REF）"
  echo "  ⚠️ 生产机连不上 GitHub ⇒ 只能与**本地已有的** $REF 比；若它过旧会全部无法判定。"
  echo
  head_sha="$(git -C "$REPO" rev-parse --short "$REF" 2>/dev/null || echo '?')"
  echo "  $REF = $head_sha"
  echo
  printf '  %-12s %-46s %s\n' 状态 已安装路径 仓库路径
  while IFS='|' read -r inst rel; do
    [ -n "$inst" ] || continue
    if [ ! -e "$inst" ]; then printf '  %-12s %-46s %s\n' "缺失" "$inst" "$rel"; drift=$((drift+1)); continue; fi
    if ! git -C "$REPO" cat-file -e "$REF:$rel" 2>/dev/null; then
      printf '  %-12s %-46s %s\n' "无法判定" "$inst" "$rel"; unknown=$((unknown+1)); continue
    fi
    a="$(sha256sum "$inst" | cut -d' ' -f1)"
    b="$(git -C "$REPO" show "$REF:$rel" | sha256sum | cut -d' ' -f1)"
    if [ "$a" = "$b" ]; then printf '  %-12s %-46s %s\n' "SAME" "$inst" "$rel"; same=$((same+1))
    else printf '  %-12s %-46s %s  (装=%s 仓=%s)\n' "**DRIFT**" "$inst" "$rel" "${a:0:16}" "${b:0:16}"; drift=$((drift+1)); fi
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
else
  echo "  !! 既没有清单（$MANIFEST）也没有仓库检出（$REPO/.git）⇒ **无法对账**"
  exit 2
fi

echo
echo "  小结：SAME=$same  DRIFT=$drift  无法判定=$unknown"
echo
echo "=== 未纳入对账的（生产专有 / 非仓库资产，属正常） ==="
printf '  %s\n' \
  "deploy/docker-compose.override.yml（生产专有，含生产键；仓库只留历史档 *.mem-20260926）" \
  "deploy/docker-compose.yml（生产版本比仓库新）" \
  "deploy/.env（凭据，不入库）"

echo
if [ "$drift" -gt 0 ]; then
  echo "判定：**有漂移**（见上）。同步方式：从仓库取对应文件 install 到原路径 → systemctl daemon-reload。"
  echo "       若你刚改过这些文件，请先在仓库重跑 deploy/gen-prod-install-manifest.sh 并提交，再对账。"
  exit 1
fi
if [ "$unknown" -gt 0 ]; then
  echo "判定：无漂移，但有 $unknown 条**无法判定**（ref 过旧/离线）⇒ 用清单模式，或先在仓库生成并同步 manifest。"
  exit 2
fi
echo "判定：**全部一致**（已安装 == 清单；清单与代码同一次提交）"
exit 0
