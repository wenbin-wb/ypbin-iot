#!/usr/bin/env bash
# zram-swap-verify.sh —— 断言「zram 设备 + vm.swappiness」**成对**生效（只读、幂等）
#
# 用途：作为 systemd 单元 zram-swap.service 的 ExecStartPost 事后断言。
#   任一项不符 ⇒ 非 0 退出 ⇒ 单元判失败（绝不静默通过）。
#   也可人工随时运行做巡检（不改任何状态）。
#
# 期望值来自环境变量（与 zram-swap.service 的 Environment= 一致），未设置时用单元里的同款默认：
#   ZRAM_SIZE / ZRAM_ALGO / ZRAM_MEM_LIMIT / ZRAM_PRIO / VM_SWAPPINESS
set -uo pipefail

DEV=/dev/zram0
EXP_SIZE="${ZRAM_SIZE:-2G}"
EXP_ALGO="${ZRAM_ALGO:-lz4}"
EXP_MEM="${ZRAM_MEM_LIMIT:-1G}"
EXP_PRIO="${ZRAM_PRIO:-100}"
EXP_SWAP="${VM_SWAPPINESS:-150}"

ok=0
chk() { # chk <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then
    printf '  OK   %-34s = %s\n' "$1" "$2"
  else
    printf '  FAIL %-34s = %s (期望 %s)\n' "$1" "$2" "$3" >&2
    ok=1
  fi
}

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

echo "[zram-swap-verify] 期望：size=$EXP_SIZE algo=$EXP_ALGO mem_limit=$EXP_MEM prio=$EXP_PRIO swappiness=$EXP_SWAP"

# ── 1. zram 是否真的作为 swap 在使用 ────────────────────────────────────────────
if swapon --show=NAME --noheadings 2>/dev/null | tr -d ' ' | grep -qx "$DEV"; then
  chk "swapon 含 $DEV" "yes" "yes"
else
  chk "swapon 含 $DEV" "no" "yes"
fi

# ── 2. 设备属性（算法 / mem_limit / 容量）──────────────────────────────────────
if [ -r /sys/block/zram0/comp_algorithm ]; then
  algo=$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' /sys/block/zram0/comp_algorithm)
  chk "comp_algorithm(当前)" "${algo:-?}" "$EXP_ALGO"
else
  chk "comp_algorithm 可读" "no" "yes"
fi

# ⚠️ mem_limit 的 sysfs 属性权限是 **0200（只写）**，root 也读不了（实测 `cat` ⇒ Permission denied），
#    因此只能从 /sys/block/zram0/mm_stat 的**第 4 列**读取。
#    该对应关系已用变异验证钉死：把 mem_limit 写成 512M ⇒ 第 4 列变 536870912，
#    写回 1G ⇒ 变回 1073741824（其它列不变）。
#    mm_stat 列序（内核 admin-guide/blockdev/zram.rst）：
#      orig_data_size compr_data_size mem_used_total **mem_limit** mem_used_max ...
mm_memlimit() {
  if [ -r /sys/block/zram0/mem_limit ]; then
    cat /sys/block/zram0/mem_limit 2>/dev/null && return 0
  fi
  awk 'NR==1{print $4}' /sys/block/zram0/mm_stat
}
if [ -r /sys/block/zram0/mm_stat ]; then
  chk "mem_limit(bytes)" "$(mm_memlimit)" "$(to_bytes "$EXP_MEM")"
else
  chk "mm_stat 可读" "no" "yes"
fi

if [ -r /sys/block/zram0/disksize ]; then
  chk "disksize(bytes)" "$(cat /sys/block/zram0/disksize)" "$(to_bytes "$EXP_SIZE")"
else
  chk "disksize 可读" "no" "yes"
fi

# ── 3. 优先级 ──────────────────────────────────────────────────────────────────
prio=$(swapon --show=NAME,PRIO --noheadings 2>/dev/null | awk -v d="$DEV" '$1==d{print $2}')
chk "swap 优先级" "${prio:-?}" "$EXP_PRIO"

# ── 4. swappiness（成对的另一半）───────────────────────────────────────────────
chk "vm.swappiness" "$(cat /proc/sys/vm/swappiness)" "$EXP_SWAP"

# ── 5. 三项要求的原始输出（供人工/报告留证）────────────────────────────────────
echo "[zram-swap-verify] --- zramctl ---"; zramctl || true
echo "[zram-swap-verify] --- swapon -s ---"; swapon -s || true
echo "[zram-swap-verify] --- /proc/sys/vm/swappiness ---"; cat /proc/sys/vm/swappiness

if [ "$ok" -ne 0 ]; then
  echo "[zram-swap-verify] 判定：FAIL（zram 与 swappiness 未成对生效）" >&2
  exit 1
fi
echo "[zram-swap-verify] 判定：PASS（zram 设备 + vm.swappiness 成对生效）"
