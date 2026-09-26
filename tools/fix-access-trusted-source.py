#!/usr/bin/env python3
"""补 ypbin-access.yaml 的 trusted-source 两键（显式 dump -> diff -> POST），不依赖 install.sh。

用法（在服务器上）：
    set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
    python3 fix-access-trusted-source.py            # dry-run：只 dump/构造/校验，不写 Nacos
    python3 fix-access-trusted-source.py --apply    # 真正 POST 并回读校验

安全：token 只从环境变量 GATEWAY_SIGN_TOKEN 取，只写入 value 行，输出一律脱敏。
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.parse

DATA_ID = "ypbin-access.yaml"
GROUP = "DEFAULT_GROUP"
CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
CONSOLE = "http://127.0.0.1:8080"
KEY = "trusted-source-token"
REQUIRE_KEY = "require-trusted-source"
ANCHOR = "  # access 是下游服务"
ENV_FILE = "/opt/ypbin/ypbin-iot/deploy/.env"
BAK_DIR = "/opt/ypbin"

APPLY = "--apply" in sys.argv


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def dump_live() -> str:
    url = f"{CLIENT}?dataId={DATA_ID}&groupName={GROUP}&namespaceId="
    out = subprocess.run(["curl", "-fsS", "-m", "15", url], capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    assert body.get("code") == 0, f"nacos read failed: {body.get('message')}"
    return body["data"]["content"]


def login() -> str:
    """取 Nacos console accessToken；打印时脱敏。"""
    out = subprocess.run(
        ["curl", "-sS", "-m", "15", "-X", "POST", f"{CONSOLE}/v3/auth/user/login",
         "-H", "Content-Type: application/x-www-form-urlencoded",
         "--data-urlencode", "username=" + os.environ.get("NACOS_ADMIN_USERNAME", "nacos"),
         "--data-urlencode", "password@-"],
        capture_output=True, check=True, input=_nacos_password().encode("utf-8"))
    body = json.loads(out.stdout.decode("utf-8"))
    token = body.get("accessToken")
    assert token, f"nacos login failed (no accessToken): keys={sorted(body)}"
    print(f"    nacos login ok, accessToken=sha256:{sha(token)}")
    return token


def publish_via_stdin(token: str, content: str, path: str) -> str:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(content)
    out = subprocess.run(
        ["curl", "-sS", "-m", "60", "-X", "POST", f"{CONSOLE}/v3/console/cs/config",
         "-H", f"accessToken: {token}",
         "--data-urlencode", f"dataId={DATA_ID}",
         "--data-urlencode", f"groupName={GROUP}",
         "--data-urlencode", "type=yaml",
         "--data-urlencode", "namespaceId=",
         "--data-urlencode", f"content@{path}"],
        capture_output=True, check=False)
    print(f"    POST http_code={out.returncode} body={out.stdout.decode('utf-8', 'replace')[:200]}")
    return out.stdout.decode("utf-8", "replace")


def build_block(token: str) -> list:
    return [
        "  # 身份头来源校验：仅信任带网关签名标记的身份头；未配置密钥则拒绝启动（fail-fast）",
        "  # （access 不引 ypbin-common.yaml，拿不到共享配置里的同名键 ⇒ 必须在本 Data ID 里显式声明）",
        "  cloud:",
        "    feign:",
        f"      {KEY}: {token}",
        f"      {REQUIRE_KEY}: true",
    ]


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


def main() -> int:
    token = os.environ.get("GATEWAY_SIGN_TOKEN", "")
    assert token and "${" not in token, "GATEWAY_SIGN_TOKEN 未设置或是占位符，拒绝继续"

    ts = time.strftime("%Y%m%d-%H%M%S")
    print(f"=== 1) dump live {DATA_ID} (TS={ts}) ===")
    live = dump_live()
    live_path = f"/tmp/access-live-{ts}.yaml"
    open(live_path, "w", encoding="utf-8").write(live)
    live_sha = sha(live)
    print(f"    lines={live.count(chr(10)) + 1} sha256[:16]={live_sha}")

    bak = f"{BAK_DIR}/nacos-{DATA_ID}.bak-{ts}"
    with open(bak, "w", encoding="utf-8") as fh:
        fh.write(live)
    os.chmod(bak, 0o600)
    print(f"    快照: {bak} (0600)")

    print("=== 2) 构造新内容（只插入缺失块）===")
    lines = live.split("\n")
    if any(line.startswith(f"      {KEY}:") for line in lines):
        print("    !! live 已含该键 —— 无需修复，退出")
        return 0
    idx = [i for i, line in enumerate(lines) if line.startswith(ANCHOR)]
    assert len(idx) == 1, f"锚点不唯一/未找到：{idx}"
    block = build_block(token)
    new_lines = lines[:idx[0]] + block + lines[idx[0]:]
    new = "\n".join(new_lines)

    new_path = f"/tmp/access-new-{ts}.yaml"
    open(new_path, "w", encoding="utf-8").write(new)
    print(f"    新内容: {new_path} lines={new.count(chr(10)) + 1}")

    print("=== 3) 校验（插入之外逐字未变）===")
    # 3a 逐字 diff：新内容删除插入块后必须与 live 完全一致
    rebuilt = new_lines[:idx[0]] + new_lines[idx[0] + len(block):]
    assert rebuilt == lines, "!! 除插入块外内容发生变化"
    print("    [OK] 剥离插入块后与 live 逐字一致")

    # 3b 两键在位且值正确（按哈希比对，不打印明文）
    val_lines = [l for l in new_lines if l.startswith(f"      {KEY}: ")]
    assert len(val_lines) == 1, f"!! {KEY} 行数异常: {len(val_lines)}"
    got = val_lines[0].split(": ", 1)[1]
    assert got == token, "!! 写入值与 .env 不一致"
    assert "${" not in got, "!! 写入的仍是占位符"
    require_lines = [l for l in new_lines if l.startswith(f"      {REQUIRE_KEY}: ")]
    assert require_lines == [f"      {REQUIRE_KEY}: true"], f"!! {REQUIRE_KEY} 异常: {require_lines}"
    print(f"    [OK] {KEY} 值非占位符且与 .env 的 GATEWAY_SIGN_TOKEN 一致 (sha256:{sha(got)})")
    print(f"    [OK] {REQUIRE_KEY}: true")

    # 3c 真值只出现在 value 行，绝不落进注释
    hits = [l for l in new_lines if token in l]
    assert len(hits) == 1 and hits[0].startswith(f"      {KEY}: "), \
        f"!! 真值出现在 {len(hits)} 行（含注释行），拒绝 POST"
    print("    [OK] 真值仅出现 1 次，且是 value 行（未污染注释）")

    # 3d 其余占位符只允许带默认值的环境变量形态
    leftovers = sorted({l.split("${", 1)[1].split("}")[0] for l in new_lines if "${" in l})
    assert leftovers == ["ACCESS_NODE_ID:access-1", "NACOS_ADDR:localhost:8848"], \
        f"!! 出现预期之外的占位符: {leftovers}"
    print(f"    [OK] 其余占位符仅为运行期环境变量: {leftovers}")

    print("=== 4) 回滚物 ===")
    rb = f"{BAK_DIR}/rollback-access-trusted-source-{ts}.sh"
    with open(rb, "w", encoding="utf-8") as fh:
        fh.write(f"""#!/usr/bin/env bash
# 一键回滚：把 ypbin-access.yaml 还原成修复前快照 {ts}
# 快照: {bak}  sha256[:16]={live_sha}
set -euo pipefail
if [ -z "${{NACOS_ADMIN_PASSWORD:-}}" ]; then echo "!! 请先 export NACOS_ADMIN_PASSWORD（取 deploy/.env 的值，勿写进命令行）再跑本脚本"; exit 1; fi
T=$(printf '%s' "$NACOS_ADMIN_PASSWORD" | curl -sS -m 15 -X POST http://127.0.0.1:8080/v3/auth/user/login \\
    -H "Content-Type: application/x-www-form-urlencoded" \\
    --data-urlencode "username=${{NACOS_ADMIN_USERNAME:-nacos}}" --data-urlencode "password@-" \\
  | sed -n 's/.*"accessToken":"\\([^"]*\\)".*/\\1/p')
[ -n "$T" ] || {{ echo "!! nacos 登录失败"; exit 1; }}
curl -fsS -m 60 -X POST "http://127.0.0.1:8080/v3/console/cs/config" \\
  -H "accessToken: $T" --data-urlencode "dataId={DATA_ID}" \\
  --data-urlencode "groupName={GROUP}" --data-urlencode "type=yaml" \\
  --data-urlencode "namespaceId=" --data-urlencode "content@{bak}" >/dev/null
echo "已还原 {DATA_ID} 到 {ts} 快照（sha256[:16]={live_sha}）"
echo "注意：access 需重启（或等 Nacos 推送）才会重新加载；未启动则无需操作。"
""")
    os.chmod(rb, 0o700)
    print(f"    回滚: {rb} (0700)")

    if not APPLY:
        print("=== dry-run 结束（未写 Nacos）。核对无误后加 --apply ===")
        return 0

    print("=== 5) POST 到 Nacos ===")
    tk = login()
    publish_via_stdin(tk, new, new_path)

    print("=== 6) 回读校验 ===")
    back = dump_live()
    back_sha = sha(back)
    print(f"    回读 sha256[:16]={back_sha}")
    b_lines = back.split("\n")
    assert back == new, "!! 回读内容与提交内容不一致"
    print("    [OK] 回读与提交逐字一致")
    b_val = [l for l in b_lines if l.startswith(f"      {KEY}: ")]
    assert len(b_val) == 1 and b_val[0].split(": ", 1)[1] == token, "!! 回读值不符"
    assert [l for l in b_lines if l.startswith(f"      {REQUIRE_KEY}: ")] == \
        [f"      {REQUIRE_KEY}: true"], "!! 回读 require 键不符"
    print(f"    [OK] 两键在位；{KEY} 非占位符且与 .env 一致")
    # 关键：修复前的每一行都必须仍在（live-only 键未丢）
    missing = [l for l in lines if l not in b_lines]
    assert not missing, f"!! 修复前存在而修复后丢失的行: {missing}"
    print(f"    [OK] 修复前 {len(lines)} 行全部保留（未丢 live-only 键）")

    print(f"\n=== 完成 ===\n快照={bak}\n回滚={rb}\nlive_sha256[:16]: {live_sha} -> {back_sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
