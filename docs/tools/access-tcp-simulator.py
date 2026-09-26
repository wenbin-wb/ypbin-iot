#!/usr/bin/env python3
# Copyright (c) 2026-present ypbin-admin authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
"""最小 TCP 模拟设备 —— **测试工具，不是业务代码**。

用途：生产环境没有物理设备，但需要验证「access 采集链路是否真的在工作」。
本脚本扮演一台被动上报的 TCP 设备：客户端（= access 容器）连上来之后，按固定周期
把一帧数据写回连接。access 侧收到帧 → 交给协议栈 → 点位映射 → 微批上报 iot，
于是 `device_liveness` / 断档事件 / Redis 最新值 / IoTDB 时序行都由**真实链路**产生。

⚠️ 与真实设备的差异（不要把它当成采集能力的证明）：
  1. 它只会「连上来就发」，不响应任何下行写；TCP 适配器本身也只声明 WRITE + SUBSCRIBE_STREAM。
  2. 帧内容是固定文本，不代表任何真实工业协议（Modbus/OPC UA 的寄存器语义完全没有覆盖）。
  3. 它不模拟断线重连/心跳超时/半开连接，因此**链路的健壮性没有被验证**。

用法：
    python3 access-tcp-simulator.py --port 19002 --interval 2.0

停止：Ctrl-C（或 kill）；停止后接入侧不再有新读数，正是验收「数据确由该链路产生」的反证手段。
"""

import argparse
import socket
import threading
import time

BANNER = "[SIM] ypbin-iot 测试用 TCP 模拟设备（非业务代码）"


def serve_connection(conn: socket.socket, addr, interval: float, counter: dict, lock: threading.Lock):
    """单个连接：按周期发帧，直到对端断开。"""
    print(f"{BANNER} 连接建立：{addr}", flush=True)
    seq = 0
    try:
        while True:
            seq += 1
            payload = f"TEMP=23.5,SEQ={seq}\n".encode("utf-8")
            conn.sendall(payload)
            with lock:
                counter["frames"] += 1
            if seq == 1 or seq % 15 == 0:
                print(f"{BANNER} -> {addr} 已发送 {seq} 帧（累计 {counter['frames']}）", flush=True)
            time.sleep(interval)
    except (BrokenPipeError, ConnectionResetError, OSError) as ex:
        print(f"{BANNER} 连接断开：{addr}（已发 {seq} 帧）：{ex}", flush=True)
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description="ypbin-iot 测试用 TCP 模拟设备")
    parser.add_argument("--bind", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=19002, help="监听端口（默认 19002）")
    parser.add_argument("--interval", type=float, default=2.0, help="发帧间隔秒（默认 2.0）")
    args = parser.parse_args()

    counter = {"frames": 0, "conns": 0}
    lock = threading.Lock()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.port))
    srv.listen(8)
    print(f"{BANNER}\n{BANNER} 监听 {args.bind}:{args.port}，间隔 {args.interval}s", flush=True)
    try:
        while True:
            conn, addr = srv.accept()
            with lock:
                counter["conns"] += 1
            threading.Thread(target=serve_connection,
                             args=(conn, addr, args.interval, counter, lock),
                             daemon=True).start()
    except KeyboardInterrupt:
        print(f"{BANNER} 收到中断，停止。累计连接 {counter['conns']}，帧 {counter['frames']}",
              flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
