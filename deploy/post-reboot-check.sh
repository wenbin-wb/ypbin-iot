#!/usr/bin/env bash
# post-reboot-check.sh —— 重启后**逐项自动核对**（只读；给"启用新内核那次重启"用）
#
# 用法（在生产机上，root）：
#     bash /usr/local/sbin/post-reboot-check.sh
#     bash /usr/local/sbin/post-reboot-check.sh --wait     # 先等在跑的服务就绪（最多 6 分钟）再核对
#
# 设计要点（每条判据都注明"为什么用这个而不是更省事的那个"）：
#   · **不用 18080/18081/18082 的 `/actuator/health`**：它们返回的是「业务 404 包在 HTTP 200 里」，
#     对"坏没坏"没有鉴别力；18086(access) 的 `/actuator/health` 会**挂起**。
#     ⇒ 真正可用的健康端点是 **18084(iot)**；其余用 **Nacos 注册数 + `Started XxxApplication` 日志 + 19000**。
#   · **`/internal/**` 的判据**：`POST /internal/readings` 带正确 `X-Internal-Token` + 空 body `{}`
#     必须返回 **`code=400`（items 读数清单不能为空）**；无/错 token 才是 `code=401`。
#     ⇒ 这条能在**不写入任何数据**的前提下证明"内部凭证链路可用"。
#   · 口令/token 一律经 **stdin** 或 **curl -K 进程替换**投递，**不进 argv**、**不打印**。
#
# 退出码：0 = 全部通过；1 = 有判据 FAIL；2 = 有判据无法求值（环境不完整）
set -uo pipefail

ENVF=/opt/ypbin/ypbin-iot/deploy/.env
ACT=http://127.0.0.1:18084/actuator/metrics
fail=0
unknown=0

ok()   { printf '  \033[32mOK  \033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; fail=$((fail+1)); }
unk()  { printf '  \033[33m?   \033[0m %s\n' "$*"; unknown=$((unknown+1)); }
head2() { printf '\n=== %s ===\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { echo "must run as root"; exit 2; }
[ -r "$ENVF" ] || { echo "找不到 $ENVF"; exit 2; }
set -a; . "$ENVF"; set +a

if [ "${1:-}" = "--wait" ]; then
  echo "等待应用服务就绪（最多 360s）…"
  for _ in $(seq 1 36); do
    up=0
    for p in 18084 19000; do
      [ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://127.0.0.1:$p/")" = "200" ] && up=$((up+1))
    done
    [ "$up" -ge 1 ] && break
    sleep 10
  done
fi

head2 "0. 内核与启动项（确认是否真的启用了目标内核 / 是否残留一次性钉回）"
printf '  uname -r            = %s\n' "$(uname -r)"
printf '  uptime              = %s\n' "$(uptime -p 2>/dev/null || uptime)"
printf '  boot_id             = %s\n' "$(cat /proc/sys/kernel/random/boot_id)"
ne="$(grub-editenv /boot/grub/grubenv list 2>/dev/null | sed -n 's/^next_entry=//p')"
if [ -z "$ne" ]; then ok "grub next_entry 为空（无残留的一次性钉回）"
else unk "grub next_entry = $ne（存在一次性启动项；若你本意是启用新内核，请确认下一次重启不会又启回旧内核）"; fi
printf '  reboot-required     = %s\n' "$([ -f /var/run/reboot-required ] && echo YES || echo no)"

head2 "1. 容器是否全 Up（只允许一次性 init 处于 Exited(0)）"
notup="$(docker ps -a --format '{{.Names}}\t{{.Status}}' | grep -v 'Up ' || true)"
if [ -z "$notup" ]; then ok "全部容器 Up"
else
  echo "$notup" | while IFS= read -r l; do echo "       $l"; done
  if echo "$notup" | grep -qv 'iotdb-init'; then bad "有非预期容器未 Up（见上）"; else ok "只有 ypbin-iotdb-init 处于 Exited（一次性 init，预期）"; fi
fi
oom="$(docker ps -a --format '{{.Names}} {{.State.OOMKilled}}' | grep -c 'true' || true)"
[ "$oom" = "0" ] && ok "无容器 OOMKilled" || bad "有 $oom 个容器 OOMKilled=true"

head2 "2. 每个应用都打出过 Started XxxApplication"
for c in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  line="$(docker logs "$c" 2>&1 | grep -oE 'Started [A-Za-z0-9_]+Application in [0-9.]+ seconds' | tail -1 || true)"
  if [ -n "$line" ]; then ok "$(printf '%-16s %s' "$c" "$line")"; else bad "$c 日志里没有 Started ...Application"; fi
done

head2 "3. Nacos 注册数（应为 totalCount=5，且各 healthyInstanceCount=1）"
TOK="$(printf '%s' "${NACOS_ADMIN_PASSWORD:-}" | curl -s -m 20 -X POST \
        "http://127.0.0.1:8848/nacos/v3/auth/user/login" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode "username=${NACOS_ADMIN_USERNAME:-nacos}" \
        --data-urlencode 'password@-' \
        | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' || true)"
if [ -z "$TOK" ]; then unk "Nacos 登录失败（口令可能未生效/服务未起）⇒ 注册数无法核对"
else
  curl -s -m 15 -K <(printf 'header = "accessToken: %s"\n' "$TOK") \
    "http://127.0.0.1:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId=public" \
  | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)["data"]
except Exception as e:
    print("  ?   解析失败:", e); sys.exit(2)
n=d.get("totalCount"); items=d.get("pageItems") or []
print("  totalCount =", n)
for i in items:
    print("   - %-16s healthyInstanceCount=%s ipCount=%s" % (i["name"], i.get("healthyInstanceCount"), i.get("ipCount")))
bad=[i for i in items if i.get("healthyInstanceCount")!=1]
sys.exit(0 if (n==5 and not bad) else 1)' || bad "注册数不是 5 或不健康（见上）"
fi

head2 "4. 对外链路 19000 与真健康端点 18084"
c19="$(curl -s -o /dev/null -w '%{http_code}' -m 8 http://127.0.0.1:19000/)"
[ "$c19" = "200" ] && ok "19000/ = 200" || bad "19000/ = $c19"
b84="$(curl -s -m 8 http://127.0.0.1:18084/actuator/health)"
case "$b84" in *'"status":"UP"'*) ok "18084/actuator/health = UP" ;; *) bad "18084/actuator/health = $b84" ;; esac

head2 "5. /internal/** 内部凭证（不写入数据的判据：400=token 通过，401=token 拒绝）"
r="$(curl -s -m 8 -K <(printf 'header = "X-Internal-Token: %s"\n' "${INTERNAL_TOKEN:-}") \
      -X POST -H 'Content-Type: application/json' -d '{}' \
      "http://127.0.0.1:18084/internal/readings" || true)"
case "$r" in
  *'"code":400'*) ok "带 token 返回 code=400（items 不能为空）⇒ 内部凭证链路可用" ;;
  *'"code":401'*) bad "带 token 仍返回 401 ⇒ INTERNAL_TOKEN 不匹配（.env 与 Nacos 配置需一致）" ;;
  *) unk "响应不可判定：$(printf '%s' "$r" | head -c 120)" ;;
esac

head2 "6. 采集链路：iot.reading 是否在增长"
act() { curl -s -m 8 "$ACT/$1" | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); m=d.get("measurements") or []
    print(int(m[0]["value"]) if m else "NA")
except Exception: print("NA")'; }
w1="$(act iot.timeseries.write.rows)"; d1="$(act iot.timeseries.db.rows)"; sleep 25
w2="$(act iot.timeseries.write.rows)"; d2="$(act iot.timeseries.db.rows)"
printf '  write.rows %s -> %s   db.rows %s -> %s\n' "$w1" "$w2" "$d1" "$d2"
if [ "$w1" != "NA" ] && [ "$w2" != "NA" ] && [ "$w2" -gt "$w1" ]; then ok "write.rows 在增长（喂数正常）"
elif [ "$d1" != "NA" ] && [ "$d2" != "NA" ] && [ "$d2" -gt "$d1" ]; then ok "db.rows 在增长（喂数正常）"
else bad "25s 内 write.rows 与 db.rows 都没增长 ⇒ 喂数可能停摆（跑 feeder-watch.sh 看四级判据）"; fi
printf '  write.failed=%s  db.probe.failed=%s\n' "$(act iot.timeseries.write.failed)" "$(act iot.timeseries.db.probe.failed)"

head2 "7. zram / vm.swappiness（重启后应由 systemd 单元自动恢复）"
sw="$(cat /proc/sys/vm/swappiness)"
[ "$sw" = "150" ] && ok "vm.swappiness = 150" || bad "vm.swappiness = $sw（期望 150）"
if swapon --show=NAME --noheadings 2>/dev/null | tr -d ' ' | grep -qx /dev/zram0; then ok "/dev/zram0 是活动 swap"; else bad "/dev/zram0 不是活动 swap"; fi
printf '  zram-swap.service: active=%s enabled=%s\n' "$(systemctl is-active zram-swap.service)" "$(systemctl is-enabled zram-swap.service)"
[ "$(systemctl is-active zram-swap.service)" = "active" ] || bad "zram-swap.service 不是 active"
[ -r /sys/block/zram0/mm_stat ] && printf '  mm_stat = %s\n' "$(cat /sys/block/zram0/mm_stat)"
zr="$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' /sys/block/zram0/comp_algorithm 2>/dev/null)"
[ "$zr" = "lz4" ] && ok "zram 压缩算法 = lz4" || bad "zram 压缩算法 = ${zr:-?}（期望 lz4）"

head2 "8. 喂数源 systemd 单元是否自启（这条是本轮踩过的坑：曾经是手工 nohup 进程）"
printf '  access-tcp-simulator.service: active=%s enabled=%s NRestarts=%s\n' \
  "$(systemctl is-active access-tcp-simulator.service)" "$(systemctl is-enabled access-tcp-simulator.service)" \
  "$(systemctl show -p NRestarts --value access-tcp-simulator.service 2>/dev/null)"
[ "$(systemctl is-active access-tcp-simulator.service)" = "active" ] || bad "喂数源单元不是 active"
if ss -ltn 2>/dev/null | grep -q '172.20.0.1:19002'; then ok "172.20.0.1:19002 在监听"; else bad "172.20.0.1:19002 无监听"; fi
if ss -ltn 2>/dev/null | grep -q '0.0.0.0:19002'; then bad "19002 监听在 0.0.0.0（对外暴露，应为 172.20.0.1）"; else ok "19002 未绑定 0.0.0.0"; fi

head2 "9. MALLOC_ARENA_MAX（5 个应用应都为 2）"
for c in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  v="$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MALLOC_ARENA_MAX=//p')"
  [ "$v" = "2" ] && ok "$(printf '%-16s MALLOC_ARENA_MAX=2' "$c")" || bad "$(printf '%-16s MALLOC_ARENA_MAX=%s（期望 2）' "$c" "${v:-UNSET}")"
done

head2 "10. 磁盘 / 内存 / 内核 OOM 计数"
df -h / | sed 's/^/  /'
free -m | sed 's/^/  /'
# 注意：`grep -c` 在 0 命中时**退出码为 1 但会打印 0**，所以这里**不能**再 `|| echo 0`
# （那样会得到两行 "0\n0"，把判据算成非 0 ⇒ 假 FAIL。本轮实测踩到，已修。）
k="$(dmesg 2>/dev/null | grep -c 'Out of memory: Killed' || true)"
k="$(printf '%s' "$k" | head -1)"
printf '  本次启动 OOM 次数 = %s\n' "$k"
[ "$k" = "0" ] && ok "本次启动无内核 OOM" || bad "本次启动发生 $k 次内核 OOM"

echo
if [ "$fail" -gt 0 ]; then echo "==== 判定：FAIL（$fail 项未通过，$unknown 项无法求值） ===="; exit 1; fi
if [ "$unknown" -gt 0 ]; then echo "==== 判定：全部已求值的判据通过，但有 $unknown 项无法求值 ===="; exit 2; fi
echo "==== 判定：全部通过 ===="
exit 0
