#!/usr/bin/env python3
"""只读探测：确认 Nacos 各端点在 8080(console) / 8848(/nacos) 上的归属。不打印任何口令。"""
import json
import subprocess

pw = usr = None
for ln in open("/opt/ypbin/ypbin-iot/deploy/.env", encoding="utf-8"):
    if ln.startswith("NACOS_ADMIN_PASSWORD="):
        pw = ln.split("=", 1)[1].strip()
    if ln.startswith("NACOS_ADMIN_USERNAME="):
        usr = ln.split("=", 1)[1].strip()


def login(base):
    r = subprocess.run(["curl", "-sS", "-m", "20", "-X", "POST", base + "/v3/auth/user/login",
                        "-H", "Content-Type: application/x-www-form-urlencoded",
                        "--data-urlencode", "username=" + usr, "--data-urlencode", "password@-"],
                       input=pw, capture_output=True, text=True)
    try:
        return json.loads(r.stdout).get("accessToken") or ""
    except Exception:
        return ""


t8848 = login("http://127.0.0.1:8848/nacos")
t8080 = login("http://127.0.0.1:8080")
print("login token len: 8848/nacos=%d  8080=%d" % (len(t8848), len(t8080)))

url = ("http://127.0.0.1:8848/nacos/v3/admin/ns/service/list"
       "?pageNo=1&pageSize=100&namespaceId=public&accessToken=" + t8848)
r = subprocess.run(["curl", "-sS", "-m", "15", url], capture_output=True, text=True)
try:
    d = json.loads(r.stdout)
    print("service/list(8848/nacos) totalCount =", (d.get("data") or {}).get("totalCount"))
except Exception:
    print("service/list(8848/nacos) 非 JSON:", r.stdout[:150])

for base, tok in (("http://127.0.0.1:8848/nacos", t8848), ("http://127.0.0.1:8080", t8080)):
    r = subprocess.run(["curl", "-sS", "-m", "15", "-X", "PUT",
                        base + "/v3/auth/user?username=__probe_nouser__",
                        "-H", "accessToken: " + tok,
                        "-H", "Content-Type: application/x-www-form-urlencoded",
                        "--data-urlencode", "newPassword@-"],
                       input="x", capture_output=True, text=True)
    print("PUT %-30s -> %s" % (base, r.stdout.strip()[:170]))
