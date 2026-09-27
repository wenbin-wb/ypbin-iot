#!/usr/bin/env bash
# =============================================================================
# emqx-tunnel-watch.sh —— 「EMQX 管理面隧道停摆」判据（**只读**，可安全高频运行）
# =============================================================================
# 照仓库既有的 `deploy/feeder-watch.sh` 模式：**只告警，不自愈**。
#   自愈交给 `emqx-tunnel.service` 的 `Restart=always`；
#   本脚本一旦也去重启隧道，看门狗与单元就会互相打架（历史教训）。
#
# 判据（分层，快判据先响；任一层失败即 ALERT）：
#   L1（本地单元）：`systemctl is-active emqx-tunnel.service` 不是 active ⇒ ALERT
#   L2（本地监听）：`127.0.0.1:18093` 无监听 ⇒ ALERT（端口没建起来，或刚被重启）
#   L3（端到端）  ：`curl http://127.0.0.1:18093/status` 非 200 / 正文不含 "is started" ⇒ ALERT
#                   —— 这一层才是真正的「隧道通了 **且** 对端 EMQX 活着」；
#                     只看 L2 会把「端口在听但对端已死」判成 OK。
#   L4（进程面）  ：`emqx-tunnel.service` 的 NRestarts 在两次采样间增长 ⇒ 说明在**反复重连**
#                   （连上了又断），OK 判据不能只看「此刻是 active」
#
# ⚠️ 本脚本**不使用任何凭据**：只用 EMQX Dashboard 的免鉴权健康端点 `/status`。
#    需要 API Key 的深度探测（`/api/v5/...`）属平台侧（下一阶段），不放在生产机上，
#    避免把凭据落到生产机 —— 与「隧道只做端口转发、不落地凭据」同一条纪律。
#
# 退出码：0 = OK；2 = ALERT（有明确告警）；3 = 无 ALERT 但有判据**无法求值**
#         （优先级：有 ALERT ⇒ 2 覆盖 3，与 feeder-watch.sh 一致）
#
# 用法：
#   emqx-tunnel-watch.sh                 # 默认：端口 18093、单元 emqx-tunnel.service
#   emqx-tunnel-watch.sh --port 18093 --unit emqx-tunnel.service --restart-window 60
# =============================================================================
set -uo pipefail

PORT=18093
UNIT=emqx-tunnel.service
BIND=127.0.0.1
URL="http://127.0.0.1:${PORT}/status"
RESTART_WINDOW=60   # L4 两次采样的间隔（秒）
CURL_TIMEOUT=8

while [ $# -gt 0 ]; do
  case "$1" in
    --port)           PORT="$2"; URL="http://127.0.0.1:${PORT}/status"; shift 2 ;;
    --unit)           UNIT="$2"; shift 2 ;;
    --bind)           BIND="$2"; shift 2 ;;
    --restart-window) RESTART_WINDOW="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 3 ;;
  esac
done

alerts=()
notes=()
unevaluable=()

now=$(date -u +%FT%TZ)

# ── L1：单元状态 ──────────────────────────────────────────────────────────────
state=$(systemctl is-active "$UNIT" 2>/dev/null || echo unknown)
notes+=("L1 $UNIT = $state")
[ "$state" = "active" ] || alerts+=("L1 隧道单元不是 active（$state）⇒ systemd 可能正在重启它")

# ── L2：本地监听 ──────────────────────────────────────────────────────────────
if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "^${BIND}:${PORT}$"; then
  notes+=("L2 监听 ${BIND}:${PORT} = 在")
else
  notes+=("L2 监听 ${BIND}:${PORT} = 不在")
  alerts+=("L2 ${BIND}:${PORT} 无监听 ⇒ 平台侧连不上 EMQX 管理面")
fi

# ── L3：端到端可用性（隧道 + 对端 EMQX）───────────────────────────────────────
body="$(curl -s --max-time "$CURL_TIMEOUT" -o - -w '\n%{http_code}' "$URL" 2>/dev/null)"
code="$(printf '%s' "$body" | tail -n1)"
text="$(printf '%s' "$body" | sed '$d')"
if [ "$code" = "200" ] && printf '%s' "$text" | grep -q "is started"; then
  notes+=("L3 $URL = HTTP 200 且含 'is started'")
elif [ -z "$code" ] || [ "$code" = "000" ]; then
  # 注意：这一条**只进 ALERT，不进 unevaluable** —— 判据本身求值成功了，结论就是"连不上"；
  # 两边都放会让同一条被重复计数（"另有 N 条判据无法求值"里出现它自己）。
  alerts+=("L3 $URL 连不上 ⇒ 隧道或对端 EMQX 不可用")
else
  notes+=("L3 $URL = HTTP $code，正文=$(printf '%s' "$text" | head -c 120)")
  alerts+=("L3 $URL 返回异常（HTTP $code）⇒ 隧道通了但对端 EMQX 不健康")
fi

# ── L4：重启计数（发现「连上又断」的抖动）────────────────────────────────────
r1=$(systemctl show -p NRestarts --value "$UNIT" 2>/dev/null)
sleep "$RESTART_WINDOW"
r2=$(systemctl show -p NRestarts --value "$UNIT" 2>/dev/null)
notes+=("L4 NRestarts: ${r1:-NA} -> ${r2:-NA}（窗口 ${RESTART_WINDOW}s）")
if [ -z "$r1" ] || [ -z "$r2" ]; then
  unevaluable+=("L4 读不到 NRestarts")
elif [ "$r2" -gt "$r1" ]; then
  alerts+=("L4 ${RESTART_WINDOW}s 内重启 $((r2 - r1)) 次 ⇒ 隧道在反复重连（不是稳定长连）")
fi

# ── 输出 ─────────────────────────────────────────────────────────────────────
echo "emqx-tunnel-watch @ $now"
for n in "${notes[@]}"; do echo "  · $n"; done
if [ "${#alerts[@]}" -gt 0 ]; then
  echo "判定: ALERT（${#alerts[@]} 条）"
  for a in "${alerts[@]}"; do echo "  ! $a"; done
  if [ "${#unevaluable[@]}" -gt 0 ]; then
    echo "  （另有 ${#unevaluable[@]} 条判据无法求值）"
    for u in "${unevaluable[@]}"; do echo "  ? $u"; done
  fi
  exit 2
fi
if [ "${#unevaluable[@]}" -gt 0 ]; then
  echo "判定: 无法求值（${#unevaluable[@]} 条判据读不到；无 ALERT）"
  for u in "${unevaluable[@]}"; do echo "  ? $u"; done
  exit 3
fi
echo "判定: OK（隧道与 EMQX 管理面可用）"
exit 0
