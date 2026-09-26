#!/usr/bin/env bash
# alert-drill.sh —— 「喂数停摆」告警**真实演练**（一次性；会在演示数据里留下一条真实短断档）
#
# 设计（为什么这样最短且仍然真实）：
#   `feeder-watch.sh` 默认窗口 = L1 120s + L2 900s ≈ 17 分钟。因此"停到刚好触发"有两种口径：
#     (a) 停到**整轮跑完**才看结论 ⇒ 需停 ~17 分钟；
#     (b) 让**一次默认窗口的 L1 窗口（120s）完整落在停摆期内** ⇒ 只需停 ~2.5 分钟，
#         该轮最终仍会以真实的 `判定: ALERT（L1）` 落进 journald（L2 因期间已恢复而通过）。
#   本脚本采用 (b)：**喂数中断 ≈2.5 分钟**，但告警来自**默认窗口的真实判定**，不是缩短窗口造出来的。
#
# 用法：alert-drill.sh            # 生产机 root
# 记账：脚本本身不改任何配置；恢复后由调用方把断档写入 DEMO-DATA.md（**不得抹掉**）。
set -uo pipefail
D=/root/mem-round2-20260926
OUT=$D/alert-drill.txt
UNIT=feeder-watch-drill
SIM=access-tcp-simulator.service
say() { printf '[drill %s] %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT"; }
act() { curl -s -m 8 "http://127.0.0.1:18084/actuator/metrics/$1" | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); m=d.get("measurements") or []
    print(int(m[0]["value"]) if m else "NA")
except Exception: print("NA")'; }

systemctl reset-failed "$UNIT" >/dev/null 2>&1 || true

say "=== T0：停摆前基线 ==="
W0=$(act iot.timeseries.write.rows); R0=$(act iot.timeseries.db.rows)
say "  write.rows=$W0  db.rows=$R0  单元=$(systemctl is-active $SIM)  19002=$(ss -ltn | grep -c 172.20.0.1:19002)"
T0=$(date -u +%s)

say "=== 停喂数源（$SIM）——断档开始 ==="
systemctl stop "$SIM"
sleep 2
say "  停后：单元=$(systemctl is-active $SIM)  19002监听=$(ss -ltn | grep -c 172.20.0.1:19002)（期望 0）"

say "=== 立刻起一轮**默认窗口**判定（L1 120s 会完整落在停摆期内）==="
systemd-run --unit="$UNIT" --collect --property=StandardOutput=journal \
  /usr/local/sbin/feeder-watch.sh >>"$OUT" 2>&1
say "  已下发 $UNIT（后台跑，约 17 分钟后出结论）"

say "=== 观察 L3 类现象（停摆期内取证）==="
sleep 20
say "  access 日志最近是否有连接失败：$(docker logs --since 40s ypbin-access 2>&1 | grep -c 'open failed for connection t1-d9300012' || true) 条 open failed"
say "  write.rows 现值=$(act iot.timeseries.write.rows)（应与 $W0 相同 ⇒ 已停写）"

# 等 L1 窗口（120s）完全过去
elapsed=$(( $(date -u +%s) - T0 ))
need=$(( 135 - elapsed )); [ "$need" -gt 0 ] && sleep "$need"
say "  t+$(( $(date -u +%s) - T0 ))s：L1 窗口已完整覆盖停摆期（write.rows 应无增长：$W0 -> $(act iot.timeseries.write.rows)）"

say "=== 恢复喂数源（断档结束）==="
systemctl start "$SIM"
T1=$(date -u +%s)
say "  已启动；单元=$(systemctl is-active $SIM)  19002=$(ss -ltn | grep -c 172.20.0.1:19002)"
say "  **本设备（demo-dev-curve / 9300012）真实断档时长 ≈ $((T1-T0)) 秒**（$(date -u -d @$T0 +%FT%TZ) → $(date -u -d @$T1 +%FT%TZ)）"

say "=== 等 access 重新订阅 ==="
for i in $(seq 1 30); do
  if docker logs --since 4m ypbin-access 2>&1 | grep -aq '订阅成功：deviceId=9300012'; then
    say "  重新订阅成功（t+$(( $(date -u +%s) - T0 ))s）"; break
  fi
  sleep 10
done
for i in $(seq 1 12); do
  W=$(act iot.timeseries.write.rows)
  [ "$W" != "NA" ] && [ "$W" -gt "$W0" ] && { say "  喂数已恢复增长：write.rows $W0 -> $W"; break; }
  sleep 15
done
say "  恢复后 write.rows=$(act iot.timeseries.write.rows)  db.rows=$(act iot.timeseries.db.rows)"

say "=== 等演练那一轮的结论（最多 20 分钟）==="
for i in $(seq 1 80); do
  st=$(systemctl show -p ActiveState --value "$UNIT" 2>/dev/null || echo gone)
  [ "$st" = "gone" ] || [ "$st" = "inactive" ] || [ "$st" = "failed" ] && { say "  演练单元已结束（ActiveState=$st）"; break; }
  sleep 15
done
say "--- 演练单元的 journald 原文 ---"
journalctl -u "$UNIT" --no-pager -o cat 2>/dev/null | tee -a "$OUT" | tail -20
say "--- 演练单元退出结果 ---"
systemctl show "$UNIT" -p Result -p ExecMainStatus 2>/dev/null | tee -a "$OUT"
say "DONE"
