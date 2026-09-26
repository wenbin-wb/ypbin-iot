#!/usr/bin/env python3
"""INTERNAL_TOKEN 再轮换（成对：deploy/.env + live Nacos 的 ypbin-common.yaml / ypbin-access.yaml）。

设计约束（本轮的凭据卫生硬规则）：
  * **绝不**把任何配置整段打印到 stdout/日志；只打印 **长度 / sha256 前 16 位 / grep 计数 / md5**。
  * 所有中间文件落在 600 权限目录内，用完即删；旧值受控留存（600）供一键回滚。
  * 替换前先**统计**旧值出现在哪些配置、各有几处；替换后断言「旧值 0 处、新值处数不变、新值不落注释行」。
  * POST 后**回读**（写到 600 文件）并**只比 md5 + 计数**。
  * dry-run 默认；加 --apply 才动生产。

用法（服务器上）：
    python3 rotate-internal-token.py            # dry-run：备份 + 统计，不改任何东西
    python3 rotate-internal-token.py --apply    # 执行轮换 + 重启 + 验证 + 生成回滚物
"""
import hashlib
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import time
import urllib.parse

APPLY = "--apply" in sys.argv
ROOT = "/opt/ypbin/ypbin-iot"
ENV_FILE = f"{ROOT}/deploy/.env"
NACOS_ADMIN = "http://127.0.0.1:8080"
NACOS_CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
GROUP = "DEFAULT_GROUP"
# 轮换前先把 7 份 live 配置全部备份（回滚要能整体还原）；实际改动只发生在含旧值的那些
CFGS = ["ypbin-common", "ypbin-gateway", "ypbin-auth", "ypbin-system",
        "ypbin-ai", "ypbin-iot", "ypbin-access"]
# INTERNAL_TOKEN 的消费者：nacos 先起，再业务（access 最后，它是新加的）
RESTART = [("ypbin-nacos", None), ("ypbin-auth", 18081), ("ypbin-system", 18082),
           ("ypbin-iot", 18084), ("ypbin-access", 18086)]

TS = time.strftime("%Y%m%d-%H%M%S")
W = f"/opt/ypbin/token-rotation-{TS}"
BEFORE = f"{W}/before/nacos"
AFTER = f"{W}/after"


def say(msg=""):
    print(msg, flush=True)


def fp(value):
    return hashlib.sha256(value.encode()).hexdigest()[:16]


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def count_hits(path, needle):
    """统计 needle 出现次数（不打印 needle）。"""
    out = run(["grep", "-c", "-F", needle, path])
    return int(out.stdout.strip() or 0)


def read_env_token():
    with open(ENV_FILE, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("INTERNAL_TOKEN="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("!! .env 里没有 INTERNAL_TOKEN")


def dump_config(cfg, dest):
    url = f"{NACOS_CLIENT}?dataId={cfg}.yaml&groupName={GROUP}&namespaceId="
    out = run(["curl", "-fsS", "-m", "25", url])
    if out.returncode != 0:
        raise SystemExit(f"!! dump {cfg} 失败")
    body = json.loads(out.stdout)
    if body.get("code") != 0:
        raise SystemExit(f"!! dump {cfg} 返回非成功信封：{body.get('message')}")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(body["data"]["content"])
    return dest


def nacos_login():
    out = run(["curl", "-sS", "-m", "20", "-X", "POST", f"{NACOS_ADMIN}/v3/auth/user/login",
               "-H", "Content-Type: application/x-www-form-urlencoded",
               "--data-urlencode", "username=" + os.environ.get("NACOS_ADMIN_USERNAME", "nacos"),
               "--data-urlencode", "password@-"], input=_nacos_password())
    token = (json.loads(out.stdout).get("accessToken") or "")
    if not token:
        raise SystemExit("!! Nacos 登录未拿到 accessToken")
    say(f"    nacos login ok  accessToken sha256[:16]={fp(token)}")
    return token


def publish(cfg, path, token):
    out = run(["curl", "-sS", "-m", "60", "-X", "POST", f"{NACOS_ADMIN}/v3/console/cs/config",
               "-H", f"accessToken: {token}",
               "--data-urlencode", f"dataId={cfg}.yaml",
               "--data-urlencode", f"groupName={GROUP}",
               "--data-urlencode", "type=yaml",
               "--data-urlencode", "namespaceId=",
               "--data-urlencode", f"content@{path}"])
    return out.stdout.strip()


def wait_health(container, port, needle, timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        out = run(["curl", "-s", "-m", "4", f"http://127.0.0.1:{port}/actuator/health"])
        if needle in out.stdout:
            return True
        time.sleep(3)
    return False


def _nacos_password():
    """取 Nacos 控制台口令：环境变量优先、其次 deploy/.env；只经 stdin 投递给 curl，不进 argv。

    2026-09-26 修：原实现在环境变量存在时直接调用自身（自我递归），
    只要调用方 export 了 NACOS_ADMIN_PASSWORD（回滚脚本正要求这么做）就必然 RecursionError。
    实际意图就是「取环境变量里的值」，这里直接返回。
    """
    env_password = os.environ.get("NACOS_ADMIN_PASSWORD")
    if env_password:
        return env_password
    for line in open("/opt/ypbin/ypbin-iot/deploy/.env", encoding="utf-8"):
        if line.startswith("NACOS_ADMIN_PASSWORD="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("!! 未配置 NACOS_ADMIN_PASSWORD")


def main():
    say("=" * 78)
    say(f"INTERNAL_TOKEN 再轮换   TS={TS}   mode={'APPLY' if APPLY else 'DRY-RUN'}")
    say("=" * 78)

    old = read_env_token()
    if len(old) != 64:
        raise SystemExit(f"!! .env 的 INTERNAL_TOKEN 长度异常（{len(old)}，期望 64），拒绝继续")
    new = secrets.token_hex(32)
    say(f"old: len={len(old)} sha256[:16]={fp(old)}")
    say(f"new: len={len(new)} sha256[:16]={fp(new)}")

    for d in (W, BEFORE, AFTER):
        os.makedirs(d, exist_ok=True)
        os.chmod(d, stat.S_IRWXU)
    say(f"\n[1] 备份到 {W}（700）")
    shutil.copy2(ENV_FILE, f"{W}/before/deploy.env.bak-{TS}")
    os.chmod(f"{W}/before/deploy.env.bak-{TS}", 0o600)
    say(f"    deploy/.env -> before/deploy.env.bak-{TS} (600)")
    for cfg in CFGS:
        p = dump_config(cfg, f"{BEFORE}/{cfg}.yaml")
        os.chmod(p, 0o600)

    say("\n[2] 旧值在哪些配置里（只看计数，不打印内容）")
    targets = []
    for cfg in CFGS:
        hits = count_hits(f"{BEFORE}/{cfg}.yaml", old)
        if hits:
            targets.append((cfg, hits))
        say(f"    {cfg:<16} old_hits={hits}  md5={md5(f'{BEFORE}/{cfg}.yaml')}")
    say(f"    => 需改配置: {[c for c, _ in targets] or '（无！说明 .env 与 Nacos 已不一致）'}")

    if not APPLY:
        say("\nDRY-RUN 结束：未改 .env、未写 Nacos。核对无误后加 --apply")
        return 0

    if not targets:
        raise SystemExit("!! 没有任何 live 配置含旧值——.env 与 Nacos 本来就不一致，先人工查清再轮换")

    say("\n[3] 更新 deploy/.env")
    lines = []
    with open(ENV_FILE, encoding="utf-8") as fh:
        for line in fh:
            lines.append(f"INTERNAL_TOKEN={new}\n" if line.startswith("INTERNAL_TOKEN=") else line)
    with open(ENV_FILE, "w", encoding="utf-8") as fh:
        fh.writelines(lines)
    os.chmod(ENV_FILE, 0o600)
    if count_hits(ENV_FILE, old) != 0 or count_hits(ENV_FILE, new) != 1:
        raise SystemExit("!! .env 替换后校验失败")
    say(f"    .env: old_hits=0 new_hits=1  md5={md5(ENV_FILE)}")

    say("\n[4] 替换 live 配置并 POST")
    token = nacos_login()
    changed = []
    for cfg, hits in targets:
        src, dst = f"{BEFORE}/{cfg}.yaml", f"{AFTER}/{cfg}.yaml"
        with open(src, encoding="utf-8") as fh:
            text = fh.read()
        out = text.replace(old, new)
        with open(dst, "w", encoding="utf-8") as fh:
            fh.write(out)
        os.chmod(dst, 0o600)
        if count_hits(dst, old) != 0:
            raise SystemExit(f"!! {cfg} 替换后仍含旧值，中止（已改的 .env 需回滚）")
        if count_hits(dst, new) != hits:
            raise SystemExit(f"!! {cfg} 新值处数 {count_hits(dst, new)} != 旧值处数 {hits}")
        # 真值不得落在注释行（上一轮踩过的坑）
        bad = run(["grep", "-n", "-F", new, dst])
        comment_lines = [ln for ln in bad.stdout.splitlines() if ":  #" in ln or ":#" in ln]
        if comment_lines:
            raise SystemExit(f"!! {cfg} 新值出现在注释行 {len(comment_lines)} 处，中止")
        resp = publish(cfg, dst, token)
        say(f"    {cfg:<16} new_hits={hits} POST -> {resp[:60]}")
        changed.append(cfg)

    say("\n[5] 回读校验（只比 md5 + 计数）")
    ok = True
    for cfg in changed:
        rb = f"{AFTER}/{cfg}.readback.yaml"
        time.sleep(2)
        dump_config(cfg, rb)
        os.chmod(rb, 0o600)
        same = md5(rb) == md5(f"{AFTER}/{cfg}.yaml")
        o, n = count_hits(rb, old), count_hits(rb, new)
        say(f"    {cfg:<16} md5_match={same} old_hits={o} new_hits={n} md5={md5(rb)}")
        ok = ok and same and o == 0 and n > 0
        os.remove(rb)
    if not ok:
        raise SystemExit("!! 回读校验不通过（Nacos 读后写有延迟，可稍后重跑校验；不要盲目继续）")
    say("    [OK] 回读与提交逐字一致、旧值 0 处、新值在位")

    say("\n[6] 逐个重启（nacos -> auth -> system -> iot -> access）")
    run(["docker", "restart", "ypbin-nacos"])
    for i in range(40):
        out = run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "5",
                   f"{NACOS_ADMIN}/v3/console/health/readiness"])
        if out.stdout.strip() == "200":
            break
        time.sleep(3)
    say(f"    ypbin-nacos readiness={out.stdout.strip()}")
    results = {}
    for name, port in RESTART[1:]:
        run(["docker", "restart", name])
        needle = '"status":"UP"' if name == "ypbin-iot" else '"code"'
        good = wait_health(name, port, needle)
        results[name] = good
        say(f"    {name:<16} ready={good} (:{port})")

    say("\n[7] 新旧值判据")
    checks = []
    for label, url in (("iot/epochs", "http://127.0.0.1:18084/internal/lease/epochs"),
                       ("system/permissions", "http://127.0.0.1:18082/internal/permissions?userId=1")):
        for which, value in (("NEW", new), ("OLD", old)):
            out = run(["curl", "-sS", "-m", "15", "-o", "/dev/null", "-w", "%{http_code}",
                       "-H", f"X-Internal-Token: {value}", url])
            body = run(["curl", "-sS", "-m", "15", "-H", f"X-Internal-Token: {value}", url]).stdout
            try:
                code = json.loads(body).get("code")
            except Exception:
                code = None
            say(f"    {label:<20} {which} http={out.stdout.strip()} R.code={code}")
            checks.append((label, which, out.stdout.strip(), code))
    say("\n[8] 健康与入口")
    for name, port in (("gateway", 18080), ("auth", 18081), ("system", 18082), ("iot", 18084), ("access", 18086)):
        out = run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "8",
                   f"http://127.0.0.1:{port}/actuator/health"])
        say(f"    {name:<10} :{port} health http={out.stdout.strip()}")
    ui = run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "10", "http://127.0.0.1:19000/"])
    say(f"    19000 (页面) http={ui.stdout.strip()}")
    bad = run(["curl", "-sS", "-m", "15", "-X", "POST", "http://127.0.0.1:19000/api/auth/login",
               "-H", "Content-Type: application/json",
               "-d", '{"username":"__no_such_user__","password":"__wrong__"}'])
    try:
        badcode = json.loads(bad.stdout).get("code")
        badmsg = json.loads(bad.stdout).get("message")
    except Exception:
        badcode, badmsg = None, (bad.stdout[:80])
    say(f"    错口令登录 R.code={badcode} msg={badmsg}")
    say("\n[9] 端口仍仅回环")
    ss = run(["ss", "-lntp"])
    leaked = [ln for ln in ss.stdout.splitlines()
              if any(f":{p}" in ln for p in (18081, 18082, 18084, 18086, 3306, 6379, 6667, 8848))
              and "127.0.0.1" not in ln]
    say(f"    非回环监听条数={len(leaked)}" + ("  !! " + str(leaked) if leaked else "  [OK]"))

    say("\n[10] 窗口内 ERROR 计数（重启后各容器）")
    for name, _ in RESTART:
        out = run(["docker", "logs", "--since", "10m", name])
        n = sum(1 for ln in (out.stdout + out.stderr).splitlines() if " ERROR " in ln)
        say(f"    {name:<16} ERROR={n}")

    say("\n[11] 生成一键回滚物")
    rb = f"{W}/rollback-{TS}.sh"
    with open(rb, "w", encoding="utf-8") as fh:
        fh.write(f"""#!/usr/bin/env bash
# 一键回滚 INTERNAL_TOKEN 轮换 {TS}（回到轮换前的值）
# 旧值受控留存：{W}/before/deploy.env.bak-{TS} 与 {BEFORE}/*.yaml（均 600）
set -euo pipefail
TS={TS}; ROOT={ROOT}; W=/opt/ypbin/token-rotation-$TS; NACOS={NACOS_ADMIN}
umask 077
say(){{ printf '%s\\n' "$*"; }}
install -m 600 "$W/before/deploy.env.bak-$TS" "$ROOT/deploy/.env"
say "已还原 deploy/.env"
if [ -z "${{NACOS_ADMIN_PASSWORD:-}}" ]; then say "!! 请先 export NACOS_ADMIN_PASSWORD（取 deploy/.env 的值，勿写进命令行）再跑本脚本"; exit 3; fi
T="$(printf '%s' "$NACOS_ADMIN_PASSWORD" | curl -fsS -m 20 -X POST "$NACOS/v3/auth/user/login" -H 'Content-Type: application/x-www-form-urlencoded' \\
  --data-urlencode 'username=${{NACOS_ADMIN_USERNAME:-nacos}}' --data-urlencode 'password@-' | sed -n 's/.*"accessToken":"\\([^"]*\\)".*/\\1/p')"
[ -n "$T" ] || {{ say "!! nacos 登录失败"; exit 3; }}
H="$W/.rb-hdr"; printf 'header = "accessToken: %s"\\n' "$T" > "$H"; chmod 600 "$H"; unset T
for c in {' '.join(CFGS)}; do
  [ -f "$W/before/nacos/$c.yaml" ] || continue
  curl -fsS -m 40 -K "$H" -X POST "$NACOS/v3/console/cs/config" \\
    --data-urlencode "dataId=$c.yaml" --data-urlencode "groupName={GROUP}" \\
    --data-urlencode "type=yaml" --data-urlencode "namespaceId=" \\
    --data-urlencode "content@$W/before/nacos/$c.yaml" >/dev/null && say "已还原 $c.yaml"
done
rm -f "$H"
docker restart ypbin-nacos >/dev/null
for i in $(seq 1 40); do [ "$(curl -s -o /dev/null -w '%{{http_code}}' -m 5 "$NACOS/v3/console/health/readiness")" = 200 ] && break; sleep 3; done
for s in ypbin-auth ypbin-system ypbin-iot ypbin-access; do docker restart "$s" >/dev/null; say "已重启 $s"; done
say "回滚完成；判据：用旧值 curl /internal/lease/epochs 应 200、用轮换后的新值应 401"
""")
    os.chmod(rb, 0o700)
    say(f"    {rb} (700)")

    say("\n" + "=" * 78)
    say(f"完成。旧值 sha256[:16]={fp(old)} -> 新值 sha256[:16]={fp(new)}")
    say(f"备份目录 {W} / 回滚 {rb}")
    say("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
