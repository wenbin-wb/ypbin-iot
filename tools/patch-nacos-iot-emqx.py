#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 `ypbin.emqx` 段写进 Nacos **活配置** `ypbin-iot.yaml`（EMQX 平台侧集成的配置侧步骤）。

**为什么需要它**：`deploy/nacos/ypbin-iot.yaml` 是**模板**（install.sh 首次安装时导入）。已经装过
的环境，活配置里没有这一段 ⇒ `ypbin.emqx.enabled` 取默认 `false`：入站端点照常工作，
但**设备凭据不会同步到 EMQX**（签发成功、设备连不上），这是最难排查的一类「配置没生效」。

凭据卫生（与 `tools/rotate-*.py` 同口径，必须遵守）：
  * 本段**不含任何真实凭据**：api-key/api-secret 写的是 `${EMQX_API_KEY}` /
    `${EMQX_API_SECRET}` 占位符，真值只在 `deploy/.env`(600) 与容器 env —— 因为这是能改 ACL /
    建用户的全权凭据，进 Nacos 就等于**落库**（违反「凭据不入库」）。
  * 输出只有键名、长度、sha256 前 16 位与命中布尔；不打印任何值。
  * 改前先 dump live 到 `<workdir>/before-ypbin-iot.yaml`（600）供一键回滚。
  * 默认 dry-run；真要发布必须显式 `--apply`。

用法（部署机上）：
    W=/opt/ypbin/nacos-emqx-$(date +%Y%m%d-%H%M%S); install -d -m 700 "$W"
    python3 tools/patch-nacos-iot-emqx.py --workdir "$W"            # dry-run
    python3 tools/patch-nacos-iot-emqx.py --workdir "$W" --apply    # 发布 + 回读断言

Nacos 凭据来源与路径口径：与 `tools/rotate-iotdb-password.py` 逐字一致（env 优先 → deploy/.env；
控制台 API `http://127.0.0.1:8080/v3/console/cs/config`）。
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

#: 插入锚点：模板里 `# 数据保留（D0.8）` 注释是 ypbin 段内的固定位置（紧跟 security 之后）
ANCHOR_PATTERN = re.compile(r"^  # 数据保留")

#: 期望写入的段（缩进两级；**占位符不是真值**，理由见文件头）
BLOCK = """  # EMQX 接入（设计 §8.4；拓扑/方向/未验证项见 docs/EMQX-INTEGRATION.md）
  # ⚠️ api-key/api-secret 是**占位符**：真值只在 deploy/.env(600) 与容器 env 里，由 Spring 读取本配置时
  #    从 Environment 解析。**不要把真值填进本文件**——本文件进 Nacos 配置库（= 落库），而这是能改 ACL/
  #    建用户/删规则的全权凭据（OSS 版 API Key 无角色约束）。
  emqx:
    # false 时装配「不可用实现」：调用点显式报错（不静默降级），但平台侧凭据的签发/校验照常可用
    enabled: true
    # 管理面地址 = 生产机上的**出向隧道**（隧道把 EMQX 管理面绑到 docker 网桥网关；容器到不了宿主回环）
    base-url: http://172.20.0.1:18093
    api-key: ${EMQX_API_KEY}
    api-secret: ${EMQX_API_SECRET}
    # 远程调用必须显式超时（仓内铁律：禁止无超时默认客户端）
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
    # 「接入信息」端点回给前端的 broker 地址（仅展示与二维码，不含口令）。
    # ⚠️ 当前 1883 只绑中间件机回环（未对设备开放），此处是**登记的真实地址**而非"已验证可接入"。
    broker-host: %(broker_host)s
    broker-port: %(broker_port)s
    broker-tls-enabled: false
    # 设备口令随机字节数（32 字节 = 256 位熵）
    credential-password-length: 32
    # 下行发布默认值（段 B）
    downlink-qos: 1
    downlink-retain: false
    # 下行命令默认超时（毫秒）：不小于入站动作的 request_ttl=30s（评审确认）
    default-command-timeout-ms: 30000
    # 超时扫描（周期批量；不自动重试）
    command-scan-interval-ms: 15000
    command-scan-batch-size: 200
"""


def fp(value):
    return hashlib.sha256(value.encode()).hexdigest()[:16]


def read_env(key, default=None):
    with open(ENV_FILE, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith(key + "="):
                return line.strip().split("=", 1)[1].strip().strip('"').strip("'")
    return default


def nacos_env(name, default=None):
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


def write_private(path, text):
    tmp = path + ".tmp"
    with open(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w",
              encoding="utf-8") as handle:
        handle.write(text)
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workdir", required=True, help="700 工作目录（备份写在这里，600）")
    parser.add_argument("--nacos", default="http://127.0.0.1:8080")
    parser.add_argument("--broker-host", default=os.environ.get("EMQX_BROKER_HOST", "43.242.200.8"))
    parser.add_argument("--broker-port", default=os.environ.get("EMQX_BROKER_PORT", "1883"))
    parser.add_argument("--apply", action="store_true", help="真正发布（默认 dry-run）")
    args = parser.parse_args()

    block = BLOCK % {"broker_host": args.broker_host, "broker_port": args.broker_port}
    if not os.access(args.workdir, os.W_OK):
        raise SystemExit("!! 工作目录不可写：%s" % args.workdir)

    user = nacos_env("NACOS_ADMIN_USERNAME", "nacos")
    admin_pw = nacos_env("NACOS_ADMIN_PASSWORD")
    if not admin_pw:
        raise SystemExit("!! 缺 Nacos 控制台口令：请 export NACOS_ADMIN_PASSWORD 或在 deploy/.env 里设置")
    login = urllib.parse.urlencode({"username": user, "password": admin_pw}).encode()
    token = json.loads(request(args.nacos, "/v3/auth/user/login", login,
                               {"Content-Type": "application/x-www-form-urlencoded"}))["accessToken"]
    print("[0] Nacos 登录 ok（user=%s，口令 sha256[:16]=%s）" % (user, fp(admin_pw)))

    src = fetch_live(args.nacos, token)
    write_private(os.path.join(args.workdir, "before-" + DATA_ID), src)
    print("[1] live dump ok → %s（600）len=%d sha256[:16]=%s"
          % (os.path.join(args.workdir, "before-" + DATA_ID), len(src), fp(src)))

    lines = src.split("\n")
    if re.search(r"^  emqx:$", src, flags=re.M):
        # 已存在：**逐字相同则幂等跳过；不同则整段替换**（替换同样断言"除该段外逐字未变"）。
        # 不做"逐个键合并"：那样会在段内留下旧键与新键混合的状态，也没法用"删除整段后比原文"来证明没动别处。
        start = src.index("  emqx:")
        end = len(src)
        for offset, line in enumerate(lines):
            position = sum(len(part) + 1 for part in lines[:offset])
            if position > start and (ANCHOR_PATTERN.match(line)
                    or re.match(r"^  [a-z][a-z0-9-]*:", line)):
                end = position
                break
        current = src[start:end]
        if current == block:
            print("[2] 幂等：live 里已有**逐字相同**的 ypbin.emqx 段（无需变更）")
            return
        out = src[:start] + block + src[end:]
        if out.replace(block, "", 1) != src.replace(current, "", 1):
            raise SystemExit("!! 除 ypbin.emqx 段以外内容被改动 —— 拒绝发布")
        print("[2] 更新 ok：替换 ypbin.emqx 段（旧 sha256[:16]=%s → 新 %s），其余内容逐字未变"
              % (fp(current), fp(block)))
    else:
        anchors = [i for i, line in enumerate(lines) if ANCHOR_PATTERN.match(line)]
        if len(anchors) != 1:
            raise SystemExit("!! 期望 live 里锚点（# 数据保留）恰好 1 处，实际 %d 处" % len(anchors))
        index = anchors[0]
        out = "\n".join(lines[:index]) + "\n" + block + "\n".join(lines[index:])
        # 关键断言：把插入的块删掉后必须与原文**逐字相同**（防止"顺手"改了别的配置）
        if out.replace(block, "", 1) != src:
            raise SystemExit("!! 除插入 ypbin.emqx 段以外内容被改动 —— 拒绝发布")
        print("[2] 组装 ok：插入 %d 行、除该段外内容逐字未变（含占位符而非真值）" % (block.count("\n")))

    if not args.apply:
        print("[3] dry-run：未发布。加 --apply 才写 Nacos")
        return

    publish(args.nacos, token, out)
    after = fetch_live(args.nacos, token)
    if after != out:
        raise SystemExit("!! 发布后回读与期望不一致（dump 在 %s，可据此回滚）" % args.workdir)
    print("[3] 发布后回读逐字一致：emqx.enabled=true 占位符未展开（由 Spring 运行时解析）")


if __name__ == "__main__":
    sys.exit(main())
