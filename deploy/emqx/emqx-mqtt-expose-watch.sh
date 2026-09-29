#!/usr/bin/env bash
# =============================================================================
# emqx-mqtt-expose-watch.sh —— 「MQTT 1883 对外暴露」的**只读**判据（**只告警，不自愈**）
# =============================================================================
# 在**中间件机**上运行。与 `emqx-tunnel-watch.sh` 同一模式：只判、不动手；
# 需要自愈时交给 systemd 的单元重启策略，看门狗一旦也去改配置就会互相打架（历史教训）。
#
# 为什么放在中间件机而不是生产机：
#   · 「1883 是否真的对外监听」的**权威事实**在中间件机（compose 绑地址 + 宿主防火墙规则 + 宿主监听）；
#   · 「匿名连接计数」需要 EMQX REST API Key。⚠️ **如实登记**：生产机**确实**持有同一对
#     EMQX REST Key/Secret（在平台自己的 `deploy/.env` 里，平台调 EMQX 管理面必需）——
#     `emqx-tunnel-watch.sh` 头部那句"生产机不落任何 EMQX 凭据"**只对那个脚本自身成立**
#     （它确实不用凭据），**不是**"生产机上没有 EMQX 凭据"这一事实陈述（旧措辞有歧义，已由独立复核指出）。
#     本脚本仍放在中间件机，是因为**权威事实**（宿主监听/防火墙/DNAT）在这里，而不是因为凭据不存在于生产机。
#
# 判据（任一层失败即 ALERT）：
#   A1 匿名被拒（**安全红线**）：EMQX `client.auth.anonymous` 必须恒为 0。
#      > 0 说明有客户端**以匿名身份通过了认证** ⇒ 鉴权配置被改坏，必须立刻查。
#   A2 管理面未对外：`EMQX_DASHBOARD_BIND_ADDR` 必须是回环，且宿主不得有 `0.0.0.0:18093` 监听。
#   A3 未开的端口确实没开：宿主不得有 8083/8084 监听（容器内应为 enable=false）。
#      ⚠️ **8883 已于 2026-09-30 启用**（看板 #9 第一步）⇒ 它不再属于本判据，
#         改由 **A6** 判（声明一致 + 握手真完成 + 证书未过期）。别把 8883 再加回这里。
#   A4 声明与现实一致（**有效闸门层**）：`.env` 声明 0.0.0.0 ⇒ 宿主必须有 `0.0.0.0:1883` 监听，
#      **且** Docker 的 `nat/DOCKER` 里要有"面向非回环目的"的 DNAT（= 真会把外部流量转给容器）；
#      声明 127.0.0.1 ⇒ 宿主不应有非回环监听、也不应有面向外部的 DNAT。**不一致就是 ALERT**
#      （既不放过"该开没开"，也不放过"该关没关"——后者是更危险的静默漂移）。
#   A5 纵深防御层（**仅供参考，不参与 PASS/FAIL**）：宿主 INPUT 上那条 tcp/1883 放行规则。
#      ⚠️ **实测结论（必须记住）：对 Docker 发布出来的端口，INPUT/ufw 都拦不住** ——
#      外部流量在 `nat/PREROUTING -j DOCKER` 就被 DNAT 成转发流量，随后走
#      `filter/FORWARD -j DOCKER-FORWARD -j DOCKER` 被 ACCEPT，**根本不经过 INPUT**。
#      实测：撤掉 INPUT 规则后，从生产机探 43.242.200.8:1883 **仍然 OPEN**。
#      ⇒ 真正决定"对外可达"的是**绑定地址**（本判据 A4）与**云安全组**；
#        要按来源收敛，必须写在 `DOCKER-USER` 链（或云安全组），写在 INPUT/ufw 上无效。
#      残留的 INPUT 规则在"声明只绑回环"时按 ALERT 报（回滚不彻底的痕迹）。
#
# 凭据纪律：只用 `/opt/emqx/.env` 里的 EMQX API Key，且经 600 的 curl 配置文件传入（不进 argv、
#   不打印）；输出只给计数与布尔结论，绝不回显任何口令/密钥。
#
# 退出码（与 emqx-tunnel-watch.sh 对齐）：0 = OK；2 = ALERT；3 = 无 ALERT 但判据无法求值
#   优先级：有 ALERT ⇒ 2 覆盖 3。
#
# 用法：
#   emqx-mqtt-expose-watch.sh                 # 全量判据
#   emqx-mqtt-expose-watch.sh --no-api        # 跳过需要 EMQX API 的判据（离线巡检）
# =============================================================================
set -uo pipefail

PORT=1883
DASH_PORT=18093
# 仍应保持关闭的端口（WS/WSS 未开）。⚠️ **8883 已于 2026-09-30 移出本列表**
#   —— 它现在由 A6 按"与 .env 声明一致 + 握手真能完成"来判，而不再是"必须没监听"。
CLOSED_PORTS="8083 8084"
TLS_PORT=8883
# 允许覆盖（便于**变异验证**：用一个假 .env / 假 BASE_URL 证明判据真的会咬人，而不必改线上配置）
MW_ENV="${MW_ENV:-/opt/emqx/.env}"
BASE_URL="${BASE_URL:-http://127.0.0.1:18093}"
USE_API=1

while [ $# -gt 0 ]; do
  case "$1" in
    --no-api) USE_API=0; shift ;;
    --port) PORT="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 3 ;;
  esac
done

alerts=(); notes=(); unevaluable=()
ok_note() { notes+=("$1"); }
alert()   { alerts+=("$1"); }
uneval()  { unevaluable+=("$1"); }

now=$(date -u +%FT%TZ)

# ── 事实采集 ─────────────────────────────────────────────────────────────────
env_val() { sed -n "s/^$1=//p" "$MW_ENV" 2>/dev/null | tail -1; }

host_listen_addr() {   # $1=port → 打印所有宿主监听地址（可能多行）
  ss -lnt 2>/dev/null | awk -v p=":$1" '$4 ~ p"$" {print $4}'
}
has_listen_on() {      # $1=addr:port
  host_listen_addr "${1##*:}" | grep -qx -- "$1"
}
any_listen_any_addr() { # $1=port → 是否存在非回环监听
  host_listen_addr "$1" | grep -qvE '^127\.0\.0\.1:' && return 0 || return 1
}
rule_sources() {       # 打印 tcp/$PORT 放行规则的来源集合（"无 -s" 归一成 0.0.0.0/0）
  # ⚠️ 实测坑：`iptables -I ... -s 0.0.0.0/0` 会被内核**规范化掉 `-s`**，
  #    `-S` 里直接回显成 `-A INPUT -p tcp -m tcp --dport 1883 -j ACCEPT`。
  #    只匹配带 `-s` 的形态 ⇒ 刚刚放行成功的规则被判成"没有放行"（**假 ALERT**）。
  iptables -S INPUT 2>/dev/null | sed -n \
    -e "s/^-A INPUT -s \([^ ]*\) -p tcp -m tcp --dport $PORT -j ACCEPT\$/\1/p" \
    -e "s/^-A INPUT -p tcp -m tcp --dport $PORT -j ACCEPT\$/0.0.0.0\/0/p"
}

DECLARED_MQTT="$(env_val EMQX_MQTT_BIND_ADDR)"
DECLARED_DASH="$(env_val EMQX_DASHBOARD_BIND_ADDR)"
# 8883：**未声明即为 compose 默认值 127.0.0.1（fail-closed）**，与 compose 的 `:-127.0.0.1` 同口径
DECLARED_TLS="$(env_val EMQX_MQTT_TLS_BIND_ADDR)"; DECLARED_TLS="${DECLARED_TLS:-127.0.0.1}"
notes+=("声明 EMQX_MQTT_BIND_ADDR=${DECLARED_MQTT:-NA}、EMQX_DASHBOARD_BIND_ADDR=${DECLARED_DASH:-NA}、EMQX_MQTT_TLS_BIND_ADDR=${DECLARED_TLS:-NA}")

# ── A1：匿名连接必须恒为 0（安全红线）────────────────────────────────────────
if [ "$USE_API" -eq 1 ]; then
  if [ ! -r "$MW_ENV" ]; then
    uneval "A1 读不到 $MW_ENV ⇒ 无法查 client.auth.anonymous"
  else
    set -a; # shellcheck disable=SC1090
    . "$MW_ENV"; set +a
    if [ -z "${EMQX_API_KEY:-}" ] || [ -z "${EMQX_API_SECRET:-}" ]; then
      uneval "A1 .env 缺 EMQX_API_KEY/EMQX_API_SECRET（只比键名，不打印值）"
    else
      PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
      printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"
      anon="$(curl -s --max-time 8 -K "$PRIV/curlrc" "$BASE_URL/api/v5/metrics?aggregate=true" \
        | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("client.auth.anonymous", "<absent>"))
except Exception:
    print("<error>")' 2>/dev/null)"
      connack_err="$(curl -s --max-time 8 -K "$PRIV/curlrc" "$BASE_URL/api/v5/metrics?aggregate=true" \
        | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("packets.connack.auth_error", "<absent>"))
except Exception:
    print("<error>")' 2>/dev/null)"
      rm -rf "$PRIV"
      case "$anon" in
        ''|'<absent>'|'<error>') uneval "A1 取不到 client.auth.anonymous（API 返回异常）";;
        0) ok_note "A1 client.auth.anonymous = 0（匿名未被接受；connack.auth_error=$connack_err）";;
        *) alert "A1 🔴 client.auth.anonymous = $anon（应恒为 0）⇒ 有客户端以匿名身份通过认证，鉴权配置可疑，立即排查！";;
      esac
    fi
  fi
else
  ok_note "A1 已按 --no-api 跳过（离线巡检）"
fi

# ── A2：管理面未对外 ─────────────────────────────────────────────────────────
if [ "$DECLARED_DASH" = "127.0.0.1" ] || [ -z "$DECLARED_DASH" ]; then
  ok_note "A2 Dashboard 绑定声明 = ${DECLARED_DASH:-NA}（回环，管理面不对外）"
else
  alert "A2 🔴 EMQX_DASHBOARD_BIND_ADDR=$DECLARED_DASH（应为回环）⇒ 管理面被一起放开了，这与「只开数据面」的边界冲突！"
fi
if any_listen_any_addr "$DASH_PORT"; then
  alert "A2 🔴 宿主存在非回环 $DASH_PORT 监听：$(host_listen_addr "$DASH_PORT" | tr '\n' ' ') ⇒ 管理面可能已对外"
else
  ok_note "A2 宿主 $DASH_PORT 无非回环监听"
fi

# ── A3：8883/8083/8084 确实没开 ──────────────────────────────────────────────
for p in $CLOSED_PORTS; do
  if any_listen_any_addr "$p"; then
    alert "A3 🔴 宿主机 $p 出现监听（本轮应为 enable=false / 不发布）"
  else
    ok_note "A3 端口 $p 无宿主监听"
  fi
done

# ── A4：声明与现实一致（有效闸门层）──────────────────────────────────────────
# Docker 是否会把**外部**流量转给容器：看 nat/DOCKER 里有没有"目的不是 127.0.0.1/32"的 1883 DNAT。
dnat_external() { # $1=port（默认 $PORT，供 A6 复用；与 A4 同一判据，避免两处各写一套漂移）
  local p="${1:-$PORT}"
  iptables -t nat -S DOCKER 2>/dev/null | grep -- "--dport $p -j DNAT" \
    | grep -qv -- '-d 127\.0\.0\.1/32'
}
sources="$(rule_sources | tr '\n' ' ')"
if [ "$DECLARED_MQTT" = "0.0.0.0" ]; then
  if has_listen_on "0.0.0.0:$PORT"; then
    ok_note "A4 宿主监听 0.0.0.0:$PORT 在（与声明一致）"
  else
    alert "A4 🔴 声明对外（0.0.0.0）但宿主没有 0.0.0.0:$PORT 监听 ⇒ 外部连不上（绑定层没生效）"
  fi
  if dnat_external; then
    ok_note "A4 nat/DOCKER 存在面向非回环的 $PORT DNAT（外部流量会被转给容器）"
  else
    alert "A4 🔴 声明对外但 nat/DOCKER 没有面向外部的 $PORT DNAT ⇒ Docker 不会转发外部流量"
  fi
elif [ "$DECLARED_MQTT" = "127.0.0.1" ]; then
  if any_listen_any_addr "$PORT"; then
    alert "A4 🔴 声明只绑回环，但宿主存在非回环 $PORT 监听：$(host_listen_addr "$PORT" | tr '\n' ' ') ⇒ 静默漂移成对外暴露"
  else
    ok_note "A4 声明只绑回环且无非回环 $PORT 监听"
  fi
  if dnat_external; then
    alert "A4 🔴 声明只绑回环，但 nat/DOCKER 仍有面向外部的 $PORT DNAT ⇒ 回滚没落到 Docker 层"
  else
    ok_note "A4 nat/DOCKER 无面向外部的 $PORT DNAT（与只绑回环一致）"
  fi
else
  uneval "A4 读不到 EMQX_MQTT_BIND_ADDR（值='${DECLARED_MQTT:-}'）"
fi

# ── A5：纵深防御层（INPUT 规则，仅供参考）────────────────────────────────────
if [ -n "$sources" ]; then
  ok_note "A5 宿主 INPUT 有 tcp/$PORT 放行（来源：${sources}）—— 纵深防御，**不是** Docker 端口对外可达的闸门"
  [ "$DECLARED_MQTT" = "127.0.0.1" ] && alert "A5 🔴 声明已收回（127.0.0.1）但仍残留 INPUT tcp/$PORT 放行 ⇒ 回滚不彻底（虽不影响可达性）"
else
  ok_note "A5 宿主 INPUT 无 tcp/$PORT 放行（纵深防御缺失；对 Docker 发布端口不影响可达性）"
fi

# ── A6：8883（MQTT over TLS）—— 声明一致性 + 握手真完成（2026-09-30 新增）─────
# 本判据**只告警不自愈**（与全脚本同一口径）。它回答三个问题：
#   ① 声明与监听是否一致（和 A4 同构，只是换成 8883）；
#   ② **TLS 是否真的能握手**（"端口在监听"不等于"证书能协商"——证书过期/私钥读不到/
#      版本不匹配都表现为"端口开着但连不上"）；
#   ③ 证书离到期还有多久（自签**不会**自动续期，这是最容易静默爆掉的一项）。
# ⚠️ 判据口径：这里只验**TLS 层**（握手 + 证书），不做 MQTT/CONNACK 层断言——
#    后者由 emqx-tls-selfcheck.sh 负责。两者分工写清楚，避免"巡检绿 = MQTT 也能连"的误读。
tls_probe() { # tls_probe <host> → 成功(0)并把摘要放 TLS_SUMMARY
  TLS_SUMMARY=""
  command -v openssl >/dev/null 2>&1 || return 1
  local out rc
  out="$(printf 'Q' | timeout 10 openssl s_client -connect "${1}:${TLS_PORT}" \
          -servername "$1" 2>&1)"; rc=$?
  [ "$rc" -ne 0 ] && return 1
  local proto verify
  proto="$(printf '%s' "$out" | sed -n 's/^ *Protocol *: *//p' | head -1)"
  verify="$(printf '%s' "$out" | sed -n 's/^ *Verify return code: *//p' | head -1)"
  [ -z "$proto" ] && return 1
  TLS_SUMMARY="protocol=${proto}；Verify return code=${verify:-（无）}"
  return 0
}

tls_addrs="$(host_listen_addr "$TLS_PORT" | tr '\n' ' ')"
case "$DECLARED_TLS" in
  0.0.0.0)
    if has_listen_on "0.0.0.0:$TLS_PORT"; then
      ok_note "A6 宿主监听 0.0.0.0:$TLS_PORT 在（与声明一致）"
    else
      alert "A6 🔴 声明 TLS 对外（0.0.0.0）但宿主没有 0.0.0.0:$TLS_PORT 监听 ⇒ 外部连不上（绑定层没生效）"
    fi
    if dnat_external "$TLS_PORT"; then
      ok_note "A6 nat/DOCKER 存在面向非回环的 $TLS_PORT DNAT（外部流量会被转给容器）"
    else
      alert "A6 🔴 声明 TLS 对外但 nat/DOCKER 没有面向外部的 $TLS_PORT DNAT ⇒ Docker 不会转发外部流量"
    fi
    ;;
  127.0.0.1)
    if any_listen_any_addr "$TLS_PORT"; then
      alert "A6 🔴 声明 TLS 只绑回环，但宿主存在非回环 $TLS_PORT 监听：$tls_addrs ⇒ 静默漂移成对外暴露"
    else
      ok_note "A6 声明 TLS 只绑回环且无非回环 $TLS_PORT 监听"
    fi
    if dnat_external "$TLS_PORT"; then
      alert "A6 🔴 声明 TLS 只绑回环，但 nat/DOCKER 仍有面向外部的 $TLS_PORT DNAT ⇒ 回滚没落到 Docker 层"
    else
      ok_note "A6 nat/DOCKER 无面向外部的 $TLS_PORT DNAT（与只绑回环一致）"
    fi
    ;;
  *) alert "A6 🔴 EMQX_MQTT_TLS_BIND_ADDR='$DECLARED_TLS' 不受支持（期望 0.0.0.0/127.0.0.1）" ;;
esac

# 握手：只要端口有监听就必须能握（否则是"开着但不可用"的最坏形态）
if [ -n "$tls_addrs" ]; then
  if tls_probe 127.0.0.1; then
    ok_note "A6 TLS 握手成功（127.0.0.1:$TLS_PORT；$TLS_SUMMARY）"
  else
    alert "A6 🔴 宿主 $TLS_PORT 有监听但 **TLS 握手失败** ⇒ 证书/私钥/版本有问题，设备连不上（查 docker logs ypbin-emqx | grep -i ssl）"
  fi
  # 证书到期：自签不自动续期，<30 天告警
  if command -v openssl >/dev/null 2>&1 && [ -r /opt/emqx/certs/server.crt ]; then
    end="$(openssl x509 -in /opt/emqx/certs/server.crt -noout -enddate 2>/dev/null | cut -d= -f2)"
    if [ -n "$end" ]; then
      left=$(( ( $(date -d "$end" +%s 2>/dev/null || echo 0) - $(date +%s) ) / 86400 ))
      if [ "$left" -lt 0 ]; then
        alert "A6 🔴 TLS 服务端证书**已过期**（$end）⇒ 客户端握手会失败"
      elif [ "$left" -lt 30 ]; then
        alert "A6 ⚠️ TLS 服务端证书 $left 天后到期（$end）—— 自签不自动续期，请排期重签（gen-certs.sh --force）"
      else
        ok_note "A6 TLS 服务端证书有效期剩余 $left 天（至 $end）"
      fi
    else
      uneval "A6 读不到 /opt/emqx/certs/server.crt 的有效期"
    fi
  else
    uneval "A6 读不到 /opt/emqx/certs/server.crt（无法判到期）"
  fi
else
  ok_note "A6 宿主 $TLS_PORT 无监听（8883 未对外发布）—— 跳过握手判据"
fi

# ── 输出 ─────────────────────────────────────────────────────────────────────
echo "emqx-mqtt-expose-watch @ $now"
for n in "${notes[@]}"; do echo "  · $n"; done
if [ "${#alerts[@]}" -gt 0 ]; then
  echo "判定: ALERT（${#alerts[@]} 条）"
  for a in "${alerts[@]}"; do echo "  ! $a"; done
  if [ "${#unevaluable[@]}" -gt 0 ]; then
    echo "  （另有 ${#unevaluable[@]} 条判据无法求值）"
    for u in "${unevaluable[@]}"; do echo "  ? $u"; done
  fi
  exit 2
fi
if [ "${#unevaluable[@]}" -gt 0 ]; then
  echo "判定: 无法求值（${#unevaluable[@]} 条判据读不到；无 ALERT）"
  for u in "${unevaluable[@]}"; do echo "  ? $u"; done
  exit 3
fi
echo "判定: OK（1883/8883 暴露面与声明一致、TLS 握手可完成、匿名仍被拒、管理面未对外）"
exit 0
