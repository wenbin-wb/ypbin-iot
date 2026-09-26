#!/usr/bin/env bash
# feeder-watch.sh —— 「喂数停摆」判据（**只读**，可安全高频运行）
#
# 背景（2026-09-26 生产实测）：
#   喂数源（TCP 模拟设备）是**容器外的宿主机进程**。它不在 compose 栈里时，一次机器重启就会让
#   采集链路**静默变成"零写入"**：所有容器 Up、Nacos 5/5 健康实例、19000=200、18084=UP，
#   但 `iot.reading` 一行都不再增加。**这种故障没有任何现成告警会触发。**
#   本脚本把判据固化成可执行形式，供人工巡检或接入监控/定时器。
#
# 判据（分层，快判据先响）：
#   L1（实时计数器，窗口 120s）：`iot.timeseries.write.rows` 在两分钟内无增长 ⇒ ALERT
#        —— 最快，但它是**进程内**计数器：ypbin-iot 重启会归零，故必须配合 L2
#   L2（真值，窗口 900s）：`iot.timeseries.db.rows`（对 iot.reading 的 COUNT(*) 探针，
#        服务端 10 分钟一次）在 15 分钟内无增长 ⇒ ALERT
#   L3（根因面）：
#        · 喂数源单元不 active，或 `172.20.0.1:19002` 无监听 ⇒ ALERT（喂数源自身挂了）
#        · `iot.timeseries.write.failed` 或 `iot.timeseries.db.probe.failed` 增长 ⇒ ALERT（写失败/库不可用）
#
# 退出码：0 = OK；2 = ALERT（有明确告警）；3 = 无 ALERT 但有判据**无法求值**（端点不可达等，本身也要人看）
#         （优先级：有 ALERT ⇒ 2 覆盖 3；这与文档一致，2026-09-26 复核后修正）
#
# 用法：
#   feeder-watch.sh            # 用默认窗口
#   feeder-watch.sh --window 60 --db-window 600
#
# 建议接入方式（**未实施，待批准**）：
#   systemd timer 每 2 分钟跑一次，`OnFailure=` 或解析退出码 2 触发告警；或由外部监控
#   直接抓 `/actuator/metrics/iot.timeseries.write.rows` 与 `.../db.rows` 做同比。
set -uo pipefail

WIN=120          # L1 窗口（秒）
DBWIN=900        # L2 窗口（秒）
SIM_BIND=172.20.0.1
SIM_PORT=19002
SIM_UNIT=access-tcp-simulator.service
ACT=http://127.0.0.1:18084/actuator/metrics

while [ $# -gt 0 ]; do
  case "$1" in
    --window)    WIN="$2"; shift 2 ;;
    --db-window) DBWIN="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 3 ;;
  esac
done

alerts=()
notes=()
unevaluable=()          # 判据无法求值（与 ALERT 区分，影响退出码）

act() { # act <metric> -> 整数或 NA
  curl -s --max-time 8 "$ACT/$1" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); m=d.get("measurements") or []
    print(int(m[0]["value"]) if m else "NA")
except Exception:
    print("NA")'
}

now=$(date -u +%FT%TZ)

# ── L1：实时写入计数器 ────────────────────────────────────────────────────────
w1=$(act iot.timeseries.write.rows)
sleep "$WIN"
w2=$(act iot.timeseries.write.rows)
notes+=("L1 write.rows: $w1 -> $w2（窗口 ${WIN}s）")
if [ "$w1" = "NA" ] || [ "$w2" = "NA" ]; then
  unevaluable+=("L1 无法求值：write.rows 读不到（ypbin-iot/18084 异常？）")
elif [ "$w2" -le "$w1" ]; then
  alerts+=("L1 喂数停摆：write.rows 在 ${WIN}s 内未增长（$w1 -> $w2）")
fi

# ── L2：真值探针（COUNT(*)）───────────────────────────────────────────────────
d1=$(act iot.timeseries.db.rows)
sleep "$DBWIN"
d2=$(act iot.timeseries.db.rows)
notes+=("L2 db.rows: $d1 -> $d2（窗口 ${DBWIN}s）")
if [ "$d1" = "NA" ] || [ "$d2" = "NA" ]; then
  unevaluable+=("L2 无法求值：db.rows 读不到")
elif [ "$d2" -le "$d1" ]; then
  alerts+=("L2 真值未增长：iot.reading 的 COUNT(*) 在 ${DBWIN}s 内未变（$d1 -> $d2）")
fi

# ── L3：根因面 ────────────────────────────────────────────────────────────────
simstate=$(systemctl is-active "$SIM_UNIT" 2>/dev/null || echo unknown)
notes+=("L3 $SIM_UNIT: $simstate")
[ "$simstate" = "active" ] || alerts+=("L3 喂数源单元不是 active（$simstate）")

if ss -ltn 2>/dev/null | grep -q "${SIM_BIND}:${SIM_PORT}"; then
  notes+=("L3 监听 ${SIM_BIND}:${SIM_PORT}: 在")
else
  notes+=("L3 监听 ${SIM_BIND}:${SIM_PORT}: 不在")
  alerts+=("L3 ${SIM_BIND}:${SIM_PORT} 无监听 ⇒ access 会一直 'open failed for connection t1-d9300012'")
fi

for m in iot.timeseries.write.failed iot.timeseries.db.probe.failed; do
  v=$(act "$m")
  notes+=("L3 $m = $v")
  case "$v" in
    NA) unevaluable+=("L3 $m 读不到") ;;
    0|0.0) : ;;
    *) alerts+=("L3 $m = $v（非 0 ⇒ 写失败/对账失败，需看 ERROR 日志）") ;;
  esac
done

# ── 输出 ─────────────────────────────────────────────────────────────────────
echo "feeder-watch @ $now"
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
echo "判定: OK（喂数正常）"
exit 0
