#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟 MQTT 设备：用**平台签发的凭据**连接 EMQX，发布上行读数或**订阅下行并回执**（入站/下行验收用）。

为什么需要它：入站链路的验收必须由「真 MQTT 客户端 + 真凭据」驱动，而不是直接 curl 平台端点
（后者只证明端点活着，证明不了认证、ACL、规则、动作四段串联）。本脚本刻意做成**单文件、无额外依赖
之外的东西**：只用 paho-mqtt（中间件机 `/opt/emqx/venv` 里已有），退出码即判据。

凭据纪律：口令**只从 600 文件读**（`--password-file`），不进 argv、不打印；输出只给 topic 长度、
payload 长度与结果判据。

退出码（供验收脚本判定）：
  0  发布成功（QoS1 收到 PUBACK 且连接保持）
  2  连接被拒（认证失败：username/口令不对，或账号已吊销/不存在）
  3  发布被 broker 断开（ACL **在 deny_action=disconnect 时**才会出现）
  4  超时/网络错误（链路不可达）
  5  listen：等待窗口内没有收到下行消息

⚠️ **本 broker 的 ACL 拒绝在客户端看来是"成功"**（实测 2026-09-27）：配置里
`authorization.deny_action = ignore`（设计 H1 明确要求）⇒ 越权 publish 既不回错误、也不断开，
客户端照常收到 PUBACK（退出码 0）。因此**越权用例的判据只能是 EMQX 指标差值**
（`packets.publish.auth_error` / `authorization.nomatch`），不能用本脚本的退出码。
把它写在这里，是因为"退出码 0 = 发布成功"的直观读法在验收里会给出**假绿**。
"""
import argparse
import json
import sys
import time

from paho.mqtt.client import CallbackAPIVersion, Client, MQTTv311

# 断连/发布结果的观察窗口（越权发布时 broker 会在这段时间内断开连接）
OBSERVE_SECONDS = 3.0

EXIT_OK = 0
EXIT_AUTH_REFUSED = 2
EXIT_PUBLISH_DENIED = 3
EXIT_NETWORK = 4

EXIT_NO_MESSAGE = 5


class Outcome:
    """收集回调里的事实（不用全局变量，避免 paho 回调线程与主线程的隐式耦合）。"""

    def __init__(self):
        self.connected = False
        self.connack_rc = None
        self.published = False
        self.disconnected = False
        self.error = None


def listen(args, password):
    """订阅下行主题，收到一条消息后打印并（可选）回执。

    :param args: 命令行参数
    :param password: 明文口令（从 600 文件读入，不打印）
    :return: 退出码
    """
    outcome = Outcome()
    received = {}

    def on_connect(_client, _userdata, _flags, reason_code, _properties):
        value = getattr(reason_code, "value", reason_code)
        outcome.connected = (value == 0)
        outcome.connack_rc = value

    def on_disconnect(_client, _userdata, _flags, _reason_code, _properties):
        outcome.disconnected = True

    def on_message(_client, _userdata, message):
        received["topic"] = message.topic
        received["payload"] = message.payload.decode("utf-8", errors="replace")

    def on_publish(_client, _userdata, _mid, _reason_code, _properties):
        outcome.published = True

    client = Client(CallbackAPIVersion.VERSION2, client_id=(args.client_id or "") + "-sub",
                    clean_session=True, protocol=MQTTv311)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message
    client.on_publish = on_publish
    if args.username:
        client.username_pw_set(args.username, password)
    try:
        client.connect(args.host, args.port, keepalive=15)
    except OSError as exc:
        print("NETWORK_ERROR connect failed: %s" % exc.__class__.__name__)
        return EXIT_NETWORK
    client.loop_start()
    deadline = time.time() + 5
    while time.time() < deadline and not outcome.connected and not outcome.disconnected:
        time.sleep(0.05)
    if not outcome.connected:
        client.loop_stop()
        print("AUTH_REFUSED connack_rc=%s" % outcome.connack_rc)
        return EXIT_AUTH_REFUSED
    client.subscribe(args.topic, qos=args.qos)
    deadline = time.time() + args.wait_seconds
    while time.time() < deadline and "payload" not in received:
        time.sleep(0.05)
    if "payload" not in received:
        client.loop_stop()
        print("NO_MESSAGE topic=%s waited=%ss" % (args.topic, args.wait_seconds))
        return EXIT_NO_MESSAGE
    payload = received["payload"]
    print("RECEIVED topic=%s payload=%s" % (received["topic"], payload))
    if args.reply_code is None:
        client.disconnect()
        client.loop_stop()
        return EXIT_OK
    # 从下行报文里取 requestId（回执契约：requestId 在 payload 里，不在主题里）
    request_id = None
    try:
        request_id = json.loads(payload).get("requestId")
    except ValueError:
        request_id = None
    if not request_id:
        client.loop_stop()
        print("REPLY_SKIPPED 下行报文里没有 requestId")
        return EXIT_NETWORK
    reply = {"deviceId": args.device_id, "requestId": request_id, "code": args.reply_code,
             "message": args.reply_message, "data": json.loads(args.reply_data),
             "ts": int(time.time() * 1000)}
    client.publish(args.reply_topic, json.dumps(reply), qos=1)
    deadline = time.time() + 5
    while time.time() < deadline and not outcome.published:
        time.sleep(0.05)
    client.disconnect()
    client.loop_stop()
    print("REPLY_SENT requestId=%s code=%s published=%s" % (request_id, args.reply_code,
                                                           outcome.published))
    return EXIT_OK


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--username", default=None, help="不传 = 匿名连接（负向用例）")
    parser.add_argument("--password-file", default=None, help="600 文件；口令不落 argv")
    parser.add_argument("--client-id", default=None)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--payload", required=True)
    parser.add_argument("--qos", type=int, default=1)
    parser.add_argument("--mode", choices=("pub", "listen"), default="pub",
                        help="pub=发布上行读数；listen=订阅下行并在收到后回执")
    parser.add_argument("--wait-seconds", type=float, default=25.0, help="listen 的等待窗口")
    parser.add_argument("--device-id", default=None, help="listen 回执里的 deviceId")
    parser.add_argument("--reply-topic", default=None, help="listen 回执发布到的 up/reply 主题")
    parser.add_argument("--reply-code", type=int, default=None, help="给该值即回执（0=成功）")
    parser.add_argument("--reply-message", default="ok")
    parser.add_argument("--reply-data", default="{}")
    args = parser.parse_args()

    password = None
    if args.password_file:
        with open(args.password_file, encoding="utf-8") as handle:
            password = handle.read().strip()
    if args.mode == "listen":
        return listen(args, password)

    outcome = Outcome()
    # paho 2.x：显式声明回调 API 版本（VERSION1 已废弃，会在 stderr 打警告，污染验收输出）
    client = Client(CallbackAPIVersion.VERSION2, client_id=args.client_id or "",
                    clean_session=True, protocol=MQTTv311)

    def on_connect(_client, _userdata, _flags, reason_code, _properties):
        # reason_code 是 ReasonCode 对象：0/成功 为 0；认证失败为 4/5（或 paho 直接断开）
        outcome.connack_rc = getattr(reason_code, "value", reason_code)
        outcome.connected = (outcome.connack_rc == 0)

    def on_disconnect(_client, _userdata, _flags, _reason_code, _properties):
        outcome.disconnected = True

    def on_publish(_client, _userdata, _mid, _reason_code, _properties):
        outcome.published = True

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_publish = on_publish
    if args.username:
        client.username_pw_set(args.username, password)

    try:
        client.connect(args.host, args.port, keepalive=15)
    except OSError as exc:
        print("NETWORK_ERROR connect failed: %s" % exc.__class__.__name__)
        return EXIT_NETWORK

    client.loop_start()
    deadline = time.time() + OBSERVE_SECONDS
    while time.time() < deadline and not outcome.connected and not outcome.disconnected:
        time.sleep(0.05)

    if not outcome.connected:
        client.loop_stop()
        # 认证失败：broker 回 CONNACK rc=4/5（paho 只在 v3.1.1 下把 rc 传给 on_connect，
        # 部分版本直接断开）⇒ 两种都归"被拒"
        print("AUTH_REFUSED connack_rc=%s disconnected=%s"
              % (outcome.connack_rc, outcome.disconnected))
        return EXIT_AUTH_REFUSED

    info = client.publish(args.topic, args.payload, qos=args.qos)
    deadline = time.time() + OBSERVE_SECONDS
    while time.time() < deadline and not outcome.published and not outcome.disconnected:
        time.sleep(0.05)

    denied = outcome.disconnected and not outcome.published
    if denied:
        # 越权 publish：broker 直接断开（MQTT 3.1.1 无 error 包）
        client.loop_stop()
        print("PUBLISH_DENIED topic_len=%d payload_len=%d（broker 已断开 ⇒ ACL 拒绝）"
              % (len(args.topic), len(args.payload)))
        return EXIT_PUBLISH_DENIED

    if not outcome.published:
        client.loop_stop()
        print("PUBLISH_TIMEOUT mid=%s disconnected=%s" % (info.mid, outcome.disconnected))
        return EXIT_NETWORK

    client.disconnect()
    client.loop_stop()
    print("PUBLISHED topic_len=%d payload_len=%d qos=%d"
          % (len(args.topic), len(args.payload), args.qos))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
