#!/usr/bin/env python3
# =============================================================================
# EMQX 实验床 TLS 验证（issue #218 自签演练）
# 用法：/opt/emqx/venv/bin/python mqtt-tls-test.py <host> [port] [ca_crt]
# =============================================================================
import ssl
import sys
import time
import paho.mqtt.client as mqtt

HOST = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8883
CA = sys.argv[3] if len(sys.argv) > 3 else "/opt/emqx-lab/lab/certs/ca.crt"

connected = []
c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="lab-tls", protocol=mqtt.MQTTv311)
c.tls_set(ca_certs=CA, tls_version=ssl.PROTOCOL_TLS_CLIENT)
c.on_connect = lambda cl, ud, flags, rc, props=None: connected.append(getattr(rc, "value", rc))
c.connect(HOST, PORT, 10)
c.loop_start()
deadline = time.time() + 5
while time.time() < deadline and not connected:
    time.sleep(0.1)
c.loop_stop()
try:
    c.disconnect()
except Exception:
    pass
print("[218] TLS connect %s:%d with CA=%s -> connected=%s rc=%s"
      % (HOST, PORT, CA, bool(connected), connected[0] if connected else None))
