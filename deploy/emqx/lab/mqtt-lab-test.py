#!/usr/bin/env python3
# =============================================================================
# EMQX 实验床功能验证（issue #218 来源限制/限流；issue #219 集群路由）
# =============================================================================
# 依赖：paho-mqtt（中间件机 /opt/emqx/venv 含 2.1.0）
# 用法：/opt/emqx/venv/bin/python mqtt-lab-test.py <node1_ip> <node2_ip> [restricted_port] [ratelimited_port]
# 说明：lab 监听器 enable_authn=false，无需凭据；客户端从中间件机宿主发起，
#       源地址 172.29.0.1（emqx-lab 网桥网关）——落在 default/ratelimited 的 allow 网段、
#       落在 restricted 的 deny 范围（只 allow 回环）。
# =============================================================================
import sys
import threading
import time
import paho.mqtt.client as mqtt

NODE1, NODE2 = sys.argv[1], sys.argv[2]
RESTRICTED_PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 1884
RATELIMITED_PORT = int(sys.argv[4]) if len(sys.argv) > 4 else 1885
V = mqtt.CallbackAPIVersion.VERSION2
_seq = [0]


def new_client(client_id):
    return mqtt.Client(V, client_id=client_id, protocol=mqtt.MQTTv311)


def try_connect(host, port, timeout=6.0):
    got = {"connected": False, "rc": None, "err": None}
    _seq[0] += 1
    c = new_client("lab-conn-%d" % _seq[0])

    def on_connect(client, userdata, flags, rc, properties=None):
        got["connected"] = True
        got["rc"] = getattr(rc, "value", rc)

    c.on_connect = on_connect
    try:
        c.connect_async(host, port, 4)
        c.loop_start()
    except Exception as exc:  # noqa: BLE001
        got["err"] = repr(exc)
        return got
    deadline = time.time() + timeout
    while time.time() < deadline and not got["connected"]:
        time.sleep(0.05)
    try:
        c.disconnect()
    except Exception:
        pass
    c.loop_stop()
    return got


def test_allow_deny():
    print("[218] default 1883 (allow 172.29.0.0/16) ->", try_connect(NODE1, 1883))
    print("[218] restricted %d (allow loopback only) ->" % RESTRICTED_PORT,
          try_connect(NODE1, RESTRICTED_PORT))


def test_conn_rate():
    # ratelimited 监听器 max_conn_rate=1/s：并发 8 个连接，统计 3s 内成功数
    results = [None] * 8

    def worker(i):
        results[i] = try_connect(NODE1, RATELIMITED_PORT, timeout=3.0)["connected"]

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(8)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0
    print("[218] ratelimited %d (max_conn_rate=1/s): %d/8 connected in %.1fs"
          % (RATELIMITED_PORT, sum(1 for x in results if x), elapsed))


def test_cluster_routing():
    received = []
    sub = new_client("lab-sub")
    sub.on_message = lambda cl, ud, msg: received.append(msg.payload.decode())
    sub.connect(NODE1, 1883, 5)
    sub.subscribe("lab/route", qos=1)
    sub.loop_start()
    time.sleep(1.0)
    pub = new_client("lab-pub")
    pub.connect(NODE2, 1883, 5)
    pub.loop_start()
    pub.publish("lab/route", "hello-from-node2", qos=1)
    time.sleep(2.0)
    pub.loop_stop(); pub.disconnect()
    sub.loop_stop(); sub.disconnect()
    print("[219] subscribe@node1 publish@node2 received ->", received)


def test_rate_limit():
    pub = new_client("lab-flood")
    pub.connect(NODE2, 1883, 5)
    pub.loop_start()
    n = 0
    deadline = time.time() + 3.0
    while time.time() < deadline:
        pub.publish("lab/flood", "x" * 64, qos=0)
        n += 1
        time.sleep(0.01)
    pub.loop_stop(); pub.disconnect()
    print("[218] flooded %d msgs in ~3s (limiter.messages_rate=2/s)" % n)


if __name__ == "__main__":
    test_allow_deny()
    test_conn_rate()
    test_cluster_routing()
    test_rate_limit()
