#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从**外部网络视角**（如生产机）用 MQTT 3.1.1 连接 EMQX，验证可达性 / 鉴权 / 越权 / 下行回执。

与 `mqtt-device-probe.py` 的分工（两者互补，不重复）：
  · `mqtt-device-probe.py` 跑在**中间件机**、依赖 `/opt/emqx/venv` 的 paho-mqtt，
    用于「中间件机本地回环」的入站/下行验收；
  · **本脚本零第三方依赖（只用标准库 socket/ssl/struct/json）**，用于「从**别的机器**经公网
    连 43.242.200.8:1883」的验收——生产机上没有 paho，且不应为了一次验收去污染生产机的 Python。
    这一点是本脚本存在的唯一理由：**"从外部真实接入"才是暴露面验收的核心**。

凭据纪律：口令**只从 600 文件读**（`--password-file`），不进 argv、不打印；
输出只给 topic/payload 长度、CONNACK 返回码与判据结论。

⚠️ 本 broker 的 ACL 拒绝在客户端看来是"成功"：`authorization.deny_action = ignore`
   ⇒ 越权 PUBLISH 既不回错误也不断开，客户端照常收到 PUBACK（退出码 0）。
   **越权用例的判据只能是 EMQX 指标差值**（`packets.publish.auth_error` / `authorization.nomatch`），
   不能看本脚本的退出码——把它写在这里，是因为"退出码 0 = 发布成功"的直观读法会给出**假绿**。

退出码（供验收脚本判定）：
  0  成功（连接 + 发布/订阅按预期完成）
  2  连接被拒（CONNACK rc 非 0，或 broker 直接断开 —— 匿名/错口令都走这里）
  3  发布被 broker 断开（仅当 deny_action 被改成 disconnect 时才会出现）
  4  网络/协议错误（连不上、超时、TLS 失败）
  5  listen 模式：等待窗口内没收到下行消息
"""
import argparse
import json
import socket
import ssl
import struct
import sys
import time

# ── MQTT 3.1.1 常量（杜绝魔法值） ─────────────────────────────────────────────
MQTT_PROTOCOL_NAME = b"MQTT"
MQTT_PROTOCOL_LEVEL = 0x04          # 3.1.1
CONNECT_FLAG_CLEAN_SESSION = 0x02
CONNECT_FLAG_WILL = 0x04
CONNECT_FLAG_USERNAME = 0x80
CONNECT_FLAG_PASSWORD = 0x40

PKT_TYPE_CONNECT = 1
PKT_TYPE_CONNACK = 2
PKT_TYPE_PUBLISH = 3
PKT_TYPE_PUBACK = 4
PKT_TYPE_SUBSCRIBE = 8
PKT_TYPE_SUBACK = 9
PKT_TYPE_PINGREQ = 12
PKT_TYPE_PINGRESP = 13
PKT_TYPE_DISCONNECT = 14

# ⚠️ 实测坑（自我修正，留档）：报文**类型号**在首字节的**高半字节**，
#    所以 CONNACK 的"类型号"是 2，而它的首字节是 0x20。把这两个概念混用
#    （拿 0x20 去和 `first >> 4` 比较）会让每一次握手都被误判成"实得报文类型 2"。
#    ⇒ 统一约定：常量一律是**类型号**，构造首字节时左移 4 位。

CONNACK_ACCEPTED = 0
CONNACK_REFUSED_NOT_AUTHORIZED = 5

EXIT_OK = 0
EXIT_AUTH_REFUSED = 2
EXIT_PUBLISH_DENIED = 3
EXIT_NETWORK = 4
EXIT_NO_MESSAGE = 5

# 连接/收包超时（秒）——远程调用必须显式超时，禁用无超时默认值
CONNECT_TIMEOUT = 10.0
READ_TIMEOUT = 15.0
# keepalive 与心跳间隔：CONNECT 里声明的 keepalive 是 15s，broker 在 **2×keepalive** 内收不到任何
# 报文就会断开空闲客户端（实测本 broker 正好 30s）。listen 模式最长等 45s ⇒ **必须发 PINGREQ**，
# 否则"订阅成功但什么都没收到"会被误读成"没有下行"，实际是连接早被 keepalive 断掉了（实测踩过）。
KEEPALIVE_SECONDS = 15
PING_INTERVAL = 5.0


def encode_remaining_length(length):
    """MQTT 变长"剩余长度"编码。"""
    out = bytearray()
    while True:
        byte = length % 128
        length //= 128
        if length > 0:
            byte |= 0x80
        out.append(byte)
        if length == 0:
            return bytes(out)


def encode_string(raw):
    """MQTT UTF-8 字符串：2 字节大端长度 + 内容。"""
    return struct.pack("!H", len(raw)) + raw


def build_connect(client_id, username=None, password=None, keepalive=KEEPALIVE_SECONDS):
    """构造 CONNECT 报文。username/password 为 None 时置对应标志位为 0（= 匿名连接）。"""
    flags = CONNECT_FLAG_CLEAN_SESSION
    payload = encode_string(client_id.encode("utf-8"))
    if username is not None:
        flags |= CONNECT_FLAG_USERNAME
        payload += encode_string(username.encode("utf-8"))
    if password is not None:
        flags |= CONNECT_FLAG_PASSWORD
        payload += encode_string(password.encode("utf-8"))
    variable = encode_string(MQTT_PROTOCOL_NAME) + bytes([MQTT_PROTOCOL_LEVEL, flags]) \
        + struct.pack("!H", keepalive)
    body = variable + payload
    return bytes([PKT_TYPE_CONNECT << 4]) + encode_remaining_length(len(body)) + body


def build_publish(topic, payload, qos=1, packet_id=1):
    """构造 PUBLISH；qos=0 无报文标识符，qos>=1 带 packet_id。"""
    body = encode_string(topic.encode("utf-8"))
    if qos > 0:
        body += struct.pack("!H", packet_id)
    body += payload.encode("utf-8")
    header = (PKT_TYPE_PUBLISH << 4) | (qos << 1)
    return bytes([header]) + encode_remaining_length(len(body)) + body


def build_subscribe(topic, qos=1, packet_id=1):
    body = struct.pack("!H", packet_id) + encode_string(topic.encode("utf-8")) + bytes([qos])
    return bytes([(PKT_TYPE_SUBSCRIBE << 4) | 0x02]) + encode_remaining_length(len(body)) + body


def build_puback(packet_id):
    """PUBACK：QoS1 收到下行后必须回，否则 broker 会一直重发（并计入 inflight/awaiting_rel）。"""
    body = struct.pack("!H", packet_id)
    return bytes([PKT_TYPE_PUBACK << 4]) + encode_remaining_length(len(body)) + body


def build_simple(packet_type):
    """构造无变长体的报文（PINGREQ / DISCONNECT）；参数是**类型号**。"""
    return bytes([packet_type << 4, 0x00])


def recv_exact(sock, count):
    """读满 count 字节；对端关闭时抛 ConnectionResetError。"""
    chunks = []
    remaining = count
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ConnectionResetError("对端在读到 %d/%d 字节时关闭连接" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def recv_packet(sock):
    """读一个 MQTT 报文，返回 (packet_type, flags, body)。

    :return: 三元组；对端关闭时抛 ConnectionResetError
    """
    first = recv_exact(sock, 1)[0]
    multiplier = 1
    length = 0
    for _ in range(4):                       # 剩余长度最多 4 字节
        byte = recv_exact(sock, 1)[0]
        length += (byte & 0x7F) * multiplier
        if not byte & 0x80:
            break
        multiplier *= 128
    else:
        raise ValueError("剩余长度编码非法（超过 4 字节）")
    body = recv_exact(sock, length) if length else b""
    return first >> 4, first & 0x0F, body


def parse_connack(body):
    if len(body) < 2:
        raise ValueError("CONNACK 长度不足：%d" % len(body))
    return body[1]


def parse_puback_id(body):
    return struct.unpack("!H", body[:2])[0] if len(body) >= 2 else None


def connect(args, password):
    """建立 TCP/TLS 连接并完成 MQTT CONNECT 握手。

    :return: (sock, connack_rc)；连接层失败抛 OSError，握手层被拒返回 rc 非 0
    """
    raw = socket.create_connection((args.host, args.port), timeout=CONNECT_TIMEOUT)
    raw.settimeout(READ_TIMEOUT)
    if args.tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        if args.tls_insecure:
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
        elif args.tls_ca:
            context.load_verify_locations(args.tls_ca)
        else:
            context.load_default_certs()
        raw = context.wrap_socket(raw, server_hostname=args.host)
    sock = raw
    sock.sendall(build_connect(args.client_id or "", args.username, password))
    ptype, _flags, body = recv_packet(sock)
    if ptype != PKT_TYPE_CONNACK:
        raise ValueError("期望 CONNACK，实得报文类型 %d" % ptype)
    return sock, parse_connack(body)


def run_publish(args, password):
    try:
        sock, rc = connect(args, password)
    except OSError as exc:
        print("NETWORK_ERROR %s: %s" % (exc.__class__.__name__, exc))
        return EXIT_NETWORK
    except ValueError as exc:
        print("PROTOCOL_ERROR %s" % exc)
        return EXIT_NETWORK
    if rc != CONNACK_ACCEPTED:
        print("AUTH_REFUSED connack_rc=%s" % rc)
        return EXIT_AUTH_REFUSED
    print("CONNECTED connack_rc=0")
    try:
        sock.sendall(build_publish(args.topic, args.payload, qos=args.qos))
        if args.qos == 0:
            # QoS0 无应答：broker 收到即算投递；越权时 deny_action=ignore ⇒ 客户端看不出区别
            print("PUBLISHED topic_len=%d payload_len=%d qos=0（无 PUBACK，判据见 EMQX 指标）"
                  % (len(args.topic), len(args.payload)))
            return EXIT_OK
        while True:
            ptype, _flags, body = recv_packet(sock)
            if ptype == PKT_TYPE_PUBACK:
                print("PUBLISHED topic_len=%d payload_len=%d qos=%d puback_id=%s"
                      % (len(args.topic), len(args.payload), args.qos, parse_puback_id(body)))
                return EXIT_OK
            if ptype == PKT_TYPE_PINGREQ:   # 不该出现，防御性忽略
                continue
            # 其余报文（如 broker 下发的 DISCONNECT/其他）不看
    except ConnectionResetError as exc:
        # deny_action=disconnect 时越权会走到这里
        print("PUBLISH_DENIED broker 断开连接（%s）" % exc)
        return EXIT_PUBLISH_DENIED
    except (OSError, ValueError) as exc:
        print("NETWORK_ERROR %s: %s" % (exc.__class__.__name__, exc))
        return EXIT_NETWORK
    finally:
        try:
            sock.sendall(build_simple(PKT_TYPE_DISCONNECT))
        except OSError:
            pass
        sock.close()


def run_listen(args, password):
    """订阅下行主题；收到一条后打印，并可自动回执（listen 模式）。"""
    try:
        sock, rc = connect(args, password)
    except OSError as exc:
        print("NETWORK_ERROR %s: %s" % (exc.__class__.__name__, exc))
        return EXIT_NETWORK
    except ValueError as exc:
        print("PROTOCOL_ERROR %s" % exc)
        return EXIT_NETWORK
    if rc != CONNACK_ACCEPTED:
        print("AUTH_REFUSED connack_rc=%s" % rc)
        return EXIT_AUTH_REFUSED
    try:
        sock.sendall(build_subscribe(args.topic, qos=args.qos))
        while True:
            ptype, _flags, body = recv_packet(sock)
            if ptype == PKT_TYPE_SUBACK:
                granted = body[2] if len(body) >= 3 else None
                print("SUBSCRIBED topic=%s granted_qos=%s" % (args.topic, granted), flush=True)
                if granted == 0x80:
                    print("SUBSCRIBE_REFUSED broker 拒绝订阅（granted=0x80）")
                    return EXIT_AUTH_REFUSED
                break
        deadline = time.time() + args.wait_seconds
        next_ping = time.time() + PING_INTERVAL
        while time.time() < deadline:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            now = time.time()
            if now >= next_ping:
                sock.sendall(build_simple(PKT_TYPE_PINGREQ))
                next_ping = now + PING_INTERVAL
            sock.settimeout(min(READ_TIMEOUT, max(0.2, remaining), max(0.2, next_ping - time.time())))
            try:
                ptype, flags, body = recv_packet(sock)
            except socket.timeout:
                continue
            if ptype == PKT_TYPE_PINGRESP:
                continue
            if ptype != PKT_TYPE_PUBLISH:
                continue
            qos = (flags >> 1) & 0x03
            topic_len = struct.unpack("!H", body[:2])[0]
            topic = body[2:2 + topic_len].decode("utf-8", errors="replace")
            offset = 2 + topic_len
            packet_id = None
            if qos > 0:
                packet_id = struct.unpack("!H", body[offset:offset + 2])[0]
                offset += 2
            payload = body[offset:].decode("utf-8", errors="replace")
            # QoS1 必须回 PUBACK：不回的话 broker 会持续重发这条下行，并一直把它算在 inflight 里
            if qos == 1 and packet_id is not None:
                sock.sendall(build_puback(packet_id))
            print("RECEIVED topic=%s qos=%d payload=%s" % (topic, qos, payload), flush=True)
            if args.reply_code is None:
                return EXIT_OK
            try:
                request_id = json.loads(payload).get("requestId")
            except ValueError:
                print("REPLY_SKIPPED 下行报文不是合法 JSON")
                return EXIT_NETWORK
            if not request_id:
                print("REPLY_SKIPPED 下行报文里没有 requestId")
                return EXIT_NETWORK
            reply = {"deviceId": args.device_id, "requestId": request_id, "code": args.reply_code,
                     "message": args.reply_message, "data": json.loads(args.reply_data),
                     "ts": int(time.time() * 1000)}
            sock.sendall(build_publish(args.reply_topic, json.dumps(reply), qos=1))
            while True:
                ptype2, _f2, body2 = recv_packet(sock)
                if ptype2 == PKT_TYPE_PINGRESP:
                    continue
                if ptype2 == PKT_TYPE_PUBACK:
                    print("REPLY_SENT requestId=%s code=%s puback_id=%s"
                          % (request_id, args.reply_code, parse_puback_id(body2)), flush=True)
                    return EXIT_OK
        print("NO_MESSAGE topic=%s waited=%ss" % (args.topic, args.wait_seconds))
        return EXIT_NO_MESSAGE
    except ConnectionResetError as exc:
        print("CONNECTION_RESET %s（broker 断开：订阅越权或会话被踢）" % exc)
        return EXIT_PUBLISH_DENIED
    except (OSError, ValueError) as exc:
        print("NETWORK_ERROR %s: %s" % (exc.__class__.__name__, exc))
        return EXIT_NETWORK
    finally:
        try:
            sock.sendall(build_simple(PKT_TYPE_DISCONNECT))
        except OSError:
            pass
        sock.close()


def run_auth(args, password):
    """只做连接握手：用于「有凭据能连 / 无凭据被拒」的正负用例。"""
    try:
        sock, rc = connect(args, password)
    except OSError as exc:
        print("NETWORK_ERROR %s: %s" % (exc.__class__.__name__, exc))
        return EXIT_NETWORK
    except ValueError as exc:
        print("PROTOCOL_ERROR %s" % exc)
        return EXIT_NETWORK
    try:
        if rc == CONNACK_ACCEPTED:
            print("CONNECTED connack_rc=0（连接被接受）")
            return EXIT_OK
        print("AUTH_REFUSED connack_rc=%s" % rc)
        return EXIT_AUTH_REFUSED
    finally:
        try:
            sock.sendall(build_simple(PKT_TYPE_DISCONNECT))
        except OSError:
            pass
        sock.close()


def main():
    parser = argparse.ArgumentParser(description="零依赖 MQTT 3.1.1 外部接入探针")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--mode", choices=("pub", "listen", "auth"), default="pub")
    parser.add_argument("--username", default=None, help="不传 = 匿名连接（负向用例）")
    parser.add_argument("--password-file", default=None, help="600 文件；口令不进 argv")
    parser.add_argument("--client-id", default=None)
    parser.add_argument("--topic", default=None, help="pub/listen 必填")
    parser.add_argument("--payload", default=None, help="pub 必填")
    parser.add_argument("--qos", type=int, default=1)
    parser.add_argument("--wait-seconds", type=float, default=30.0)
    parser.add_argument("--device-id", default=None)
    parser.add_argument("--reply-topic", default=None)
    parser.add_argument("--reply-code", type=int, default=None)
    parser.add_argument("--reply-message", default="ok")
    parser.add_argument("--reply-data", default="{}")
    parser.add_argument("--tls", action="store_true", help="走 TLS（8883；本轮未启用，留作后续）")
    parser.add_argument("--tls-ca", default=None)
    parser.add_argument("--tls-insecure", action="store_true")
    args = parser.parse_args()

    if args.mode in ("pub", "listen") and not args.topic:
        print("BAD_ARGS %s 模式必须给 --topic" % args.mode)
        return EXIT_NETWORK
    if args.mode == "pub" and args.payload is None:
        print("BAD_ARGS pub 模式必须给 --payload")
        return EXIT_NETWORK

    password = None
    if args.password_file:
        with open(args.password_file, encoding="utf-8") as handle:
            password = handle.read().strip()

    if args.mode == "listen":
        return run_listen(args, password)
    if args.mode == "auth":
        return run_auth(args, password)
    return run_publish(args, password)


if __name__ == "__main__":
    sys.exit(main())
