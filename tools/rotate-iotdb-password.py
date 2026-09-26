#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""IoTDB 口令轮换的「配置侧」步骤：Nacos live `ypbin-iot.yaml` + `deploy/.env`。

**只做配置**：IoTDB 内的口令由 `ALTER USER` 改（见 docs/DEPLOY-TIMESERIES.md §6.1 **步骤 ②**）——
本脚本负责把 Nacos 活配置与 `deploy/.env` 同步到同一个新值（步骤 ③）。

凭据卫生硬规则（与本仓 `tools/rotate-*.py` 一致，必须遵守）：
  * **绝不**打印任何口令值；输出只有 长度 / sha256 前 16 位 / 命中布尔 / 计数。
  * 新口令只从 **600 文件**读（`$W/.new-pw`），不经 argv、不进日志。
  * 改前先 dump live 到 `$W/before-ypbin-iot.yaml`（600）供一键回滚。
  * **只改值**：替换后断言「除 password 那一行以外，逐字未变」，否则拒绝发布。
  * 默认 dry-run；真要发布必须显式 `--apply`。

Nacos 凭据来源（与 `tools/rotate-internal-token.py` / `rotate-secret.py` 同口径）：
  环境变量优先（`NACOS_ADMIN_USERNAME` / `NACOS_ADMIN_PASSWORD`），其次 `deploy/.env`，用户名默认 `nacos`。
  ⚠️ `deploy/.env` 由 `install.sh` 生成时**不含**这两个键（只含 `NACOS_ADMIN_*` 之外的键与
  `NACOS_AUTH_*`），所以生产上要么 export 这两个变量、要么把它们写进 `.env`。

用法（部署机上，`$W` = 700 工作目录，内含 600 的 `.new-pw`）：
    W=/opt/ypbin/iotdb-rotation-$(date +%Y%m%d-%H%M%S); install -d -m 700 "$W"
    printf '%s' "$NEW" > "$W/.new-pw" && chmod 600 "$W/.new-pw"
    python3 tools/rotate-iotdb-password.py "$W"            # dry-run：只 dump + 断言
    python3 tools/rotate-iotdb-password.py "$W" --apply    # 发布 Nacos + 改 deploy/.env

备注：读 live 用的是 Nacos **控制台 API**（`http://127.0.0.1:8080/v3/console/cs/config`，
与 docs/DEPLOY-BACKEND.md §5.6.2 的实测口径逐字一致）。⚠️ `--nacos` 只换 base（host:port），
请求**路径**固定是 console 路径 ⇒ 指到 `http://127.0.0.1:8848/nacos`（client API）不会自动切换，会 404。
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = "/opt/ypbin/ypbin-iot"
ENV_FILE = f"{ROOT}/deploy/.env"
DATA_ID = "ypbin-iot.yaml"
GROUP = "DEFAULT_GROUP"
# 允许的字符集：口令会写进 YAML 标量与 .env，`#`/引号/空格/换行都会改变解析结果 ⇒ 只收安全字符集
PASSWORD_PATTERN = re.compile(r"[A-Za-z0-9._~-]+")


def fp(value):
    return hashlib.sha256(value.encode()).hexdigest()[:16]


def read_env(key, default=None):
    with open(ENV_FILE, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith(key + "="):
                return line.strip().split("=", 1)[1].strip().strip('"').strip("'")
    return default


def nacos_env(name, default=None):
    """env 优先 → deploy/.env → 默认值（与兄弟工具同口径）。"""
    return os.environ.get(name) or read_env(name, default)


def request(nacos, path, data=None, headers=None):
    req = urllib.request.Request(nacos + path, data=data, headers=headers or {})
    return urllib.request.urlopen(req, timeout=20).read()


def fetch_live(nacos, token):
    query = urllib.parse.urlencode({"dataId": DATA_ID, "groupName": GROUP, "namespaceId": ""})
    body = request(nacos, "/v3/console/cs/config?" + query, None, {"accessToken": token})
    return json.loads(body)["data"]["content"]


def publish(nacos, token, content):
    body = urllib.parse.urlencode({"dataId": DATA_ID, "groupName": GROUP, "type": "yaml",
                                   "namespaceId": "", "content": content}).encode()
    return request(nacos, "/v3/console/cs/config", body,
                   {"accessToken": token, "Content-Type": "application/x-www-form-urlencoded"})


def strip_password_lines(text):
    return "\n".join(line for line in text.split("\n")
                     if not re.match(r"^\s+password:\s", line))


def write_private(path, text):
    """原子写 + 0600：先写同目录临时文件（600），再 os.replace（避免半截文件与瞬时权限窗口）。"""
    tmp = path + ".tmp"
    with open(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w",
              encoding="utf-8") as handle:
        handle.write(text)
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workdir", help="700 工作目录（内含 600 的 .new-pw）")
    parser.add_argument("--nacos", default="http://127.0.0.1:8080")
    parser.add_argument("--apply", action="store_true", help="真正发布（默认 dry-run）")
    args = parser.parse_args()

    new_pw = open(os.path.join(args.workdir, ".new-pw"), encoding="utf-8").read().strip()
    if not new_pw:
        raise SystemExit("!! .new-pw 为空")
    if not PASSWORD_PATTERN.fullmatch(new_pw):
        # 含 `#`/引号/空格/换行会让 YAML 或 .env 解析出**另一个**值（假成功），直接拒绝
        raise SystemExit("!! 新口令含不安全字符（只允许 A-Za-z0-9 . _ ~ -）；拒绝写入配置")

    user = nacos_env("NACOS_ADMIN_USERNAME", "nacos")
    admin_pw = nacos_env("NACOS_ADMIN_PASSWORD")
    if not admin_pw:
        raise SystemExit("!! 缺 Nacos 控制台口令：请 export NACOS_ADMIN_PASSWORD 或在 deploy/.env 里设置")
    login = urllib.parse.urlencode({"username": user, "password": admin_pw}).encode()
    token = json.loads(request(args.nacos, "/v3/auth/user/login", login,
                               {"Content-Type": "application/x-www-form-urlencoded"}))["accessToken"]
    print("[0] Nacos 登录 ok（user=%s，口令 sha256[:16]=%s）" % (user, fp(admin_pw)))

    src = fetch_live(args.nacos, token)
    before = os.path.join(args.workdir, "before-" + DATA_ID)
    write_private(before, src)
    print("[1] live dump ok → %s（600）len=%d sha256[:16]=%s" % (before, len(src), fp(src)))

    lines = src.split("\n")
    pw_indexes = [i for i, line in enumerate(lines) if re.match(r"^\s+password:\s", line)]
    if len(pw_indexes) != 1:
        raise SystemExit("!! 期望 live 里只有 1 行 password:，实际 %d" % len(pw_indexes))
    index = pw_indexes[0]
    old_pw = re.match(r"^(\s+password:\s*)(\S.*)$", lines[index]).group(2).strip()
    lines[index] = re.sub(r"^(\s+password:\s*).*$", lambda m: m.group(1) + new_pw, lines[index])
    out = "\n".join(lines)
    if strip_password_lines(out) != strip_password_lines(src):
        raise SystemExit("!! 除 password 值以外内容被改动 —— 拒绝发布")
    print("[2] 值级替换 ok: 旧值 len=%d sha256[:16]=%s → 新值 len=%d sha256[:16]=%s"
          % (len(old_pw), fp(old_pw), len(new_pw), fp(new_pw)))

    if not args.apply:
        print("[3] dry-run：未发布。加 --apply 才写 Nacos 与 deploy/.env")
        return

    # ⚠️ 先验后发：`.env` 的读取/重复校验/备份全部放在 publish() **之前**——
    #    否则「Nacos 已切新口令、而 .env 因重复键/畸形被拒仍生效旧值」会留下
    #    app(新)/iotdb-init(旧) 的不一致（正是步骤 ②.5 硬门要防的那一类）。
    env_lines = open(ENV_FILE, encoding="utf-8").read().split("\n")
    write_private(os.path.join(args.workdir, "before-deploy.env"), "\n".join(env_lines))
    hits = [i for i, line in enumerate(env_lines) if line.startswith("IOTDB_PASSWORD=")]
    if len(hits) > 1:
        # compose 对重复键取**最后一个**：只改第一行会留下一个仍然生效的旧值（假成功）
        raise SystemExit("!! deploy/.env 里 IOTDB_PASSWORD 出现 %d 次，请先手工去重再轮换（未发布）"
                         % len(hits))
    if hits:
        env_lines[hits[0]] = "IOTDB_PASSWORD=" + new_pw
    else:
        env_lines.append("IOTDB_PASSWORD=" + new_pw)

    publish(args.nacos, token, out)
    live_after = fetch_live(args.nacos, token)
    live_line = [ln for ln in live_after.split("\n") if re.match(r"^\s+password:\s", ln)][0]
    assert live_line.strip().endswith(new_pw), "!! 发布后回读：live 里不是新值"
    assert not live_line.strip().endswith(old_pw), "!! 发布后回读：live 里仍是旧值"
    assert strip_password_lines(live_after) == strip_password_lines(src), "!! 除值外被改动"
    print("[3] 发布后回读：新值命中=True 旧值命中=False 除值外未变=True")

    write_private(ENV_FILE, "\n".join(env_lines))
    after_lines = open(ENV_FILE, encoding="utf-8").read().splitlines()
    env_ok = ("IOTDB_PASSWORD=" + new_pw) in after_lines
    env_old_ok = ("IOTDB_PASSWORD=" + old_pw) in after_lines
    print("[4] deploy/.env：IOTDB_PASSWORD 命中新值=%s 仍含旧值=%s（原有 %d 处）；文件已 600"
          % (env_ok, env_old_ok, len(hits)))
    assert env_ok and not env_old_ok, "!! .env 落盘复核失败"
    print("提醒：IoTDB 内的口令必须已由 ALTER USER 改成同一个值，否则下一步应用会 801")
    print("提醒：随后要重启 ypbin-iot 才会重新拉取 Nacos 配置（docker restart ypbin-iot）")


if __name__ == "__main__":
    sys.exit(main())
