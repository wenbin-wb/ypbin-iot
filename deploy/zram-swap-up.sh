#!/usr/bin/env bash
# zram-swap-up.sh —— 在内存紧张的生产机上启用 zram 压缩内存块设备作为 swap
#
# 定位（务必先读）：
#   zram **不是**「拿硬盘当内存」，它**不增加一字节物理内存**。内核文档原文：
#   "Pages written to these disks are compressed and stored in memory itself."
#   （https://docs.kernel.org/admin-guide/blockdev/zram.html，访问 2026-09-26）
#   它的作用是：给内核一条**匿名页的回收路径**（本机原先 swap=0 ⇒ 匿名页完全不可回收，
#   唯一出路是被 OOM killer 杀）。收益来自「冷页换出 → 省下的空间 > 压缩后仍占的空间」，
#   在 2:1 压缩比下净收益约为换出量的 50%。
#
# 依据（内核文档，2026-09-26 访问）：
#   · zram 文档："There is little point creating a zram of greater than twice the size of memory
#     since we expect a 2:1 compression ratio" ⇒ 8GB 物理内存下 2G 是保守取值（远低于 2×RAM 上限）
#   · zram 文档 mem_limit：可限制 zram 为存放压缩数据最多能占用的内存 ⇒ 用它给 zram 的
#     **内存开销**封顶（关键：否则 zram 可以吃掉大量 RAM）
#   · vm.swappiness 文档："For in-memory swap, like zram or zswap ... values beyond 100 can be
#     considered. For example, if the random IO against the swap device is on average 2x faster
#     than IO from the filesystem, swappiness should be 133"
#     ⇒ zram 是内存级设备，远快于本机 vda 云盘 ⇒ 取 150（文档公式在 k=4~10 时给 160~181，取 150 偏保守）
#
# 可回滚：zram-swap-down.sh（带 swapoff 安全检查；--force 可覆盖）
#
# 重启后是否持久：**是**（2026-09-26 本轮起）。持久化方式见 zram-swap-systemd.md：
#   · /etc/systemd/system/zram-swap.service   本脚本 = ExecStart（建 zram），
#       ExecStartPost = zram-swap-verify.sh（断言成对），ExecStop = zram-swap-down.sh --shutdown
#   · /etc/sysctl.d/99-zram-swap.conf         vm.swappiness=150（开机早期兜底）
#   · /etc/modules-load.d/zram.conf           开机加载 zram 模块
#
# 【成对性】zram 与 vm.swappiness 缺一不可 —— 只有 zram 而 swappiness=60 时内核几乎不使用它，
#   等于没上 zram。因此本脚本把两者放在**同一个执行序列**里，先后顺序固定为
#   「先建 zram → mkswap/swapon → 再 sysctl vm.swappiness」，且幂等分支也会补设 swappiness
#   （否则"已启用就跳过"会留下 swappiness=60 的半套状态）。
#
# 用法：zram-swap-up.sh          （幂等：已作为 swap 启用则只补设/校验 swappiness）
set -euo pipefail

SZ="${ZRAM_SIZE:-2G}"
ALGO="${ZRAM_ALGO:-lz4}"
MEMSZ="${ZRAM_MEM_LIMIT:-1G}"
DEVPRIO="${ZRAM_PRIO:-100}"
SWAPPINESS="${VM_SWAPPINESS:-150}"
DEV=/dev/zram0

log() { printf '[zram-swap-up] %s\n' "$*"; }

# 设置并**回读确认** swappiness（成对性的另一半）。回读失败即非 0 退出 ⇒ 单元判失败，
# 以免出现"zram 已启用但 swappiness 还是 60"这种看起来成功、实际等于没上 zram 的状态。
set_swappiness() {
  log "vm.swappiness <- $SWAPPINESS"
  sysctl -w "vm.swappiness=$SWAPPINESS" >/dev/null
  local now
  now=$(cat /proc/sys/vm/swappiness)
  if [ "$now" != "$SWAPPINESS" ]; then
    echo "vm.swappiness 回读=$now，与期望 $SWAPPINESS 不一致" >&2
    exit 1
  fi
  log "vm.swappiness 回读 = $now ✓"
}

size_to_bytes() { # 1G/256M/1024K/1234 -> bytes
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

# 幂等：已经作为 swap 在用 ⇒ 不再动设备，但**必须**补设/校验 swappiness（成对性）
if swapon --show=NAME --noheadings 2>/dev/null | tr -d ' ' | grep -qx "$DEV"; then
  log "$DEV 已是活动 swap，跳过设备创建（如需改容量请先跑 zram-swap-down.sh）"
  set_swappiness
  log "--- 现状 ---"
  swapon --show
  zramctl
  free -m
  exit 0
fi

log "modprobe zram"
modprobe zram

# 顺序很重要（内核文档）：comp_algorithm / mem_limit 必须在写 disksize **之前**设置，
# 因为写 disksize 会初始化设备，之后再改压缩算法会返回 -EBUSY。
if [ -w /sys/block/zram0/comp_algorithm ]; then
  log "comp_algorithm <- $ALGO"
  echo "$ALGO" > /sys/block/zram0/comp_algorithm
else
  echo "no /sys/block/zram0/comp_algorithm —— zram 模块未就绪" >&2; exit 1
fi

# 给 zram 的**内存开销**封顶：存放压缩数据最多用 MEMSZ 的物理内存
if [ -w /sys/block/zram0/mem_limit ]; then
  log "mem_limit <- $MEMSZ (限制 zram 占用的物理内存)"
  echo "$MEMSZ" > /sys/block/zram0/mem_limit
  # mem_limit 的 sysfs 属性是**只写**的（权限 0200，root 也读不了）
  # ⇒ 回读只能取 /sys/block/zram0/mm_stat 的**第 4 列**（该对应已用变异验证钉死，见 verify 脚本）
  if [ -r /sys/block/zram0/mm_stat ]; then
    lim_now=$(awk 'NR==1{print $4}' /sys/block/zram0/mm_stat)
    log "mem_limit 回读(mm_stat 第4列) = $lim_now"
    if [ "$lim_now" != "$(size_to_bytes "$MEMSZ")" ]; then
      echo "mem_limit 回读=$lim_now，与期望 $(size_to_bytes "$MEMSZ") 不一致" >&2
      exit 1
    fi
  fi
else
  log "警告：本内核无 mem_limit，zram 内存开销无上限（继续，但需盯 mem_used_total）"
fi

log "disksize <- $SZ"
echo "$SZ" > /sys/block/zram0/disksize

log "mkswap + swapon -p $DEVPRIO"
mkswap "$DEV" >/dev/null
swapon -p "$DEVPRIO" "$DEV"

# 顺序固定：zram 就绪之后才设 swappiness
set_swappiness

log "--- 现状 ---"
swapon --show
zramctl
free -m
