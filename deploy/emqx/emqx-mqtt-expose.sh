#!/usr/bin/env bash
# =============================================================================
# emqx-mqtt-expose.sh —— MQTT 1883 对外暴露的**放行 / 撤销**（在中间件机上运行）
# =============================================================================
# 只管**宿主防火墙**这一层。绑地址那一层由 compose 的 `EMQX_MQTT_BIND_ADDR` 决定
# （开发测试期 = 0.0.0.0）；两层都到位外部才真能连上。
# `status` 不做自己的判据 —— 直接调用**唯一真源** `emqx-mqtt-expose-watch.sh`
# （避免两处判据各写一套、日后漂移出"两个都绿但结论相反"的假象）。
#
# 🔴🔴 **必须先读：对 Docker 发布出来的端口，INPUT/ufw 拦不住（本机实测）**
#   EMQX 的 1883 是 compose `ports:` 发布出来的 ⇒ 外部流量在 `nat/PREROUTING -j DOCKER`
#   就被 DNAT 成转发流量，随后在 `filter/FORWARD -j DOCKER` 被 ACCEPT，**根本不经过 INPUT**。
#   实测：把本脚本加的 INPUT 规则删掉后，从生产机探 `43.242.200.8:1883` **仍然 OPEN**。
#   ⇒ 本脚本的规则是**纵深防御**，**不是**对外可达的闸门；真正的闸门是
#     ① **绑定地址**（`EMQX_MQTT_BIND_ADDR`，回滚就靠它）与 ② **云安全组**。
#   ⇒ 要按来源收敛访问，必须写在 **`DOCKER-USER` 链**或云安全组上；写在 INPUT/ufw 上**无效**。
#      `open --source` 目前只影响 INPUT 这条纵深防御规则，**请勿误以为它能限制外部来源**；
#      需要真正限制来源时用（示例，只放行 203.0.113.7）：
#        iptables -I DOCKER-USER -p tcp --dport 1883 ! -s 203.0.113.7 -j DROP
#        撤销：iptables -D DOCKER-USER -p tcp --dport 1883 ! -s 203.0.113.7 -j DROP
#      （本仓暂不替用户下这条规则——它会与"开发测试期允许全网"的当前决策冲突。）
#
# 🔴 只碰 tcp/1883：不动 18093（管理面，继续只绑回环），不动 8883/8083/8084（容器内 enable=false），
#    不改 INPUT 默认策略，不做 `-F`，不动宝塔的 IN_BT 与 ufw-* 链，也不动 Docker 自有的 DOCKER 链。
#
# 为什么用「独立 systemd 单元」而不是 ufw：
#   · 与本仓既有先例 `emqx-ingress-firewall.service` 一致（幂等 + ExecStop 即回滚 + 重启后自恢复）；
#   · `ufw allow` 会触发 ufw 重写自己的链，本机上还跑着别人的项目，能不动别人的链就不动；
#   · 规则插在 INPUT 队首（宝塔 IN_BT / ufw-* 之前），不受别人链的变化影响。
#
# 用法（**在中间件机上**）：
#   emqx-mqtt-expose.sh open                      # 放行 tcp/1883（来源默认 0.0.0.0/0）
#   emqx-mqtt-expose.sh open --source 1.2.3.4/32  # 只放行指定来源（更窄，推荐生产前用）
#   emqx-mqtt-expose.sh status                    # 只读巡检（转调 watch 脚本，退出码 0/2/3）
#   emqx-mqtt-expose.sh close                     # 撤销放行（= 回滚的防火墙那一半）
#
# 一行撤销（手工等价物，便于应急；不依赖本脚本与单元）：
#   iptables -D INPUT -p tcp --dport 1883 -j ACCEPT
# =============================================================================
set -uo pipefail

PORT=1883
SOURCE="0.0.0.0/0"
UNIT=emqx-mqtt-expose-firewall.service
UNIT_DIR=/etc/systemd/system
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_SRC="${UNIT_SRC:-$SCRIPT_DIR/$UNIT}"
WATCH_SH="${WATCH_SH:-$SCRIPT_DIR/emqx-mqtt-expose-watch.sh}"
ENV_DIR=/etc/ypbin
ENV_FILE="$ENV_DIR/emqx-mqtt-expose.env"
MW_ENV=/opt/emqx/.env

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# 1883 放行规则的条数（>1 说明历史上重复插过，回滚会删不干净）
rule_count() { iptables -S INPUT 2>/dev/null | grep -c -- "--dport $PORT -j ACCEPT"; }
rule_sources() {
  # "无 -s" 归一成 0.0.0.0/0：iptables 会把 `-s 0.0.0.0/0` 规范化掉（实测坑，详见 watch 脚本注释）
  iptables -S INPUT 2>/dev/null | sed -n \
    -e "s/^-A INPUT -s \([^ ]*\) -p tcp -m tcp --dport $PORT -j ACCEPT\$/\1/p" \
    -e "s/^-A INPUT -p tcp -m tcp --dport $PORT -j ACCEPT\$/0.0.0.0\/0/p"
}

do_open() {
  echo "=== 放行 tcp/$PORT（来源 $SOURCE）==="
  [ -f "$UNIT_SRC" ] || { bad "找不到单元文件 $UNIT_SRC（可用 UNIT_SRC 指过去）"; return 1; }
  install -d -m 755 "$ENV_DIR" || { bad "建 $ENV_DIR 失败"; return 1; }
  # 用临时文件 + install 落位：避免"写了一半"的中间态被单元读到
  printf 'EMQX_MQTT_EXPOSE_SOURCE=%s\n' "$SOURCE" >"$ENV_FILE.tmp" \
    && install -m 644 "$ENV_FILE.tmp" "$ENV_FILE" && rm -f "$ENV_FILE.tmp" \
    || { bad "写 $ENV_FILE 失败"; return 1; }
  ok "来源已写入 $ENV_FILE（$SOURCE）"
  install -m 644 "$UNIT_SRC" "$UNIT_DIR/$UNIT" || { bad "安装单元失败"; return 1; }
  systemctl daemon-reload || { bad "daemon-reload 失败"; return 1; }
  systemctl enable --now "$UNIT" >/dev/null 2>&1 || { bad "enable --now $UNIT 失败"; return 1; }
  ok "单元已启用并启动"
  # 读回自证：不信"我刚执行过"，只信回读
  if iptables -C INPUT -s "$SOURCE" -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null; then
    ok "回读 iptables -C 命中：规则在"
  else
    bad "回读 iptables -C 未命中 ⇒ 规则没生效"
  fi
  local n; n="$(rule_count)"
  [ "${n:-0}" -le 1 ] && ok "放行规则条数 = ${n:-0}（无堆叠）" \
    || bad "放行规则有 ${n} 条（堆叠，回滚会删不干净）"
  info "来源：$(rule_sources | tr '\n' ' ')"
  info "⚠️ 提醒：这是**纵深防御**规则。Docker 发布端口的外部可达性由「绑定地址 + 云安全组」决定，"
  info "   INPUT 规则删掉也不影响外部可达（已实测）——详见本脚本头部与 watch 脚本 A5 判据。"
  return "$fail"
}

do_close() {
  echo "=== 撤销 tcp/$PORT 放行 ==="
  systemctl disable --now "$UNIT" >/dev/null 2>&1 && ok "单元已停用（ExecStop 已删规则）" || info "单元本就不在（幂等）"
  # 兜底：ExecStop 只删它自己那组来源；把任何残留的 1883 放行都删干净
  local removed=0 src
  while read -r src; do
    [ -z "$src" ] && continue
    iptables -D INPUT -s "$src" -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null && removed=$((removed + 1))
  done < <(rule_sources)
  [ "$removed" -eq 0 ] && ok "无残留（或已清）" || ok "额外清掉 $removed 条残留规则"
  local n; n="$(rule_count)"
  [ "${n:-0}" -eq 0 ] && ok "回读：INPUT 里已无 $PORT 放行（全删干净）" || bad "仍有 ${n} 条 $PORT 放行"
  info "⚠️ 宿主防火墙只是回滚的一半——还要把 $MW_ENV 的 EMQX_MQTT_BIND_ADDR 改回 127.0.0.1 并重启容器"
  info "   （完整步骤见 docs/EMQX-DEPLOY.md「开发测试期的暴露面登记 / 回滚」）"
  return "$fail"
}

do_status() {
  [ -f "$WATCH_SH" ] || { bad "找不到判据脚本 $WATCH_SH（可用 WATCH_SH 指过去）"; return 3; }
  bash "$WATCH_SH" "$@"
}

ACTION="${1:-status}"; shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    --port)   PORT="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 3 ;;
  esac
done

case "$ACTION" in
  open)   do_open; exit "$fail" ;;
  close)  do_close; exit "$fail" ;;
  status) do_status; exit $? ;;
  *) echo "用法: $0 {open|close|status} [--source CIDR] [--port N]" >&2; exit 3 ;;
esac
