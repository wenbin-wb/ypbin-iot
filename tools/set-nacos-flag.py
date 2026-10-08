#!/usr/bin/env python3
"""改 Nacos **live** 配置里「某个 2 空格缩进块」下的一个布尔 flag（默认 `ypbin-iot.yaml` 的 `alert.enabled`）。

用途：需要临时把一个开关翻过去做**故障注入/验收**（如把告警评估器停掉，验平台自告警的
`PLATFORM_EVALUATOR_STALLED`），做完再翻回来。live 改动会被 Nacos 推给运行中的进程并
**即时生效**（`@ConfigurationProperties` 会被 rebind；2026-10-08 实测 `Refresh keys changed:
[ypbin.alert.enabled]` → 下一个 tick 生效，**无需重启**）。

用法（在部署机 `/opt/ypbin/ypbin-iot` 下跑；口令从环境变量或 `deploy/.env` 取，绝不进 argv）：

    python3 tools/set-nacos-flag.py alert enabled false --dry   # 只看将要改哪一行
    python3 tools/set-nacos-flag.py alert enabled false         # 发布
    python3 tools/set-nacos-flag.py alert enabled true          # 翻回来

退出码：0 成功（含"已是目标值"）；非零 = 未发布（断言失败/发布失败/回读不一致）。

安全约束（2026-10-08 事故后定稿，事故记录见 `deploy/PROD-OPS-NOTES.md` 第 16 行）：
  1. **按行号改**：先定位块（`  <block>:`），再在块内定位 flag 行；**绝不 `str.replace(content,…)`**
     —— 事故：那种写法会命中全文**首个** `    enabled: true`，结果改掉的是 `  tenant:`
     段的 `enabled`（= 关租户隔离的开关），而不是目标行；
  2. **发布前逐行 diff，断言恰好 1 行变化**，否则拒绝发布；
  3. **发布后回读校验**（逐字节比对已发布内容），不一致即失败；
  4. 改前把 live 原文备份到 `backup/nacos/`；
  5. 只打印 diff 与状态，**不打印配置正文**（正文可能含落地值/占位符）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time

NACOS = "http://127.0.0.1:8848/nacos/v3"
ENV_FILE = "/opt/ypbin/ypbin-iot/deploy/.env"
BACKUP_DIR = "/opt/ypbin/ypbin-iot/backup/nacos"


def _password() -> str:
    """取 Nacos 控制台口令：环境变量优先，其次 deploy/.env；只经 stdin 投递，不进 argv。"""
    env_password = os.environ.get("NACOS_ADMIN_PASSWORD")
    if env_password:
        return env_password
    try:
        with open(ENV_FILE, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("NACOS_ADMIN_PASSWORD="):
                    return line.split("=", 1)[1].rstrip("\n")
    except OSError:
        pass
    return ""


def _login() -> str:
    payload = "username={}&password={}".format(
        os.environ.get("NACOS_ADMIN_USERNAME", "nacos"), _password()).encode("utf-8")
    out = subprocess.run(
        ["curl", "-sS", "-m", "20", "-X", "POST", NACOS + "/auth/user/login",
         "-H", "Content-Type: application/x-www-form-urlencoded", "--data-binary", "@-"],
        input=payload, capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    token = body.get("accessToken")
    if not token:
        raise SystemExit("登录失败（未取到 accessToken）")
    return token


def _dump_live(token: str, data_id: str, group: str, namespace: str) -> str:
    out = subprocess.run(
        ["curl", "-fsS", "-m", "25", "-H", "accessToken: " + token,
         "{}/admin/cs/config?dataId={}&groupName={}&namespaceId={}".format(
             NACOS, data_id, group, namespace)],
        capture_output=True, check=True)
    return json.loads(out.stdout.decode("utf-8"))["data"]["content"]


def _publish(token: str, data_id: str, group: str, namespace: str, content: str) -> None:
    """发布；正文经 stdin（curl --data-urlencode name@-）传入，避免进 argv/进程列表。"""
    script = [
        "curl", "-fsS", "-m", "30", "-X", "POST", NACOS + "/admin/cs/config",
        "-H", "Content-Type: application/x-www-form-urlencoded",
        "-H", "accessToken: " + token,
        "--data-urlencode", "dataId=" + data_id,
        "--data-urlencode", "groupName=" + group,
        "--data-urlencode", "namespaceId=" + namespace,
        "--data-urlencode", "type=yaml",
        "--data-urlencode", "content@-",
    ]
    out = subprocess.run(script, input=content.encode("utf-8"), capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    if body.get("code") != 0:
        raise SystemExit("发布失败：" + out.stdout.decode("utf-8")[:200])


def _edit_one_line(content: str, block: str, flag: str, want: str) -> tuple[str, list[tuple[int, str, str]]]:
    lines = content.split("\n")
    start = next((i for i, line in enumerate(lines) if line.rstrip() == "  %s:" % block), None)
    if start is None:
        raise SystemExit("未找到 '  %s:' 段（检查块名/缩进）" % block)
    target = None
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.strip() and not line.startswith("    "):  # 离开该块
            break
        if re.fullmatch(r"    %s:\s*(true|false)\s*" % flag, line):
            target = index
            break
    if target is None:
        raise SystemExit("未在 '%s' 段内找到布尔 flag '%s'" % (block, flag))
    new_lines = list(lines)
    new_lines[target] = re.sub(r"(    %s:\s*)(true|false)" % flag,
                              lambda match: match.group(1) + want, lines[target])
    new_content = "\n".join(new_lines)
    changed = [(i + 1, lines[i], new_lines[i]) for i in range(len(lines)) if lines[i] != new_lines[i]]
    return new_content, changed


def main() -> int:
    parser = argparse.ArgumentParser(description="改 Nacos live 配置里某个块下的布尔 flag")
    parser.add_argument("block", help="2 空格缩进的块名，如 alert / platform-alert / tenant")
    parser.add_argument("flag", help="块内的布尔键名，如 enabled / notify-enabled")
    parser.add_argument("value", choices=["true", "false"])
    parser.add_argument("--data-id", default="ypbin-iot.yaml")
    parser.add_argument("--group", default="DEFAULT_GROUP")
    parser.add_argument("--namespace", default="public")
    parser.add_argument("--dry", action="store_true", help="只打印将要改的行，不发布")
    args = parser.parse_args()

    token = _login()
    live = _dump_live(token, args.data_id, args.group, args.namespace)
    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "%s.raw.%s" % (args.data_id, time.strftime("%Y%m%d-%H%M%S")))
    with open(backup, "w", encoding="utf-8") as handle:
        handle.write(live)

    new_content, changed = _edit_one_line(live, args.block, args.flag, args.value)
    print("backup=%s lines=%d" % (backup, live.count("\n") + 1))
    print("逐行 diff（必须恰好 1 处）：%s" % [(n, a.strip(), b.strip()) for n, a, b in changed])
    if new_content == live:
        print("已是目标值，无需发布")
        return 0
    if len(changed) != 1:
        print("!! 变化行数 != 1，拒绝发布", file=sys.stderr)
        return 2
    if args.dry:
        print("dry-run，未发布")
        return 0

    _publish(token, args.data_id, args.group, args.namespace, new_content)
    readback = _dump_live(token, args.data_id, args.group, args.namespace)
    if readback != new_content:
        print("!! 回读与已发布内容不一致，请人工检查", file=sys.stderr)
        return 3
    print("已发布并回读一致：%s.%s=%s（第 %d 行）" % (args.block, args.flag, args.value, changed[0][0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
