#!/usr/bin/env python3
"""把 Actuator 最小暴露段补进 Nacos 的 `ypbin-access.yaml` **live** 配置（显式 dump -> diff -> POST）。

用法（在服务器上；`.env` 只读入环境，不打印）：

    set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
    python3 patch-nacos-access-actuator.py            # dry-run：只 dump / 构造 / 校验，不写 Nacos
    python3 patch-nacos-access-actuator.py --apply    # 真正 POST 并回读校验

为什么需要一个脚本而不是 `install.sh`：生产机上 `deploy/nacos/ypbin-access.yaml` 是**模板**，
真正生效的是 Nacos 里的 **live** 配置，两者历史上已分叉（live 有生产改过的键）。
整份覆盖模板 = 回退 live-only 键 ⇒ 只做「读 live → 只追加这一段 → 发布 → 回读」。

安全：
* Nacos 口令只从环境变量取，且经 stdin 传给 curl（**不进 argv**）；
* accessToken 只写进 600 的 curl 配置文件（`-K`），**不进 argv、不进输出**；
* 配置正文（含 `INTERNAL_TOKEN` / `GATEWAY_SIGN_TOKEN` 真值）**一律不打印**，
  输出只有长度、sha256 前缀与「新增的那几行」（新增行不含任何凭据）。
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time

DATA_ID = "ypbin-access.yaml"
GROUP = "DEFAULT_GROUP"
CLIENT = "http://127.0.0.1:8848/nacos/v3/client/cs/config"
CONSOLE = "http://127.0.0.1:8080"
ENV_FILE = "/opt/ypbin/ypbin-iot/deploy/.env"
BAK_DIR = "/opt/ypbin"

APPLY = "--apply" in sys.argv

# 本 BLOCK 是 2026-09-27 **首次发布到 live 的那一版**（含当时的注释）。
# ⚠️ 仓库模板 `deploy/nacos/ypbin-access.yaml` 之后又加了一行 `management.health.redis.enabled: false`
#   （access 不用 Redis 但那项健康项会挂死聚合 health，见 deploy/PROD-OPS-NOTES.md 陷阱 5）
#   ⇒ 两者只在「新增那一段 + 注释」上有差异；**新环境请以仓库模板为准**，本脚本只用于补首次那段。
BLOCK = """
# ---------- 可观测（Spring Boot Actuator，2026-09-27 新增，对齐 ypbin-iot.yaml）----------
# 为什么要有这一段：access 已经带了大量指标（iot.access.egress.* / iot.access.lease.* /
# iot.access.config.* / iot.access.subscribe.* / iot.access.spec.* / iot.access.connection.* /
# iot.access.decode.failure），但 `/actuator/metrics` 默认不暴露（Spring Boot 4.1.1 的
# management.endpoints.web.exposure.include 默认只有 health）⇒ 生产只能翻 WARN 日志、看不到计数趋势。
# 上一轮新增的 iot.access.decode.failure 正是这个缺口：它只在 WARN 里可见。
#
# ⚠️ 安全边界（与 ypbin-iot.yaml 同一口径，改前务必读完）：
#   1) **只开只读且必要的最小集合，绝不用 `*`**：`*` 会连带 env / configprops / heapdump /
#      threaddump / beans / loggers / mappings；heapdump 可直接导出堆内存（含内部 token），
#      env/configprops 会回显全部配置。**暴露它们等于把凭据交出去。**
#   2) **只在内网/回环可达**：access 的 18086 在 compose 里**只绑 127.0.0.1**
#      （`${ACCESS_BIND_ADDR:-127.0.0.1}:18086:18086`，**刻意与 INTERNAL_BIND_ADDR 分开**，同
#      IOT_BIND_ADDR / IOTDB_BIND_ADDR 先例）⇒ 公网打不通。**不要把 18086 改绑到 0.0.0.0**：
#      Actuator 端点默认**没有认证**（access 的 Sa-Token 拦截器是关闭的），绑公网 = 指标裸奔。
#   3) 网关上**没有** access 的路由（它只被 iot 的内部租约/读数链路与宿主机的值班命令直接调用），
#      所以「只绑回环」就是这条链路的实际边界。
#   4) 只读性：metrics/info 是只读端点；health 已关掉细节与组件展示（show-details/show-components=never），
#      不会泄露中间件地址与异常堆栈。
#      ⚠️ **但 access 的 `/actuator/health` 在生产会挂起**（收到请求后不返回任何字节；2026-09-27 部署前后各实测一次，
#      `PROD-OPS-NOTES.md` §6.3 早有登记，**非本段引入**、根因未定位）⇒ **不要把它当可用探针**，
#      判活请用 `/actuator/health/liveness` 与 `/actuator/health/readiness`（均 {"status":"UP"}，毫秒级返回）。
#      `info` 会回 process 段（pid/工作目录/堆/GC/uptime）——与 ypbin-iot 的既有口径一致，不含凭据。
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: never
      show-components: never
    metrics:
      enabled: true
"""

ANCHOR = "management:"


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def say(msg: str = "") -> None:
    print(msg, flush=True)


def nacos_password() -> str:
    return os.environ.get("NACOS_ADMIN_PASSWORD", "")


def nacos_user() -> str:
    return os.environ.get("NACOS_ADMIN_USERNAME", "nacos")


def login() -> str:
    """取 Nacos console accessToken（口令经 stdin，不进 argv；token 只输出指纹）。"""
    payload = f"username={nacos_user()}&password={nacos_password()}".encode("utf-8")
    out = subprocess.run(
        ["curl", "-sS", "-m", "20", "-X", "POST", f"{CONSOLE}/v3/auth/user/login",
         "-H", "Content-Type: application/x-www-form-urlencoded", "--data-binary", "@-"],
        input=payload, capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    token = body.get("accessToken") or ""
    if not token:
        raise SystemExit(f"!! Nacos 登录未拿到 accessToken（keys={sorted(body)}）")
    say(f"    nacos login ok  accessToken sha256[:16]={sha(token)}")
    return token


def write_header_file(token: str) -> str:
    """把 accessToken 写进 600 的 curl 配置文件（不进 argv）。"""
    fd, path = tempfile.mkstemp(prefix="nacos-auth-")
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(f'header = "accessToken: {token}"\n')
    return path


def dump_live(header_file: str) -> str:
    url = f"{CLIENT}?dataId={DATA_ID}&groupName={GROUP}&namespaceId="
    out = subprocess.run(["curl", "-fsS", "-m", "25", "-K", header_file, url],
                         capture_output=True, check=True)
    body = json.loads(out.stdout.decode("utf-8"))
    if body.get("code") != 0:
        raise SystemExit(f"!! Nacos 读配置失败：{body.get('message')}")
    return body["data"]["content"]


def dump_live_until(header_file: str, expected: str, timeout_s: int = 15) -> str:
    """发布后回读：**client API 有秒级传播延迟**，立即回读会读到旧值（2026-09-27 实测）。

    所以轮询到内容一致为止（超时返回最后一次读到的内容，由调用方判定失败）。
    """
    deadline = time.monotonic() + timeout_s
    actual = ""
    while True:
        actual = dump_live(header_file)
        if actual == expected or time.monotonic() >= deadline:
            return actual
        time.sleep(1)


def publish_via_file(token_header: str, content: str) -> str:
    """按 Nacos 3 Console API 发布配置；正文走文件，不进 argv。"""
    fd, path = tempfile.mkstemp(prefix="ypbin-access-", suffix=".yaml")
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(content)
    try:
        out = subprocess.run(
            ["curl", "-sS", "-m", "60", "-X", "POST", f"{CONSOLE}/v3/console/cs/config",
             "-K", token_header,
             "--data-urlencode", f"dataId={DATA_ID}",
             "--data-urlencode", f"groupName={GROUP}",
             "--data-urlencode", "type=yaml",
             "--data-urlencode", "namespaceId=",
             "--data-urlencode", f"content@{path}"],
            capture_output=True, check=False)
        return out.stdout.decode("utf-8", "replace")
    finally:
        os.unlink(path)


def main() -> int:
    if not nacos_password():
        say("!! 未提供 NACOS_ADMIN_PASSWORD（先 `set -a; . deploy/.env; set +a`）")
        return 2
    if not os.path.exists(ENV_FILE):
        say(f"!! 找不到 {ENV_FILE}")
        return 2

    token = login()
    header_file = write_header_file(token)
    try:
        before = dump_live(header_file)
        say(f"    live before: bytes={len(before)} lines={before.count(chr(10)) + 1} sha256[:16]={sha(before)}")

        if ANCHOR in before:
            say(f"    ⚠️ live 配置里**已经有** `{ANCHOR}` —— 本脚本只做追加，先人工确认现值，不自动改")
            return 3

        if not before.endswith("\n"):
            after = before + "\n" + BLOCK.lstrip("\n")
        else:
            after = before + BLOCK.lstrip("\n")
        added = after[len(before):]
        say(f"    live after : bytes={len(after)} lines={after.count(chr(10)) + 1} sha256[:16]={sha(after)}")
        say(f"    追加行数={added.count(chr(10))}（内容即本脚本 BLOCK，且**不含任何凭据**）")
        if not after.startswith(before):
            raise SystemExit("!! 内部错误：追加后原文不是前缀（会破坏 live 配置）")

        if not APPLY:
            say("    dry-run：未写入 Nacos（加 --apply 才发布）")
            return 0

        ts = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
        bak = f"{BAK_DIR}/nacos-{DATA_ID}.bak-{ts}"
        with open(bak, "w", encoding="utf-8") as fh:
            fh.write(before)
        os.chmod(bak, 0o600)
        say(f"    备份（600）：{bak}  bytes={len(before)}")

        resp = publish_via_file(header_file, after)
        say(f"    POST 响应（截断）：{resp[:200]}")
        if '"code":0' not in resp and '"code": 0' not in resp:
            raise SystemExit("!! 发布未返回 code=0 —— 不继续，先看上面的响应体")

        readback = dump_live_until(header_file, after)
        if readback != after:
            # ⚠️ POST 已返回 code=0：失败可能只是「读取侧还没看到」，**不能**据此说"没生效"。
            # 用 inspect-nacos-access-config.py 独立读回确认后再决定是否重启/回滚。
            say(f"    ❗ 已轮询等待传播，回读仍与发布内容不一致（bytes={len(readback)} "
                f"sha256[:16]={sha(readback)}）")
            say(f"    ❗ POST 已成功返回，live **可能已经生效**：请用 inspect-nacos-access-config.py 独立确认；"
                f"确属未生效则从备份还原：{bak}")
            return 4
        say(f"    ✅ 回读一致：bytes={len(readback)} sha256[:16]={sha(readback)}")
        say(f"    回滚：把 {bak} 的内容按同样方式 POST 回 {DATA_ID}，再重启 ypbin-access")
        return 0
    finally:
        os.unlink(header_file)


if __name__ == "__main__":
    sys.exit(main())
