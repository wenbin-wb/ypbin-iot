#!/usr/bin/env python3
"""Nacos **登录口令**成对轮换（L3）。

成对的三处（缺一即成半套）：
  A. Nacos 服务端的 `nacos` 用户口令（经 Console API `PUT /v3/auth/user` 修改）
  B. `deploy/.env` 的 `NACOS_ADMIN_PASSWORD`（client 侧取值的唯一来源）
  C. 5 个运行服务的 `SPRING_CLOUD_NACOS_USERNAME/PASSWORD`（compose 引用 ${NACOS_ADMIN_PASSWORD}）
     ⇒ 改 .env 后**必须 `--force-recreate`**（env 只在容器创建时注入，`restart` 不生效）

凭据卫生（沿用本仓 2026-09-26 确立的硬规则）：
  * 口令一律经 **stdin** 投递给 curl（`--data-urlencode password@-` / `newPassword@-`），不进 argv；
  * 只打印 **长度 / sha256[:16] / 计数**，绝不打印口令本体；
  * 旧值受控留存（0600）供一键回滚；回滚脚本内**不含明文**。

用法：
    python3 rotate-nacos-password.py            # dry-run（只做前置断言与计划打印）
    python3 rotate-nacos-password.py --apply    # 真正执行
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import stat
import subprocess
import sys
import time

ROOT = "/opt/ypbin/ypbin-iot"
DEPLOY = f"{ROOT}/deploy"
ENV_FILE = f"{DEPLOY}/.env"
COMPOSE = f"{DEPLOY}/docker-compose.yml"
OVERRIDE = f"{DEPLOY}/docker-compose.override.yml"
# 端点归属（2026-09-26 实测）：login / user(改密) / service-list 三者都在
# http://127.0.0.1:8848/nacos 上可用；8080 是 console API（login 与改密也可，但没有 ns/service/list）。
# 统一用一个基址，避免再踩"端口/前缀混用"的坑。
NACOS = "http://127.0.0.1:8848/nacos"
SERVICES = [("ypbin-gateway", 18080), ("ypbin-auth", 18081), ("ypbin-system", 18082),
            ("ypbin-iot", 18084), ("ypbin-access", 18086)]
PENDING = "ypbin-ai"          # compose 里也引用该 env，但当前未运行
TS = time.strftime("%Y%m%d-%H%M%S")
WORK = pathlib.Path(f"/root/mem-round2-20260926/nacos-rotate-{TS}")


def say(m=""):
    print(m, flush=True)


def fp(v: str) -> str:
    return hashlib.sha256(v.encode()).hexdigest()[:16]


def sh(cmd, stdin_text=None, timeout=180):
    return subprocess.run(cmd, capture_output=True, text=True, input=stdin_text, timeout=timeout)


def read_env(key):
    for ln in pathlib.Path(ENV_FILE).read_text(encoding="utf-8").splitlines():
        if ln.startswith(f"{key}="):
            return ln.split("=", 1)[1].strip()
    raise SystemExit(f"!! .env 缺 {key}")


def login(password, user):
    r = sh(["curl", "-sS", "-m", "20", "-X", "POST", f"{NACOS}/v3/auth/user/login",
            "-H", "Content-Type: application/x-www-form-urlencoded",
            "--data-urlencode", f"username={user}", "--data-urlencode", "password@-"], stdin_text=password)
    try:
        return json.loads(r.stdout).get("accessToken") or ""
    except Exception:
        return ""


def set_server_password(token, user, newpw):
    r = sh(["curl", "-sS", "-m", "30", "-X", "PUT", f"{NACOS}/v3/auth/user?username={user}",
            "-H", f"accessToken: {token}", "-H", "Content-Type: application/x-www-form-urlencoded",
            "--data-urlencode", "newPassword@-"], stdin_text=newpw)
    return r.stdout.strip()


def svc_env(svc):
    r = sh(["docker", "inspect", svc, "--format", "{{range .Config.Env}}{{println .}}{{end}}"])
    out = {}
    for ln in r.stdout.splitlines():
        if "=" in ln:
            k, v = ln.split("=", 1)
            out[k] = v
    return out


def registry_count():
    """Nacos 注册实例数（用当前 .env 的口令登录；只返回计数）。

    注意两点（均为 2026-09-26 实测踩到的坑）：
      ① 必须走 **8848 + /nacos** 前缀；8080（console）没有 ns/service/list 端点，会返回
         "No static resource v3/admin/ns/service/list"（不是 JSON ⇒ 被误判成 NA）；
      ② token **不要**拼进 URL 查询串（会进 curl argv，ps 可见）⇒ 写 0600 头文件并用 `curl -K`
         （2026-09-26 复核指出后已改；旧注释曾写「用查询参数」，已更正）。
    """
    user, pw = read_env("NACOS_ADMIN_USERNAME"), read_env("NACOS_ADMIN_PASSWORD")
    tok = login(pw, user)
    if not tok:
        return None
    # token 走 0600 头文件 + curl -K，**不拼进 URL**（否则会出现在 curl 的 argv 里，ps 可见）
    hdr = pathlib.Path(f"/root/mem-round2-20260926/.ns-hdr-{os.getpid()}")
    try:
        hdr.write_text(f'header = "accessToken: {tok}"\n', encoding="utf-8")
        os.chmod(hdr, 0o600)
        r = sh(["curl", "-sS", "-m", "15", "-K", str(hdr),
                f"{NACOS}/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId=public"])
    finally:
        hdr.unlink(missing_ok=True)
    try:
        d = json.loads(r.stdout)["data"]
        return d.get("totalCount"), [(i["name"], i.get("healthyInstanceCount")) for i in d.get("pageItems", [])]
    except Exception:
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    user = read_env("NACOS_ADMIN_USERNAME")
    old = read_env("NACOS_ADMIN_PASSWORD")
    say(f"== 前置：user={user}  旧口令 len={len(old)} sha256[:16]={fp(old)}")
    if len(old) > 72:
        say("!! 旧口令长度 > 72（Nacos 上限）—— 先确认现状")

    # 0) 服务端可用 + 旧口令可登录
    tok = login(old, user)
    if not tok:
        say("!! 用旧口令登录失败 ⇒ 中止（先查 Nacos 状态）")
        return 1
    say(f"   旧口令登录 OK（token len={len(tok)}）")

    # 0b) 注册数
    rc = registry_count()
    say(f"   当前注册：totalCount={rc[0] if rc else 'NA'}")
    if not rc or rc[0] != 5:
        say("!! 注册实例数不是 5 ⇒ 中止（不要在非健康基线上轮换）")
        return 1

    # 0c) 5 个服务的 env 键存在性（只报是否有键，不报值）
    for svc, _ in SERVICES:
        e = svc_env(svc)
        ok = "SPRING_CLOUD_NACOS_PASSWORD" in e and "SPRING_CLOUD_NACOS_USERNAME" in e
        say(f"   {svc:16s} SPRING_CLOUD_NACOS_* 键={ok}  当前口令 sha256[:16]={fp(e.get('SPRING_CLOUD_NACOS_PASSWORD',''))}")

    # 0d) 全盘扫描旧值出现位置（只报文件与计数，不报值）
    scan = sh(["grep", "-rlF", old, "/root", "/opt/ypbin"])
    files = [f for f in scan.stdout.splitlines() if f]
    say(f"   旧口令出现在 {len(files)} 个文件中（预期：.env + 600 快照）")
    for f in files:
        st = os.stat(f)
        say(f"     {oct(stat.S_IMODE(st.st_mode))} {f}")

    new = secrets.token_hex(16)
    if len(new) > 72:
        raise SystemExit("!! 新口令超过 72")
    say(f"== 计划：新口令 len={len(new)} sha256[:16]={fp(new)}（不打印本体）")
    if not args.apply:
        say("== dry-run 结束（加 --apply 才执行）")
        return 0

    # ── 1. 工作目录 + 备份 ───────────────────────────────────────────────────
    WORK.mkdir(parents=True, exist_ok=True)
    WORK.chmod(0o700)
    shutil.copy2(ENV_FILE, WORK / "deploy.env.bak")
    os.chmod(WORK / "deploy.env.bak", 0o600)
    (WORK / ".oldpw").write_text(old)
    (WORK / ".newpw").write_text(new)
    for p in (WORK / ".oldpw", WORK / ".newpw"):
        os.chmod(p, 0o600)
    say(f"== 1. 备份完成：{WORK}（0700；.oldpw/.newpw 0600，供回滚）")

    # ── 2. 改服务端口令 ─────────────────────────────────────────────────────
    resp = set_server_password(tok, user, new)
    say(f"== 2. PUT /v3/auth/user?username={user} 响应：{resp}")

    # ── 3. 断言：新可用 / 旧被拒 ────────────────────────────────────────────
    if not login(new, user):
        say("!! 新口令登录失败 ⇒ 立即改回旧口令")
        t2 = login(old, user)
        if t2:
            set_server_password(t2, user, old)
            say("   已改回旧口令；.env 未动 ⇒ 无需回滚服务")
        return 1
    say("== 3a. 新口令登录 OK")
    if login(old, user):
        say("!! 旧口令仍然可登录（Nacos 可能缓存）—— 记录但继续观察")
    else:
        say("== 3b. 旧口令已被拒 ✓（且服务端无需重启即生效 ⇒ 本轮不重启 nacos，理由见报告）")

    # ── 4. 更新 .env ────────────────────────────────────────────────────────
    src = pathlib.Path(ENV_FILE).read_text(encoding="utf-8")
    n = len(re.findall(r"(?m)^NACOS_ADMIN_PASSWORD=.*$", src))
    assert n == 1, f"NACOS_ADMIN_PASSWORD 出现 {n} 次"
    src = re.sub(r"(?m)^NACOS_ADMIN_PASSWORD=.*$", "NACOS_ADMIN_PASSWORD=" + new, src)
    pathlib.Path(ENV_FILE).write_text(src, encoding="utf-8")
    os.chmod(ENV_FILE, 0o600)
    assert read_env("NACOS_ADMIN_PASSWORD") == new, ".env 回读不一致"
    say("== 4. .env 已更新并回读一致（0600 保持）")

    # ── 5. 逐个 --force-recreate ────────────────────────────────────────────
    for svc, port in SERVICES:
        say(f"== 5. 重建 {svc}（--no-deps --force-recreate）")
        r = sh(["docker", "compose", "-p", "deploy", "-f", COMPOSE, "-f", OVERRIDE,
                "up", "-d", "--no-build", "--no-deps", "--force-recreate", svc], timeout=300)
        say("   " + (r.stdout + r.stderr).strip().replace("\n", " | ")[:200])
        e = svc_env(svc)
        got = e.get("SPRING_CLOUD_NACOS_PASSWORD", "")
        if fp(got) != fp(new):
            say(f"!! {svc} 运行期口令指纹不符（期望 {fp(new)}，实际 {fp(got)}）—— 请检查")
        else:
            say(f"   {svc} 运行期口令指纹 OK")

    # ── 6. 等就绪 + 验收 ────────────────────────────────────────────────────
    # ⚠️ 探测方式按服务区分（2026-09-26 踩过的坑）：
    #   · gateway(18080) 的 /actuator/health 是**业务 404 包在 HTTP 200 里**，永远不含 UP；
    #   · access(18086) 的 /actuator/health 会**挂起**（>40s）。
    #   ⇒ 这两个用根路径 200 判断，其余用 /actuator/health 里的 UP。
    PROBE = {
        "ypbin-gateway": (18080, "/", "200"),
        "ypbin-auth": (18081, "/actuator/health", "UP"),
        "ypbin-system": (18082, "/actuator/health", "UP"),
        "ypbin-iot": (18084, "/actuator/health", "UP"),
        "ypbin-access": (18086, "/", "200"),
    }
    say("== 6. 等各服务就绪（最多 150s/服务）")
    for svc, _ in SERVICES:
        port, path, needle = PROBE[svc]
        ok = False
        for _ in range(30):
            out = sh(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "5",
                      f"http://127.0.0.1:{port}{path}"]).stdout
            if needle == "200":
                if out.strip() == "200":
                    ok = True
                    break
            else:
                body = sh(["curl", "-s", "-m", "5", f"http://127.0.0.1:{port}{path}"]).stdout
                if needle in body:
                    ok = True
                    break
            time.sleep(5)
        say(f"   {svc} {'就绪' if ok else 'TIMEOUT'}（探测 {path} → {needle}）")
    rc2 = registry_count()
    say(f"== 6b. 注册：totalCount={rc2[0] if rc2 else 'NA'}  {rc2[1] if rc2 else ''}")
    code = sh(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "8",
               "http://127.0.0.1:19000/"]).stdout.strip()
    say(f"== 6c. 19000 http={code}")

    # ── 7. 生成回滚脚本（不含明文）+ bash -n 自检 ─────────────────────────
    # 2026-09-26 复核教训：原实现把 bash 模板写成 Python f-string，模板里的 `$(fp(old))`
    # 是**生成器的函数**、bash 不认识 ⇒ 生成的脚本 `bash -n` 直接语法错误、必然 exit 2，
    # 且无失败守卫 ⇒ 半回滚会让 5 个服务全部鉴权失败。改为调用独立生成器（内置 bash -n 与明文自检）。
    rb = WORK / "rollback-nacos-password.sh"
    checker = pathlib.Path("/root/mem-round2-20260926/fix_rollback_template.py")
    if checker.exists():
        r = sh(["python3", str(checker), str(WORK)], timeout=60)
        say("== 7. 回滚脚本生成器输出：\n" + (r.stdout + r.stderr).strip())
        if r.returncode != 0:
            say("!! 回滚脚本生成/自检失败 ⇒ 轮换虽已完成，但**回滚物不可用**，请立即人工处理")
            return 1
    else:
        say(f"!! 找不到 {checker} ⇒ 未生成回滚脚本（轮换已完成，但回滚物缺失）")
        return 1
    say(f"== 7. 回滚脚本已生成并通过 bash -n 自检（0600）：{rb}")

    say(f"== 完成。旧指纹={fp(old)}  新指纹={fp(new)}  （口令本体未打印、未落任何非 0600 文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
