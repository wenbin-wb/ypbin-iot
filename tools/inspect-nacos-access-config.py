#!/usr/bin/env python3
"""只读：dump Nacos 的 ypbin-access.yaml live 配置，并把 .env 里的真实凭据值替换掉后打印。

存在的意义：改 live 配置前要看清它到底有什么（live 与仓库模板历史上已分叉），
但又**绝不能把含凭据的正文打印出来**（R6/凭据纪律）。所以：读 .env 的凭据值 →
按值在正文里替换成 «REDACTED» → 再打印。任何时刻不打印凭据本身。
"""
import json
import os
import subprocess
import tempfile

CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
CONSOLE = "http://127.0.0.1:8080"
SECRET_KEYS = ("INTERNAL_TOKEN", "GATEWAY_SIGN_TOKEN", "NACOS_ADMIN_PASSWORD",
               "MYSQL_ROOT_PASSWORD", "REDIS_PASSWORD", "IOTDB_PASSWORD",
               "NACOS_AUTH_TOKEN", "NACOS_AUTH_IDENTITY_VALUE", "AI_MODEL_SECRET_KEY")

payload = "username={}&password={}".format(
    os.environ.get("NACOS_ADMIN_USERNAME", "nacos"),
    os.environ.get("NACOS_ADMIN_PASSWORD", "")).encode("utf-8")
out = subprocess.run(
    ["curl", "-sS", "-m", "20", "-X", "POST", CONSOLE + "/v3/auth/user/login",
     "-H", "Content-Type: application/x-www-form-urlencoded", "--data-binary", "@-"],
    input=payload, capture_output=True, check=True)
token = json.loads(out.stdout.decode("utf-8"))["accessToken"]
fd, header = tempfile.mkstemp(prefix="nacos-auth-")
os.fchmod(fd, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as fh:
    fh.write('header = "accessToken: %s"\n' % token)
try:
    out = subprocess.run(
        ["curl", "-fsS", "-m", "25", "-K", header,
         CLIENT + "?dataId=ypbin-access.yaml&groupName=DEFAULT_GROUP&namespaceId="],
        capture_output=True, check=True)
finally:
    os.unlink(header)

content = json.loads(out.stdout.decode("utf-8"))["data"]["content"]
redacted = content
for key in SECRET_KEYS:
    value = os.environ.get(key, "")
    if value:
        redacted = redacted.replace(value, "«REDACTED»")
print("lines=%d bytes_redacted=%d" % (redacted.count("\n") + 1, len(redacted)))
print("--- live content (凭据按值脱敏) ---")
for number, line in enumerate(redacted.splitlines(), 1):
    print("%3d|%s" % (number, line))
