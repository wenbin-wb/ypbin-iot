#!/usr/bin/env bash
# =============================================================================
# emqx-tunnel-install.sh —— 在**生产机**上安装 EMQX 管理面隧道（autossh + systemd）
# =============================================================================
# 做四件事（幂等，可重复运行）：
#   1. 生成**专用**隧道私钥 /etc/ypbin/emqx-tunnel.key（600，root；已存在则不动）
#   2. 落地**主机密钥 pin** /etc/ypbin/emqx-tunnel.known_hosts
#   3. 安装并启用 `emqx-tunnel.service`（autossh，Restart=always、开机自启）
#   4. 安装「停摆告警」脚本与 timer（**只告警不自愈**）
#
# 凭据纪律：本脚本**不生成也不保存任何口令**；唯一的凭据是那把专用私钥。
#           中间件机侧对该公钥带 `restrict,port-forwarding,permitopen="127.0.0.1:18093"`
#           —— **转发目标**被钉死在这一个（`permitopen` 实测生效：转发到别处会被
#           `administratively prohibited` 拒绝）。
#           ⚠️ **但"目标被钉死" ≠ "只能转发"**：同一行的 `port-forwarding` 是**两向**开关且未设
#           `permitlisten` ⇒ **反向转发（-R）仍可用**；OpenSSH 也没有"只许转发不许执行命令"的
#           authorized_keys 选项（`command=` 会让 -N 会话立刻结束）。实际能力 = 转发 + 非交互 root
#           命令执行，属**已登记的残留风险**（docs/EMQX-DEPLOY.md §6.1 / §10 U-H）。
#
# 前置条件：生产机已装 autossh（`autossh -V` 可跑）。生产机**无法直连外网**，
#           apt 装不上时按 docs/EMQX-DEPLOY.md 的「离线装入 autossh」步骤，
#           从中间件机（有外网）取官方 .deb 并校验 SHA256 后 `dpkg -i`。
#
# 用法：
#   bash emqx-tunnel-install.sh --mw-hostkey-file /tmp/mw_host_ed25519.pub
#   bash emqx-tunnel-install.sh --trust-keyscan        # 次选：TOFU（不推荐，见下）
#
# 主机密钥 pin 的两种来源：
#   · `--mw-hostkey-file <file>`：**推荐**。文件内容 = 中间件机 /etc/ssh/ssh_host_ed25519_key.pub
#     （从一条**已信任**的到中间件机的 SSH 会话里取；避免首次连接时的中间人风险）。
#   · `--trust-keyscan`：用 `ssh-keyscan` 现取（TOFU）。仅在拿不到可信文件时才用，
#     且脚本会把抓到的指纹打印出来供人工核对。
# =============================================================================
set -euo pipefail

MW_HOST=43.242.200.8
MW_PORT=61260
MW_USER=root
LOCAL_BIND=127.0.0.1
LOCAL_PORT=18093
REMOTE_HOST=127.0.0.1       # 站在**中间件机**看的地址（EMQX 只绑那里）
REMOTE_PORT=18093

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC=/etc/ypbin
KEY="$ETC/emqx-tunnel.key"
KNOWN="$ETC/emqx-tunnel.known_hosts"
HOSTKEY_FILE=""
TRUST_KEYSCAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --mw-hostkey-file) HOSTKEY_FILE="$2"; shift 2 ;;
    --trust-keyscan)   TRUST_KEYSCAN=1; shift ;;
    --dir)             DIR="$2"; shift 2 ;;
    --) shift; break ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行（需要写 /etc/ypbin 与 systemd 单元）" >&2; exit 1; }
command -v autossh >/dev/null 2>&1 || {
  echo "缺少 autossh。生产机无法直连外网 ⇒ 按 docs/EMQX-DEPLOY.md「离线装入 autossh」步骤先用 dpkg 装好再运行本脚本。" >&2
  exit 1
}

step() { printf '\n=== %s ===\n' "$1"; }

step "1/5 目录与专用密钥"
install -d -m 700 "$ETC"
if [ ! -f "$KEY" ]; then
  ssh-keygen -q -t ed25519 -N "" -C "ypbin-emqx-tunnel@$(hostname -s)" -f "$KEY"
  chmod 600 "$KEY"; chmod 644 "$KEY.pub"
  echo "已生成 $KEY"
else
  echo "已存在，保持不变：$KEY"
fi
echo "该公钥（需加到中间件机 root 的 authorized_keys，带受限选项）："
echo "  $(cat "$KEY.pub")"
echo "对应的 authorized_keys 行（中间件机 root 上执行）："
echo "  restrict,port-forwarding,permitopen=\"${REMOTE_HOST}:${REMOTE_PORT}\" $(cat "$KEY.pub")"

step "2/5 主机密钥 pin（known_hosts）"
if [ -s "$KNOWN" ]; then
  echo "已存在，保持不变：$KNOWN"
elif [ -n "$HOSTKEY_FILE" ]; then
  [ -s "$HOSTKEY_FILE" ] || { echo "主机密钥文件为空：$HOSTKEY_FILE" >&2; exit 1; }
  printf '[%s]:%s %s\n' "$MW_HOST" "$MW_PORT" "$(cat "$HOSTKEY_FILE")" >"$KNOWN"
  chmod 644 "$KNOWN"
  echo "已按 $HOSTKEY_FILE 写入（指纹见下，请核对）："
  ssh-keygen -lf "$KNOWN"
elif [ "$TRUST_KEYSCAN" -eq 1 ]; then
  ssh-keyscan -p "$MW_PORT" -T 10 "$MW_HOST" >"$KNOWN" 2>/dev/null || true
  [ -s "$KNOWN" ] || { echo "ssh-keyscan 未取到主机密钥" >&2; exit 1; }
  chmod 644 "$KNOWN"
  echo "⚠️ 已用 ssh-keyscan(TOFU) 写入；指纹如下，**必须人工核对**（对比中间件机 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub）："
  ssh-keygen -lf "$KNOWN"
else
  echo "缺少主机密钥来源：请传 --mw-hostkey-file <中间件机的 /etc/ssh/ssh_host_ed25519_key.pub>" >&2
  echo "（或在明确接受 TOFU 风险时传 --trust-keyscan）" >&2
  exit 1
fi

step "3/5 安装 systemd 单元"
install -m 644 "$DIR/emqx-tunnel.service" /etc/systemd/system/emqx-tunnel.service
install -m 644 "$DIR/emqx-tunnel-watch.service" /etc/systemd/system/emqx-tunnel-watch.service
install -m 644 "$DIR/emqx-tunnel-watch.timer" /etc/systemd/system/emqx-tunnel-watch.timer
install -m 755 "$DIR/emqx-tunnel-watch.sh" /usr/local/sbin/emqx-tunnel-watch.sh
echo "已安装：emqx-tunnel.service / emqx-tunnel-watch.{service,timer} / /usr/local/sbin/emqx-tunnel-watch.sh"

step "4/5 启用（开机自启 + 立即启动）"
systemctl daemon-reload
systemctl enable --now emqx-tunnel.service
systemctl enable --now emqx-tunnel-watch.timer
echo "已启用"

step "5/5 启动后校验"
sleep 5
systemctl is-active emqx-tunnel.service || true
ss -ltn | awk '{print $4}' | grep -E "^${LOCAL_BIND}:${LOCAL_PORT}$" \
  && echo "监听已建立：${LOCAL_BIND}:${LOCAL_PORT}" \
  || echo "⚠️ 监听未建立：${LOCAL_BIND}:${LOCAL_PORT}（看 journalctl -u emqx-tunnel.service）"

# 注意：heredoc 用**引号**定界（<<'EOF'），否则正文里的反引号会被 bash 当命令替换执行
cat <<'EOF'

后续（人工一步，无法自动化）：把上面那行 authorized_keys 加到**中间件机** root 上，
然后 `systemctl restart emqx-tunnel.service`。加之前隧道会因认证失败而反复重启，
这是**预期行为**（单元不会静默假装成功）。
停摆告警：`systemctl list-timers emqx-tunnel-watch.timer`；
          告警内容看 `journalctl -u emqx-tunnel-watch.service -n 50`。
EOF
