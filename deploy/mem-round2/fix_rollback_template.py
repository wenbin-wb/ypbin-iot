#!/usr/bin/env python3
"""重新生成并验证 Nacos 口令轮换的**回滚脚本**（修复 2026-09-26 复核判 FAIL 的缺陷）。

原缺陷（复核第 4 项 FAIL）：
  1. 第 16 行写了 `$(fp(old))` —— `fp` 是**生成器里的 Python 函数**，bash 不认识 ⇒ `bash -n` 直接语法错误、
     脚本必然以 exit 2 收尾，操作者/CI 无法区分"回滚成功"与"回滚失败"。
  2. **无失败守卫**：`curl -sS` 不带 `-f`、输出丢 /dev/null，`set -e`/pipefail 抓不到 ⇒ 若登录或改密失败，
     脚本仍会还原 .env 并重建 5 个服务，而服务端还是新口令 ⇒ **全网鉴权中断**（半回滚）。

本脚本做的事：用**同一份**模板重写 `<workdir>/rollback-nacos-password.sh`，然后 **`bash -n` 自检**。
用法：python3 fix_rollback_template.py <workdir>
"""
import hashlib
import os
import pathlib
import subprocess
import sys

TEMPLATE = """#!/usr/bin/env bash
# 一键回滚 Nacos 口令轮换（{ts}）。**不含任何口令明文**：值从 .env 与 {work} 的 600 文件读。
#
# 失败守卫（2026-09-26 复核后补）：任一步失败 ⇒ **非零退出，且不改 .env、不重建任何服务**。
#   为什么必须有：若"服务端改回旧值"失败而脚本仍还原 .env 并重建服务，就会得到
#   「.env 是旧口令、服务端是新口令」的**半回滚** ⇒ 5 个服务全部鉴权失败、控制面全断。
set -euo pipefail
ROOT={root}
W={work}
NACOS={nacos}

U="$(grep '^NACOS_ADMIN_USERNAME=' "$ROOT/deploy/.env" | cut -d= -f2-)"
CUR="$(grep '^NACOS_ADMIN_PASSWORD=' "$ROOT/deploy/.env" | cut -d= -f2-)"
OLD="$(cat "$W/.oldpw")"
if [ -z "$CUR" ] || [ -z "$OLD" ]; then
  echo "!! 读不到当前口令或旧口令 ⇒ 拒绝继续（未动 .env、未重建任何服务）" >&2
  exit 3
fi

umask 077
H="$W/.rb-hdr"
trap 'rm -f "$H"' EXIT

# 1) 用**当前**口令登录（口令走 stdin，不进 argv）
TOK="$(printf '%s' "$CUR" | curl -fsS -m 20 -X POST "$NACOS/v3/auth/user/login" \\
        -H 'Content-Type: application/x-www-form-urlencoded' \\
        --data-urlencode "username=$U" --data-urlencode 'password@-' \\
        | sed -n 's/.*"accessToken":"\\([^"]*\\)".*/\\1/p' || true)"
if [ -z "$TOK" ]; then
  echo "!! 用当前口令登录失败 ⇒ 拒绝继续（未动 .env、未重建任何服务）" >&2
  exit 4
fi
printf 'header = "accessToken: %s"\\n' "$TOK" > "$H"
chmod 600 "$H"

# 2) 把服务端口令改回旧值（新口令走 stdin；-f 让 HTTP>=400 直接失败）
RESP="$(printf '%s' "$OLD" | curl -fsS -m 20 -K "$H" -X PUT "$NACOS/v3/auth/user?username=$U" \\
        -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'newPassword@-' || true)"
case "$RESP" in
  *'"code":0'*) : ;;
  *) echo "!! 服务端口令改回失败（响应：$RESP）⇒ 拒绝继续（未动 .env、未重建任何服务）" >&2; exit 5 ;;
esac

# 3) 断言旧口令**确实可以登录**了，才允许动 .env
TOK2="$(printf '%s' "$OLD" | curl -fsS -m 20 -X POST "$NACOS/v3/auth/user/login" \\
        -H 'Content-Type: application/x-www-form-urlencoded' \\
        --data-urlencode "username=$U" --data-urlencode 'password@-' \\
        | sed -n 's/.*"accessToken":"\\([^"]*\\)".*/\\1/p' || true)"
if [ -z "$TOK2" ]; then
  echo "!! 旧口令仍不可登录（服务端可能未生效）⇒ 拒绝继续（未动 .env、未重建任何服务）" >&2
  exit 6
fi
echo "[1] 服务端口令已改回旧值，并已验证旧口令可登录（值不打印）"

# 4) 还原 .env 并重建 5 个服务
install -m 600 "$W/deploy.env.bak" "$ROOT/deploy/.env"
echo "[2] .env 已还原为快照（0600）"
cd "$ROOT/deploy"
for s in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml \\
    up -d --no-build --no-deps --force-recreate "$s"
done
echo "[3] 5 个服务已用旧口令重建；等待就绪…"
ready=0
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{{http_code}}' -m 5 http://127.0.0.1:19000/ || true)"
  if [ "$code" = "200" ] && curl -s -m 5 http://127.0.0.1:18084/actuator/health | grep -q UP; then
    ready=1
    break
  fi
  sleep 5
done
if [ "$ready" != "1" ]; then
  echo "!! 回滚已执行（口令与 .env 均已还原），但服务未在 150s 内就绪 ⇒ 请人工检查" >&2
  exit 7
fi
echo "[4] 就绪断言通过（19000=200 且 18084/actuator/health 含 UP）"
printf '回滚完成：服务端口令与 .env 均已还原为旧值；旧指纹=%s\\n' \\
  "$(printf '%s' "$OLD" | sha256sum | cut -c1-16)"
"""


def main() -> int:
    work = pathlib.Path(sys.argv[1])
    old = (work / ".oldpw").read_text().strip()
    ts = work.name.replace("nacos-rotate-", "")
    body = TEMPLATE.format(ts=ts, work=work, root="/opt/ypbin/ypbin-iot",
                           nacos="http://127.0.0.1:8848/nacos")
    dst = work / "rollback-nacos-password.sh"
    dst.write_text(body, encoding="utf-8")
    os.chmod(dst, 0o600)

    # 自检 1：bash 语法
    r = subprocess.run(["bash", "-n", str(dst)], capture_output=True, text=True)
    print("bash -n:", "OK" if r.returncode == 0 else "FAIL")
    if r.returncode != 0:
        print(r.stderr[:500])
        return 1
    # 自检 2：不得含明文口令
    text = dst.read_text(encoding="utf-8")
    for name, val in (("old", old), ("new", (work / ".newpw").read_text().strip())):
        print(f"含{name}口令明文: {val in text}")
        if val in text:
            return 1
    # 自检 3：不得再出现 Python 函数形式的指纹
    print("含非法指纹调用 $(fp(...)):", "$(fp(" in text)
    if "$(fp(" in text:
        return 1
    print("旧指纹 =", hashlib.sha256(old.encode()).hexdigest()[:16])
    return 0


if __name__ == "__main__":
    sys.exit(main())
