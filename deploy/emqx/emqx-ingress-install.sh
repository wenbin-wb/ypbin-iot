#!/usr/bin/env bash
# =============================================================================
# emqx-ingress-install.sh —— 安装 MQTT **入站**双向通道（幂等）
# =============================================================================
# 在**运维机（本仓所在机器）**上运行：把三份单元分发到两台机器并启用，再落下内部凭证。
#
# 装什么（三份单元 + 一个 600 凭证文件）：
#   中间件机：emqx-ingress-firewall.service（iptables 只放行本网桥→本端口）
#             emqx-ingress-relay.socket/.service（systemd-socket-proxyd：172.28.0.1:18084 → 127.0.0.1:18084）
#   生产机  ：emqx-ingress-tunnel.service（autossh：-L 172.20.0.1:18093 出向 + -R 127.0.0.1:18084 反向）
#   中间件机：/opt/emqx/ingress/internal-token（600）= 生产机 deploy/.env 的 INTERNAL_TOKEN
#
# 为什么凭证要落到中间件机（而不是"EMQX 侧不需要凭证"）：
#   入站动作的 headers 里必须带 X-Internal-Token（既有守卫头名），而 EMQX 跑在中间件机 ⇒
#   该值必须以 600 文件落到中间件机，由 emqx-ingress-init.sh 读入动作 headers。
#   这是**已登记的残留风险**（RK9/P1-5）：入站凭证与平台其它内部端点共用同一把；
#   泄露它等于拿到 /internal/** 的写权限（因此 18083/1893 只绑回环 + 不暴露公网）。
#   本脚本只用 `ssh … | ssh …` **管道**传值：不进 argv、不落盘到运维机、不打印。
#
# 用法（幂等，可重复执行）：
#   bash deploy/emqx/emqx-ingress-install.sh \
#        --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
#        --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test" \
#        --prod-env /opt/ypbin/ypbin-iot/deploy/.env
#   # 加 --check 只做只读连通性核验（不装任何东西）
# =============================================================================
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MW_SSH=""
PROD_SSH=""
PROD_ENV=/opt/ypbin/ypbin-iot/deploy/.env
MW_DIR=/opt/emqx
CHECK_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --mw-ssh)   MW_SSH="$2"; shift 2 ;;
    --prod-ssh) PROD_SSH="$2"; shift 2 ;;
    --prod-env) PROD_ENV="$2"; shift 2 ;;
    --check)    CHECK_ONLY=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

[ -n "$MW_SSH" ] || { echo "缺 --mw-ssh" >&2; exit 1; }
[ -n "$PROD_SSH" ] || { echo "缺 --prod-ssh" >&2; exit 1; }

fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info() { printf '  ·  %s\n' "$1"; }

# shellcheck disable=SC2086
mwr() { ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new $MW_SSH "$@"; }
# shellcheck disable=SC2086
prodr() { ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new $PROD_SSH "$@"; }

echo "=== 0) SSH 可达性 ==="
mwr 'hostname' >/dev/null 2>&1 && ok "中间件机可达" || { bad "中间件机不可达"; exit 1; }
prodr 'hostname' >/dev/null 2>&1 && ok "生产机可达" || { bad "生产机不可达"; exit 1; }

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "=== 只读核验 ==="
  mwr 'systemctl is-active emqx-ingress-relay.socket emqx-ingress-firewall.service' || true
  prodr 'systemctl is-active emqx-ingress-tunnel.service' || true
  exit 0
fi

echo "=== 1) 分发单元文件 ==="
for f in emqx-ingress-firewall.service emqx-ingress-relay.socket emqx-ingress-relay.service; do
  # shellcheck disable=SC2086
  scp -q -o BatchMode=yes -o StrictHostKeyChecking=accept-new $MW_SSH "$DIR/$f" ":/etc/systemd/system/$f" \
    && ok "→ 中间件机 $f" || bad "拷贝 $f 到中间件机失败"
done
# shellcheck disable=SC2086
scp -q -o BatchMode=yes -o StrictHostKeyChecking=accept-new $PROD_SSH "$DIR/emqx-ingress-tunnel.service" \
  ":/etc/systemd/system/emqx-ingress-tunnel.service" \
  && ok "→ 生产机 emqx-ingress-tunnel.service" || bad "拷贝隧道单元到生产机失败"

echo "=== 2) 启用（中间件机：先防火墙放行，再听端口）==="
mwr 'systemctl daemon-reload && systemctl enable --now emqx-ingress-firewall.service && systemctl enable --now emqx-ingress-relay.socket' \
  && ok "中间件机单元已启用" || bad "中间件机启用失败"
mwr 'systemctl is-active emqx-ingress-firewall.service emqx-ingress-relay.socket' || bad "中间件机单元未 active"
mwr 'iptables -C INPUT -s 172.28.0.0/16 -p tcp --dport 18084 -j ACCEPT' \
  && ok "iptables 放行规则已就位（只对 emqx-edge 网桥 + 本端口）" || bad "iptables 放行规则缺失"

echo "=== 3) 启用（生产机：出向 + 反向隧道）==="
prodr 'systemctl daemon-reload && systemctl enable --now emqx-ingress-tunnel.service' \
  && ok "生产机隧道单元已启用" || bad "生产机启用失败"
prodr 'systemctl is-active emqx-ingress-tunnel.service' >/dev/null && ok "隧道单元 active" || bad "隧道单元未 active"
prodr 'ss -ltn | grep -q "172.20.0.1:18093"' && ok "出向转发已监听 172.20.0.1:18093（容器可达地址）" || bad "未见 172.20.0.1:18093 监听"
mwr 'ss -ltn | grep -q "127.0.0.1:18084"' && ok "反向转发已在中间件机回环监听 18084" || bad "未见中间件机 127.0.0.1:18084 监听"
mwr 'ss -ltn | grep -q "172.28.0.1:18084"' && ok "中继已在 emqx-edge 网关监听 172.28.0.1:18084" || bad "未见 172.28.0.1:18084 监听"

echo "=== 4) 落内部凭证（600；值经 stdin 管道，不打印）==="
if prodr "grep -c '^INTERNAL_TOKEN=' $PROD_ENV" | grep -q '^1$'; then
  ok "$PROD_ENV 中 INTERNAL_TOKEN 恰好 1 行"
  prodr "sed -n 's/^INTERNAL_TOKEN=//p' $PROD_ENV" \
    | mwr 'umask 077; mkdir -p /opt/emqx/ingress; cat > /opt/emqx/ingress/internal-token; chmod 600 /opt/emqx/ingress/internal-token' \
    && ok "凭证已写入中间件机 /opt/emqx/ingress/internal-token（600）" || bad "写凭证失败"
  # 只比「长度 + 指纹」，绝不打印值
  mw_fp="$(mwr 'tr -d "\n" < /opt/emqx/ingress/internal-token | sha256sum | cut -c1-16; tr -d "\n" < /opt/emqx/ingress/internal-token | wc -c' | tr '\n' ' ')"
  prod_fp="$(prodr "sed -n 's/^INTERNAL_TOKEN=//p' $PROD_ENV | tr -d '\n' | sha256sum | cut -c1-16; sed -n 's/^INTERNAL_TOKEN=//p' $PROD_ENV | tr -d '\n' | wc -c" | tr '\n' ' ')"
  if [ "$mw_fp" = "$prod_fp" ]; then
    ok "两侧指纹一致（$mw_fp）；值未打印"
  else
    bad "两侧指纹不一致：中间件机[$mw_fp] 生产机[$prod_fp]"
  fi
else
  bad "$PROD_ENV 里 INTERNAL_TOKEN 不是恰好 1 行（重复键会让 compose 取最后一个，必须先去重）"
fi

echo "=== 5) 端到端连通性（双向各一发）==="
code="$(mwr "docker exec ypbin-emqx curl -s -o /dev/null -m 8 -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary '{}' http://172.28.0.1:18084/internal/mqtt/readings" || echo 000)"
case "$code" in
  000) bad "容器 → 平台入站端点不通（期望任一种 HTTP 响应码）" ;;
  *) ok "容器 → 平台入站端点连通（HTTP $code；401=凭证头缺失路径、400=报文非法，均属预期）" ;;
esac
code="$(prodr 'curl -s -o /dev/null -m 8 -w "%{http_code}" http://172.20.0.1:18093/api/v5/status' || echo 000)"
case "$code" in
  000) bad "生产机 → EMQX 管理面不通" ;;
  *) ok "生产机 → EMQX 管理面连通（HTTP $code）" ;;
esac

echo
if [ "$fail" -eq 0 ]; then
  echo "结论: PASS（入站通道已就绪；接着在中间件机跑 emqx-ingress-init.sh 落规则/动作）"
  exit 0
fi
echo "结论: FAIL（见上面的 ❌）"
exit 1
