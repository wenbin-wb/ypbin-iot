#!/usr/bin/env python3
"""通用「成对密钥轮换」：deploy/.env 的某个键 + 所有含该值的 live Nacos 配置。

用于 GATEWAY_SIGN_TOKEN（gateway/auth/system/ai/iot/access 的 ypbin.cloud.feign.trusted-source-token）
与 INTERNAL_TOKEN（ypbin-common / ypbin-access 的 ypbin.internal.token）。

凭据卫生硬规则（本轮确立，必须遵守）：
  * **绝不**把任何配置整段打印到 stdout/日志；只打印 长度 / sha256 前 16 位 / grep 计数 / md5。
  * 中间文件一律 600，旧值受控留存供一键回滚。
  * 替换后断言：旧值 0 处、新值处数不变、新值不落注释行；POST 后回读**只比 md5 + 计数**。

用法（服务器上）：
    python3 rotate-secret.py --env-key GATEWAY_SIGN_TOKEN            # dry-run
    python3 rotate-secret.py --env-key GATEWAY_SIGN_TOKEN --apply
"""
import argparse
import hashlib
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import time

NACOS_ADMIN = "http://127.0.0.1:8080"
NACOS_CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
ROOT = "/opt/ypbin/ypbin-iot"
ENV_FILE = f"{ROOT}/deploy/.env"
GROUP = "DEFAULT_GROUP"
CFGS = ["ypbin-common", "ypbin-gateway", "ypbin-auth", "ypbin-system",
        "ypbin-ai", "ypbin-iot", "ypbin-access"]
# 谁持有该值 -> 需要重启（含 nacos 以便下游重新拉配置）
CONSUMERS = {
    "GATEWAY_SIGN_TOKEN": [("ypbin-nacos", None), ("ypbin-gateway", 18080), ("ypbin-auth", 18081),
                           ("ypbin-system", 18082), ("ypbin-iot", 18084), ("ypbin-access", 18086)],
    "INTERNAL_TOKEN": [("ypbin-nacos", None), ("ypbin-auth", 18081), ("ypbin-system", 18082),
                       ("ypbin-iot", 18084), ("ypbin-access", 18086)],
}


def say(m=""):
    print(m, flush=True)


def fp(v):
    return hashlib.sha256(v.encode()).hexdigest()[:16]


def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(65536), b""):
            h.update(c)
    return h.hexdigest()


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def hits(path, needle):
    return int(run(["grep", "-c", "-F", needle, path]).stdout.strip() or 0)


def read_env(key):
    with open(ENV_FILE, encoding="utf-8") as fh:
        for ln in fh:
            if ln.startswith(f"{key}="):
                return ln.split("=", 1)[1].strip()
    raise SystemExit(f"!! .env 缺 {key}")


def dump(cfg, dest):
    out = run(["curl", "-fsS", "-m", "25",
               f"{NACOS_CLIENT}?dataId={cfg}.yaml&groupName={GROUP}&namespaceId="])
    body = json.loads(out.stdout)
    if body.get("code") != 0:
        raise SystemExit(f"!! dump {cfg} 失败：{body.get('message')}")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(body["data"]["content"])


def login():
    out = run(["curl", "-sS", "-m", "20", "-X", "POST", f"{NACOS_ADMIN}/v3/auth/user/login",
               "-H", "Content-Type: application/x-www-form-urlencoded",
               "--data-urlencode", "username=" + os.environ.get("NACOS_ADMIN_USERNAME", "nacos") + r"", "--data-urlencode", "password=" + _nacos_password() + r""])
    tok = json.loads(out.stdout).get("accessToken") or ""
    if not tok:
        raise SystemExit("!! Nacos 登录失败")
    say(f"    nacos login ok sha256[:16]={fp(tok)}")
    return tok


def publish(cfg, path, tok):
    return run(["curl", "-sS", "-m", "60", "-X", "POST", f"{NACOS_ADMIN}/v3/console/cs/config",
                "-H", f"accessToken: {tok}", "--data-urlencode", f"dataId={cfg}.yaml",
                "--data-urlencode", f"groupName={GROUP}", "--data-urlencode", "type=yaml",
                "--data-urlencode", "namespaceId=", "--data-urlencode", f"content@{path}"]).stdout.strip()


def wait_health(name, port, timeout_s=200):
    needle = '\\"status\\":\\"UP\\"' if name != "ypbin-iot" else "UP"
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        body = run(["curl", "-s", "-m", "4", f"http://127.0.0.1:{port}/actuator/health"]).stdout
        if "UP" in body:
            return True
        time.sleep(3)
    return False


def _nacos_password():
    """从 deploy/.env 取 Nacos 控制台口令；脚本内不留值。"""
    if os.environ.get("NACOS_ADMIN_PASSWORD"):
        return _nacos_password()
    for line in open("/opt/ypbin/ypbin-iot/deploy/.env", encoding="utf-8"):
        if line.startswith("NACOS_ADMIN_PASSWORD="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("!! 未配置 NACOS_ADMIN_PASSWORD")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env-key", required=True, choices=sorted(CONSUMERS))
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    key = a.env_key
    TS = time.strftime("%Y%m%d-%H%M%S")
    W = f"/opt/ypbin/secret-rotation-{key}-{TS}"
    before, after = f"{W}/before", f"{W}/after"
    for d in (W, before, after):
        os.makedirs(d, exist_ok=True)
        os.chmod(d, stat.S_IRWXU)

    say("=" * 78)
    say(f"轮换 {key}   TS={TS}   mode={'APPLY' if a.apply else 'DRY-RUN'}")
    say("=" * 78)
    old = read_env(key)
    new = secrets.token_hex(32)
    say(f"old len={len(old)} sha256[:16]={fp(old)}")
    say(f"new len={len(new)} sha256[:16]={fp(new)}")

    shutil.copy2(ENV_FILE, f"{before}/deploy.env.bak-{TS}")
    os.chmod(f"{before}/deploy.env.bak-{TS}", 0o600)
    for c in CFGS:
        p = f"{before}/{c}.yaml"
        dump(c, p)
        os.chmod(p, 0o600)
    say(f"\n[1] 备份 {W}（旧值受控留存，700/600）")

    say("\n[2] 旧值分布（只看计数/md5）")
    targets = []
    for c in CFGS:
        n = hits(f"{before}/{c}.yaml", old)
        if n:
            targets.append((c, n))
        say(f"    {c:<16} hits={n} md5={md5(f'{before}/{c}.yaml')}")
    say(f"    => 需改: {[c for c, _ in targets] or '（无）'}")
    if not a.apply:
        say("\nDRY-RUN 结束：未改 .env、未写 Nacos")
        return 0
    if not targets:
        raise SystemExit("!! 无任何配置含旧值，先人工查清")

    say("\n[3] 改 deploy/.env")
    lines = [f"{key}={new}\n" if ln.startswith(f"{key}=") else ln
             for ln in open(ENV_FILE, encoding="utf-8")]
    open(ENV_FILE, "w", encoding="utf-8").writelines(lines)
    os.chmod(ENV_FILE, 0o600)
    if hits(ENV_FILE, old) or hits(ENV_FILE, new) != 1:
        raise SystemExit("!! .env 校验失败")
    say(f"    .env old=0 new=1 md5={md5(ENV_FILE)}")

    say("\n[4] 替换 + POST")
    tok = login()
    changed = []
    for c, n in targets:
        src, dst = f"{before}/{c}.yaml", f"{after}/{c}.yaml"
        out = open(src, encoding="utf-8").read().replace(old, new)
        open(dst, "w", encoding="utf-8").write(out)
        os.chmod(dst, 0o600)
        if hits(dst, old):
            raise SystemExit(f"!! {c} 仍含旧值")
        if hits(dst, new) != n:
            raise SystemExit(f"!! {c} 新值处数不符（{hits(dst, new)} != {n}）")
        bad = [ln for ln in run(["grep", "-n", "-F", new, dst]).stdout.splitlines()
               if ":#" in ln or ": #" in ln]
        if bad:
            raise SystemExit(f"!! {c} 新值落进注释行 {len(bad)} 处")
        say(f"    {c:<16} {publish(c, dst, tok)[:55]}")
        changed.append(c)

    say("\n[5] 回读校验（md5 + 计数）")
    ok = True
    for c in changed:
        rb = f"{after}/{c}.readback.yaml"
        time.sleep(2)
        dump(c, rb)
        os.chmod(rb, 0o600)
        same, o, n = md5(rb) == md5(f"{after}/{c}.yaml"), hits(rb, old), hits(rb, new)
        say(f"    {c:<16} md5_match={same} old={o} new={n}")
        ok = ok and same and o == 0 and n > 0
        os.remove(rb)
    if not ok:
        raise SystemExit("!! 回读不通过（Nacos 读后写有延迟，可稍后重跑校验）")
    say("    [OK]")

    say("\n[6] 逐个重启")
    cons = CONSUMERS[key]
    run(["docker", "restart", "ypbin-nacos"])
    code = ""
    for _ in range(40):
        code = run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "5",
                    f"{NACOS_ADMIN}/v3/console/health/readiness"]).stdout.strip()
        if code == "200":
            break
        time.sleep(3)
    say(f"    ypbin-nacos readiness={code}")
    for name, port in cons[1:]:
        run(["docker", "restart", name])
        good = wait_health(name, port)
        say(f"    {name:<16} ready={good}")

    say("\n[7] 判据")
    for p in (18080, 18081, 18082, 18084, 18086):
        say(f"    :{p} health http={run(['curl','-s','-o','/dev/null','-w','%{http_code}','-m','8',f'http://127.0.0.1:{p}/actuator/health']).stdout.strip()}")
    say(f"    19000 页面 http={run(['curl','-s','-o','/dev/null','-w','%{http_code}','-m','10','http://127.0.0.1:19000/']).stdout.strip()}")
    rb = f"{W}/rollback-{TS}.sh"
    with open(rb, "w", encoding="utf-8") as fh:
        fh.write(f"""#!/usr/bin/env bash
# 一键回滚 {key} 轮换 {TS}
set -euo pipefail
TS={TS}; W=/opt/ypbin/secret-rotation-{key}-$TS; ROOT={ROOT}; NACOS={NACOS_ADMIN}
umask 077; say(){{ printf '%s\\n' "$*"; }}
install -m 600 "$W/before/deploy.env.bak-$TS" "$ROOT/deploy/.env"; say "已还原 deploy/.env"
T="$(curl -fsS -m 20 -X POST "$NACOS/v3/auth/user/login" -H 'Content-Type: application/x-www-form-urlencoded' \\
  --data-urlencode 'username=${NACOS_ADMIN_USERNAME:-nacos}' --data-urlencode 'password=$NACOS_ADMIN_PASSWORD' | sed -n 's/.*"accessToken":"\\([^"]*\\)".*/\\1/p')"
[ -n "$T" ] || {{ say "!! nacos 登录失败"; exit 3; }}
H="$W/.h"; printf 'header = "accessToken: %s"\\n' "$T" > "$H"; chmod 600 "$H"; unset T
for c in {' '.join(c for c, _ in targets)}; do
  curl -fsS -m 40 -K "$H" -X POST "$NACOS/v3/console/cs/config" \\
    --data-urlencode "dataId=$c.yaml" --data-urlencode "groupName={GROUP}" \\
    --data-urlencode "type=yaml" --data-urlencode "namespaceId=" \\
    --data-urlencode "content@$W/before/$c.yaml" >/dev/null && say "已还原 $c.yaml"
done
rm -f "$H"
docker restart ypbin-nacos >/dev/null
for i in $(seq 1 40); do [ "$(curl -s -o /dev/null -w '%{{http_code}}' -m 5 "$NACOS/v3/console/health/readiness")" = 200 ] && break; sleep 3; done
for s in {' '.join(n for n, _ in cons[1:])}; do docker restart "$s" >/dev/null; say "已重启 $s"; done
say "回滚完成"
""")
    os.chmod(rb, 0o700)
    say(f"\n完成 {fp(old)} -> {fp(new)}；回滚 {rb}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
