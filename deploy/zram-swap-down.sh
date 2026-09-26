#!/usr/bin/env bash
# zram-swap-down.sh —— 回滚/停止 zram swap（把机器还原成 swap=0 的原状）
#
# ═══════════════════════════════════════════════════════════════════════════════
# 【swapoff 安全规则 —— 硬性，勿绕过】
# ═══════════════════════════════════════════════════════════════════════════════
# `swapoff` 会把 zram 里的页**全部解压换回物理内存**：需要一次性容纳
#     orig_data_size = /sys/block/zram0/mm_stat 第 1 列（**未压缩**总字节数）
# 的匿名内存。zram 自身占用的 mem_used_total（第 3 列）虽然会被释放，
# 但**按最坏情况不计入收益**（压缩数据与解压后的页可能短暂共存）。
# 因此判定式（唯一允许 swapoff 的条件）：
#
#         MemAvailable  ≥  orig_data_size  +  余量
#
# 不满足则**禁止 swapoff** —— 否则会长时间卡死、甚至触发 OOM
# （这正是「swap 把硬 OOM 变成慢卡死」的另一面）。
# 余量默认 256MiB，可用环境变量 ZRAM_SWAPOFF_MARGIN（支持 K/M/G 后缀）覆盖。
#
# 运行模式：
#   默认         判定不通过 ⇒ **拒绝执行**并以退出码 3 结束（人工回滚用，安全优先）
#   --force      跳过判定（自行承担卡死/OOM 风险；仅在你明确知道自己在做什么时用）
#   --shutdown   供 systemd 单元 ExecStop 使用：判定不通过 ⇒ 只告警并以 0 退出
#                （避免把"内存贴水位时的关机"变成长时间卡住或被标记为关机失败）
#   --check      只做判定与打印，不改任何状态（退出码同默认模式）
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail
DEV=/dev/zram0
MMSTAT=/sys/block/zram0/mm_stat
MARGIN="${ZRAM_SWAPOFF_MARGIN:-256M}"
MODE="${1:-}"

log() { printf '[zram-swap-down] %s\n' "$*"; }
warn() { printf '[zram-swap-down][WARN] %s\n' "$*" >&2; }

to_bytes() { # 1G/256M/1024K/1234 -> bytes
  local v="$1" n u
  n="${v//[^0-9]/}"; u="${v//[0-9]/}"
  case "${u:-}" in
    ""|B|b) printf '%s' "$n" ;;
    K|k) printf '%s' "$((n * 1024))" ;;
    M|m) printf '%s' "$((n * 1024 * 1024))" ;;
    G|g) printf '%s' "$((n * 1024 * 1024 * 1024))" ;;
    *) echo "无法解析容量: $v" >&2; return 2 ;;
  esac
}

[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }

if [ ! -e "$DEV" ]; then log "$DEV 不存在，无需回滚"; exit 0; fi

# ── 读取 zram 统计与系统可用内存 ───────────────────────────────────────────────
orig=0
if [ -r "$MMSTAT" ]; then
  log "回滚前 zram 统计 (orig_data_size compr_data_size mem_used_total mem_limit mem_used_max ...):"
  cat "$MMSTAT"
  orig=$(awk '{print $1}' "$MMSTAT")
fi
avail_kb=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
avail=$((avail_kb * 1024))
margin=$(to_bytes "$MARGIN")
need=$((orig + margin))

log "安全判定：MemAvailable=$((avail / 1024 / 1024))MiB  orig_data_size=$((orig / 1024 / 1024))MiB  " \
    "余量=$((margin / 1024 / 1024))MiB  ⇒ 需要 ≥ $((need / 1024 / 1024))MiB"

safe=0
if [ "$avail" -ge "$need" ]; then safe=1; fi

if [ "$safe" -eq 0 ] && [ "$MODE" != "--force" ]; then
  warn "MemAvailable 不足：$((avail / 1024 / 1024))MiB < $((need / 1024 / 1024))MiB"
  warn "按『swapoff 安全规则』**禁止 swapoff**：解压换回的匿名页会撑爆物理内存。"
  warn "处置建议：等内存回落 / 先减负载 / 或明知风险后用 --force 覆盖。"
  if [ "$MODE" = "--shutdown" ]; then
    warn "--shutdown 模式：跳过 swapoff，以 0 退出（不影响关机流程）。"
    exit 0
  fi
  exit 3
fi

if [ "$MODE" = "--check" ]; then log "判定通过（--check：不改任何状态）"; exit 0; fi

# ── 执行回滚 ──────────────────────────────────────────────────────────────────
if swapon --show=NAME --noheadings 2>/dev/null | tr -d ' ' | grep -qx "$DEV"; then
  log "swapoff $DEV（会把这些页换回物理内存，可能需要时间）"
  swapoff "$DEV"
else
  log "$DEV 不是活动 swap"
fi

if [ -w /sys/block/zram0/reset ]; then
  log "reset $DEV（释放 zram 占用的全部内存）"
  echo 1 > /sys/block/zram0/reset
fi

log "modprobe -r zram"
modprobe -r zram 2>/dev/null || log "（模块仍被占用，忽略）"

log "vm.swappiness 运行期还原为内核默认 60"
log "（注意：若 /etc/sysctl.d/99-zram-swap.conf 仍在，重启后会被重新设为 150；"
log "  彻底回滚请见 zram-swap-systemd.md 的『回滚』一节）"
sysctl -w vm.swappiness=60 >/dev/null || true

log "--- 回滚后 ---"
swapon --show || true
free -m
