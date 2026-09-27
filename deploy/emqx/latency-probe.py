#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
# latency-probe.py —— 下行订阅端的「端到端延迟」观测器（阶段② 的 p95 来源）
# =============================================================================
# 为什么需要它（而不是直接用 emqtt-bench 的输出）：
#   emqtt-bench 0.6.2 的控制台只打印 `publish_latency avg=Xms`（每秒一行，**均值**）；
#   延迟 P95 只在 QoE 汇总行里出现，而那一行的 publish_lat 记录**只在 `-Q log`（dlog）模式**
#   下才写入（源码 src/emqtt_bench.erl:1419 `is_qoe_dlog() andalso pub_qoe(...)`），
#   官方 0.6.2 的 dlog→CSV 导出实测拿不到 Publish 列数据。
#   ⇒ 用一个**只读订阅端**（~60 行、纯 paho-mqtt）精确统计每条消息的端到端延迟分位数。
#
# 负载仍由官方 emqtt-bench 产生（本脚本**不产生负载**，只订阅；
# 它订阅的就是平台将来要订阅的主题 `ypbin/v1/+/+/up/#`）。
#
# 延迟定义：emqtt-bench 用 `--payload-hdrs ts` 在每条消息体前加 8 字节**大端**毫秒时间戳
# （源码 `<< TS:64/integer, BinL/binary >>`）；本脚本取该时间戳与本地收包时刻之差。
#
# 凭据纪律：口令**只能**经环境变量传入（`MQTT_PASSWORD`），不接受命令行参数
# （命令行会出现在 `ps` 里）；本脚本从不打印口令。
#
# 用法：
#   MQTT_PASSWORD=xxx /opt/emqx/venv/bin/python latency-probe.py \
#       --host ypbin-emqx --port 1883 --username svc-load \
#       --topic 'ypbin/v1/+/+/up/#' --duration 600 --out /var/tmp/latency.txt
# =============================================================================
import argparse
import os
import statistics
import sys
import time

import paho.mqtt.client as mqtt


def main() -> int:
    ap = argparse.ArgumentParser(description="EMQX 端到端延迟观测（只订阅，不产生负载）")
    ap.add_argument("--host", default="ypbin-emqx")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--username", required=True)
    ap.add_argument("--topic", default="ypbin/v1/+/+/up/#")
    ap.add_argument("--qos", type=int, default=1)
    ap.add_argument("--duration", type=int, required=True, help="观测时长（秒）")
    ap.add_argument("--out", default="", help="把结果追加写入该文件")
    ap.add_argument("--warmup", type=int, default=5, help="建连后预热秒数（不计入统计）")
    args = ap.parse_args()

    password = os.environ.get("MQTT_PASSWORD")
    if not password:
        print("缺少环境变量 MQTT_PASSWORD（口令只允许经环境变量传入）", file=sys.stderr)
        return 2

    lat = []
    connected = {"ok": False}
    skipped = {"warmup": 0, "short": 0, "negative": 0}
    t_start = time.time()

    def on_connect(client, userdata, flags, reason_code, properties=None):
        if reason_code == 0:
            connected["ok"] = True
            client.subscribe(args.topic, args.qos)
        else:
            print(f"CONNACK 失败：reason_code={reason_code}", file=sys.stderr)

    def on_message(client, userdata, msg):
        if time.time() - t_start < args.warmup:
            skipped["warmup"] += 1
            return
        if len(msg.payload) < 8:
            skipped["short"] += 1
            return
        ts_ms = int.from_bytes(msg.payload[:8], "big")
        d = int(time.time() * 1000) - ts_ms
        if d < 0:
            skipped["negative"] += 1
            return
        lat.append(d)

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="ypbin-latency-probe")
    client.on_connect = on_connect
    client.on_message = on_message
    client.username_pw_set(args.username, password)
    client.connect(args.host, args.port, keepalive=30)
    client.loop_start()
    time.sleep(args.duration)
    client.loop_stop()
    client.disconnect()

    if not connected["ok"]:
        print("未能建立订阅（认证或网络失败）⇒ 延迟无法求值", file=sys.stderr)
        return 3

    if not lat:
        print("未收到任何消息 ⇒ 延迟无法求值"
              f"（warmup 丢弃 {skipped['warmup']}，过短 {skipped['short']}）", file=sys.stderr)
        return 4

    lat.sort()

    def pct(q: float) -> int:
        return lat[min(len(lat) - 1, int(len(lat) * q))]

    line = (f"n={len(lat)}  avg={statistics.mean(lat):.1f}ms  "
            f"p50={pct(0.50)}ms  p95={pct(0.95)}ms  p99={pct(0.99)}ms  max={lat[-1]}ms  "
            f"(预热丢弃 {skipped['warmup']} 条)")
    print(line)
    if args.out:
        with open(args.out, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
