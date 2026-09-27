#!/usr/bin/env python3
"""把 Nacos 里某个 **YAML 配置的一个标量键**改成新值（显式 dump → 备份 → 只改那一行 → 发布 → 回读）。

为什么需要它：生产机的 `deploy/nacos/*.yaml` 只是**模板**，真正生效的是 Nacos 里的 **live** 配置，
而且 live 里有关键注释与生产改动过的键。整份覆盖模板 = 回退 live-only 内容；
用 `yaml.safe_load` 再 `dump` 又会把所有注释与顺序洗掉 ⇒ **只能按行改那一个键**。

用法（在服务器上；`.env` 只读入环境，不打印）：

    set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
    python3 patch-nacos-yaml-value.py --data-id ypbin-access.yaml \
        --key management.endpoint.health.show-details --value always              # dry-run
    python3 patch-nacos-yaml-value.py --data-id ypbin-access.yaml \
        --key management.endpoint.health.show-details --value always --apply      # 发布 + 回读校验
    # 键**尚不存在**时用 --add（在最近的已存在祖先下按缩进插入，中间层一并补齐）：
    python3 patch-nacos-yaml-value.py --data-id ypbin-access.yaml \
        --key management.health.redis.enabled --value false --add --apply

安全：口令经 stdin 传 curl（不进 argv）；accessToken 只写 600 的 curl 配置文件（`-K`）；
配置正文（含 `INTERNAL_TOKEN` 等真值）**不打印**，只打印长度、sha256 前缀与「被改的那一行」。
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time

CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
CONSOLE = "http://127.0.0.1:8080"
GROUP = "DEFAULT_GROUP"
BAK_DIR = "/opt/ypbin"


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def login() -> str:
    payload = "username={}&password={}".format(
        os.environ.get("NACOS_ADMIN_USERNAME", "nacos"),
        os.environ.get("NACOS_ADMIN_PASSWORD", "")).encode("utf-8")
    out = subprocess.run(
        ["curl", "-sS", "-m", "20", "-X", "POST", CONSOLE + "/v3/auth/user/login",
         "-H", "Content-Type: application/x-www-form-urlencoded", "--data-binary", "@-"],
        input=payload, capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    token = body.get("accessToken") or ""
    if not token:
        raise SystemExit(f"!! Nacos 登录未拿到 accessToken（keys={sorted(body)}）")
    print(f"    nacos login ok  accessToken sha256[:16]={sha(token)}")
    return token


def header_file(token: str) -> str:
    fd, path = tempfile.mkstemp(prefix="nacos-auth-")
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(f'header = "accessToken: {token}"\n')
    return path


def dump_live(data_id: str, header: str) -> str:
    url = f"{CLIENT}?dataId={data_id}&groupName={GROUP}&namespaceId="
    out = subprocess.run(["curl", "-fsS", "-m", "25", "-K", header, url],
                         capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    if body.get("code") != 0:
        raise SystemExit(f"!! 读配置失败：{body.get('message')}")
    return body["data"]["content"]


def publish(data_id: str, header: str, content: str) -> str:
    fd, path = tempfile.mkstemp(prefix="nacos-edit-", suffix=".yaml")
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(content)
    try:
        out = subprocess.run(
            ["curl", "-sS", "-m", "60", "-X", "POST", CONSOLE + "/v3/console/cs/config",
             "-K", header,
             "--data-urlencode", f"dataId={data_id}",
             "--data-urlencode", f"groupName={GROUP}",
             "--data-urlencode", "type=yaml",
             "--data-urlencode", "namespaceId=",
             "--data-urlencode", f"content@{path}"],
            capture_output=True, check=False)
        return out.stdout.decode("utf-8", "replace")
    finally:
        os.unlink(path)


def replace_scalar(content: str, key_path: str, value: str):
    """按缩进栈定位 key，只改该行的标量值（保留缩进、行尾注释与整份文件的其它内容）。

    @return (new_content, old_line, new_line)
    """
    wanted = key_path.split(".")
    stack = []            # [(indent, key)]
    hits = []
    lines = content.split("\n")
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if ":" not in stripped:
            continue
        key, _, rest = stripped.partition(":")
        key = key.strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = [entry[1] for entry in stack] + [key]
        if path == wanted:
            hits.append((index, indent, rest))
        if not rest.strip() or rest.strip().startswith("#"):
            stack.append((indent, key))
    if len(hits) != 1:
        return None, None, None, hits
    index, indent, rest = hits[0]
    comment = ""
    value_part = rest.strip()
    if " #" in value_part:
        value_part, _, comment_part = value_part.partition(" #")
        comment = "  #" + comment_part
    old_line = lines[index]
    new_line = " " * indent + key_path.split(".")[-1] + ": " + value + comment
    lines[index] = new_line
    return "\n".join(lines), old_line, new_line, hits


def insert_scalar(content: str, key_path: str, value: str):
    """键不存在时插入（在**最近的已存在祖先**下按缩进插入，中间层一并补齐）。

    @return (new_content, inserted_lines) 或 (None, None) 表示祖先也不存在
    """
    wanted = key_path.split(".")
    stack = []            # [(indent, key, line_index)]
    lines = content.split("\n")
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in stripped:
            continue
        indent = len(line) - len(line.lstrip(" "))
        key = stripped.partition(":")[0].strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = [entry[1] for entry in stack] + [key]
        if path == wanted[:len(path)]:
            stack.append((indent, key, index))
    # 找最深的已存在祖先
    for depth in range(len(wanted) - 1, 0, -1):
        ancestor = wanted[:depth]
        for indent, key, index in reversed(stack):
            if [entry[1] for entry in stack[:stack.index((indent, key, index)) + 1]] == ancestor:
                missing = wanted[depth:]
                inserted = []
                cursor = index + 1
                for offset, name in enumerate(missing):
                    pad = " " * (indent + 2 * (offset + 1))
                    new_line = f"{pad}{name}: {value}" if offset == len(missing) - 1 else f"{pad}{name}:"
                    lines.insert(cursor, new_line)
                    inserted.append(new_line)
                    cursor += 1
                return "\n".join(lines), inserted
    return None, None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-id", required=True)
    parser.add_argument("--key", required=True, help="点分键路径，例如 management.endpoint.health.show-details")
    parser.add_argument("--value", required=True)
    parser.add_argument("--add", action="store_true", help="键不存在时按缩进插入（默认拒绝新增）")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    if not os.environ.get("NACOS_ADMIN_PASSWORD"):
        print("!! 未提供 NACOS_ADMIN_PASSWORD（先 set -a; . deploy/.env; set +a）")
        return 2

    header = header_file(login())
    try:
        before = dump_live(args.data_id, header)
        print(f"    live before: bytes={len(before)} lines={before.count(chr(10)) + 1} sha256[:16]={sha(before)}")
        after, old_line, new_line, hits = replace_scalar(before, args.key, args.value)
        if after is None:
            if not args.add:
                raise SystemExit(f"!! 期望恰好命中 1 行，实际 {len(hits)} 行 ⇒ 拒绝改动"
                                 f"（要新增键请显式加 --add）")
            after, inserted = insert_scalar(before, args.key, args.value)
            if after is None:
                raise SystemExit("!! 键与祖先都不存在，拒绝插入（避免把配置插到错误层级）")
            print("    新增行：" + " / ".join(line.strip() for line in inserted))
        else:
            print(f"    改动行 before : {old_line.strip()}")
            print(f"    改动行 after  : {new_line.strip()}")
        print(f"    live after : bytes={len(after)} lines={after.count(chr(10)) + 1} sha256[:16]={sha(after)}")
        if after == before:
            print("    值未变化 ⇒ 无需发布")
            return 0

        if not args.apply:
            print("    dry-run：未写入 Nacos（加 --apply 才发布）")
            return 0

        ts = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
        bak = f"{BAK_DIR}/nacos-{args.data_id}.bak-{ts}"
        with open(bak, "w", encoding="utf-8") as fh:
            fh.write(before)
        os.chmod(bak, 0o600)
        print(f"    备份（600）：{bak}  bytes={len(before)}")

        resp = publish(args.data_id, header, after)
        print(f"    POST 响应（截断）：{resp[:200]}")
        if '"code":0' not in resp and '"code": 0' not in resp:
            raise SystemExit("!! 发布未返回 code=0 —— 不继续")

        # client API 有秒级传播延迟：轮询回读
        deadline = time.monotonic() + 15
        readback = dump_live(args.data_id, header)
        while readback != after and time.monotonic() < deadline:
            time.sleep(1)
            readback = dump_live(args.data_id, header)
        if readback != after:
            print(f"    ❗ 回读仍不一致（bytes={len(readback)} sha256[:16]={sha(readback)}）"
                  f"；POST 已成功，live 可能已生效 —— 请独立确认；确属未生效则还原 {bak}")
            return 4
        print(f"    ✅ 回读一致：bytes={len(readback)} sha256[:16]={sha(readback)}")
        print(f"    回滚：把 {bak} 的内容按同法 POST 回 {args.data_id}（或再跑一次本脚本把值改回）")
        return 0
    finally:
        os.unlink(header)


if __name__ == "__main__":
    sys.exit(main())
