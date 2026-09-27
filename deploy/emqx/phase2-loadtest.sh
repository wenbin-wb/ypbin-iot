#!/usr/bin/env bash
# =============================================================================
# phase2-loadtest.sh —— 阶段② 「限内存 OOM/存活实测」（设计 §8.3 的门槛）
# =============================================================================
# 在**中间件机**上运行。按设计 §8.3 的观测协议做，判据用数据说话：
#   ① 空闲基线（free -m / docker stats）
#   ② 两档负载：100 连接、500 连接；**每连接 1 msg/s，持续 --duration 秒（默认 600s = 10 分钟）**
#   ③ 观测：OOMKilled / RestartCount / mem_limit 使用率 / 宿主机 available / `/` 使用率 /
#            消息延迟 p95（latency-probe.py，订阅端逐条统计）/ EMQX 侧 dropped.*
#   ④ 通过/不通过结论 + 阈值对照表
#   ⑤ 压测后清理测试数据与账号（前后对比）
#
# 压测工具：**EMQX 官方 emqtt-bench**（不是自写脚本）
#   · 镜像 pin：docker.m.daocloud.io/emqx/emqtt-bench:0.6.2
#     digest sha256:b34b364859dab936507a73388fcf55d6a9fe422e17bbf36997e884500288dcf8
#   · 上游 release：https://github.com/emqx/emqtt-bench/releases/tag/0.6.2
#   · `--pull=never` 保证压测期间不依赖外网/镜像源
#
# ⚠️ 两个**实测踩过的坑**（读源码 + 实跑确认，写在这里免得下次再踩）：
#   1. `emqtt-bench pub -L/--limit` 是**全局**消息上限，**不是**每客户端上限：
#      `main(pub, Opts)` 里 `pub_limit_fun_init(...)` 只调一次，闭包被所有 client 共享
#      （src/emqtt_bench.erl:315 / :840-853）。⇒ 要"N 连接 × 1 msg/s × D 秒"必须传 `-L N*D`。
#      传 `-L D` 会让压测在几秒内就结束（实测 `-c 100 -L 45` 只发出 45 条）。
#   2. emqtt-bench **拿不到消息延迟的分位数**：`-Q true` 时控制台每秒只打印
#      `publish_latency avg=Xms`（**均值**）；逐消息的 `publish_lat` 只在 `-Q log`（dlog）
#      模式写入（src/emqtt_bench.erl:1419 `is_qoe_dlog() andalso pub_qoe(...)`），而官方
#      0.6.2 的 dlog→CSV 导出实测 Publish 列为空（列名有、值为 invalid_elapsed 被写成空串）。
#      ⇒ 延迟 p95 改由本目录的 **latency-probe.py**（~60 行、paho-mqtt 2.1.0、只订阅不施压）
#      逐条统计；负载**仍然全部**由官方 emqtt-bench 产生。
#
# ⚠️ 凭据口径（**别写成"只在进程内存"**，实测会打脸）：
#   · 本脚本自己用 `curl -K <600 文件>`，API Key 不进 argv；
#   · 但**压测端 emqtt-bench 只支持 `-P <明文口令>`** ⇒ 压测期间该口令会出现在
#     `docker inspect <pub 容器>` 的 `Config.Cmd` 里（以及 /proc/<pid>/cmdline）。
#     对策：输出目录默认 700；`cleanup()` 立即删除 `bench/*.inspect.json` 与 `*.cid`；
#     账号在压测结束时删除（口令随之失效）。**不声称"口令绝不落盘"**。
#   · 要保留 inspect 留档请显式加 `--keep-out`（自行承担口令残留在已失效账号上的风险）。
#
# 用法：
#   bash phase2-loadtest.sh --tier 100 --duration 600 --out /var/tmp/emqx-loadtest/tier100
#   bash phase2-loadtest.sh --tier 500 --duration 600 --out /var/tmp/emqx-loadtest/tier500
#   bash phase2-loadtest.sh --tier 100 --duration 60  --out /tmp/smoke      # 冒烟
# =============================================================================
set -uo pipefail

ENV_FILE=/opt/emqx/.env
BASE_URL=http://127.0.0.1:18093
CONTAINER=ypbin-emqx
NETWORK=emqx-edge
MQTT_HOST=ypbin-emqx
MQTT_PORT=1883
BENCH_IMAGE=docker.m.daocloud.io/emqx/emqtt-bench:0.6.2
BENCH_DIGEST=sha256:b34b364859dab936507a73388fcf55d6a9fe422e17bbf36997e884500288dcf8

TIER=""
DURATION=600
QOS=1
PERIOD_MS=1000          # 每连接 1 msg/s
OUT=""
KEEP=0
KEEP_OUT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tier)     TIER="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --qos)      QOS="$2"; shift 2 ;;
    --out)      OUT="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --keep-users) KEEP=1; shift ;;
    --keep-out)   KEEP_OUT=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done
[ -n "$TIER" ] || { echo "必须给 --tier <连接数>" >&2; exit 1; }
[ -n "$OUT" ] || OUT="/var/tmp/emqx-loadtest-$(date +%Y%m%d-%H%M%S)-tier$TIER"
mkdir -p "$OUT"

[ -r "$ENV_FILE" ] || { echo "读不到 $ENV_FILE" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${EMQX_API_KEY:?}"; : "${EMQX_API_SECRET:?}"

# ── 凭据不进 argv：curl 从**私有 700 目录里的 600 配置文件**读（argv 里只有文件路径）──
# 远程调用必须显式超时（仓内铁律）
CURL_MAXTIME=10
CURL_CONNECT_TIMEOUT=3
PRIV="$(mktemp -d)"; chmod 700 "$PRIV"
printf 'user = "%s:%s"\n' "$EMQX_API_KEY" "$EMQX_API_SECRET" >"$PRIV/curlrc"; chmod 600 "$PRIV/curlrc"

# 输出目录：默认 700（里面有压测端的命令记录，含**压测期间有效**的临时口令）
mkdir -p "$OUT"; chmod 700 "$OUT"

TENANT=9001
LOAD_DEV="${TENANT}.9001"          # 压测设备账号：只能 publish 自己的 up/#
LOAD_SVC="svc-load"                # 压测订阅账号：临时授权 subscribe ypbin/v1/+/+/up/#
UP_TOPIC="ypbin/v1/${TENANT}/9001/up/property"
SUB_TOPIC='ypbin/v1/+/+/up/#'
AUTH_ID='password_based%3Abuilt_in_database'
SAMPLES="$OUT/samples.csv"
SUMMARY="$OUT/summary.txt"
BENCH_OUT="$OUT/bench"
mkdir -p "$BENCH_OUT"; chmod 700 "$BENCH_OUT"

api() {  # 回显 HTTP 状态码（调用方可判定失败；**不再一律丢弃**）
  local method="$1" path="$2" data="${3:-}"
  local args=(-s --max-time "$CURL_MAXTIME" --connect-timeout "$CURL_CONNECT_TIMEOUT" \
              -K "$PRIV/curlrc" -o /dev/null -w '%{http_code}' -X "$method"
              -H 'Content-Type: application/json')
  if [ -n "$data" ]; then
    # 用 `--data-binary @file`：body 走**私有 600 文件**，不出现在 argv（口令/用户名不进 /proc）
    printf '%s' "$data" >"$PRIV/data.json"; chmod 600 "$PRIV/data.json"
    args+=(--data-binary "@$PRIV/data.json")
  fi
  curl "${args[@]}" "$BASE_URL$path"
}
# 一次取回本轮需要的全部 EMQX 指标（避免逐指标多次 curl 拖慢采样周期）
m_snapshot() {
  curl -s --max-time "$CURL_MAXTIME" --connect-timeout "$CURL_CONNECT_TIMEOUT" -K "$PRIV/curlrc" \
    "$BASE_URL/api/v5/metrics?aggregate=true" \
    | python3 -c '
import json,sys
d=json.load(sys.stdin)
ks=["packets.publish.received","delivery.dropped.queue_full","delivery.dropped.expired",
    "messages.dropped","messages.dropped.no_subscribers","client.connected","authorization.matched.allow"]
print(",".join(str(d.get(k,-1)) for k in ks))
' 2>/dev/null || echo "-1,-1,-1,-1,-1,-1,-1"
}
host_avail_mb()  { free -m | awk '/^Mem:/{print $7}'; }
disk_use_pct()   { df -P / | awk 'NR==2{gsub("%","",$5);print $5}'; }
disk_avail()     { df -h / | awk 'NR==2{print $4}'; }

CLEANED=0
cleanup() {
  [ "$CLEANED" -eq 1 ] && return   # EXIT 与 INT/TERM 都会触发；幂等重入保护
  CLEANED=1
  docker rm -f "emqx-load-pub-$TIER" >/dev/null 2>&1
  # 压测端（emqtt-bench）只支持 `-P <明文口令>` ⇒ 它的 `docker inspect` 产物
  # （bench/pub.inspect.json 的 Config.Cmd）里含压测期间的临时口令。账号删除后口令即失效，
  # 但仍立即删掉这些命令记录；要留档请显式 --keep-out。
  if [ "$KEEP_OUT" -eq 0 ]; then
    rm -f "$BENCH_OUT"/*.inspect.json "$BENCH_OUT"/*.cid
  fi

  # ⚠️ **顺序不能反**：`api` 依赖 `$PRIV/curlrc`，所以**必须先删账号、再删 $PRIV**。
  #    上一版把 `rm -rf "$PRIV"` 写在这里之前 ⇒ 四个 DELETE 全部静默失败、账号永不清理
  #    （实测回归：脚本退出后临时口令仍能登录）。且**不再吞掉失败**（禁静默降级）。
  if [ "$KEEP" -eq 1 ]; then
    echo "⚠️ --keep-users：**保留**临时账号 $LOAD_DEV / $LOAD_SVC 与它们的 ACL 规则（排查用，务必手工清理）" >&2
  else
    # 显式四条删除（**不要**用字符串解析来区分"用户/规则"——上一版据此把规则当用户删，
    # 结果 rules/users 里残留 svc-load 规则；且 `api` 的返回码必须被检查，不许静默吞掉）。
    del_or_warn() { # del_or_warn <说明> <path>
      local rc; rc=$(api DELETE "$2")
      case "$rc" in
        204|404) printf '  %-22s 已清理（HTTP %s）\n' "$1" "$rc" ;;
        *)       printf '  ⚠️ %-19s 清理失败（HTTP %s）—— 临时对象可能残留，请手工检查\n' "$1" "$rc" >&2 ;;
      esac
    }
    del_or_warn "认证用户 $LOAD_DEV" "/api/v5/authentication/$AUTH_ID/users/$LOAD_DEV"
    del_or_warn "认证用户 $LOAD_SVC" "/api/v5/authentication/$AUTH_ID/users/$LOAD_SVC"
    del_or_warn "ACL 规则 $LOAD_SVC" "/api/v5/authorization/sources/built_in_database/rules/users/$LOAD_SVC"
    del_or_warn "授权缓存"           "/api/v5/authorization/cache"
    unset -f del_or_warn
  fi
  rm -rf "$PRIV"
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM

read -r M0_RECV M0_DROPQ M0_DROPE M0_MSGDROP M0_NOSUB M0_CONN M0_ALLOW <<<"$(m_snapshot | tr ',' ' ')"
M0_AVAIL="$(host_avail_mb)"; M0_DISK="$(disk_use_pct)"
M0_OOM="$(docker inspect "$CONTAINER" --format '{{.State.OOMKilled}}')"
M0_RESTART="$(docker inspect "$CONTAINER" --format '{{.RestartCount}}')"

############################ ① 空闲基线 ############################
{
  echo "================ 阶段② 限内存 OOM/存活实测 ================"
  echo "开始(UTC)        : $(date -u +%FT%TZ)"
  echo "档位(连接数)     : $TIER"
  echo "时长(s)          : $DURATION"
  echo "每连接发送间隔   : ${PERIOD_MS}ms（= 1 msg/s）⇒ 全局消息上限 -L $((TIER*DURATION))"
  echo "QoS              : $QOS"
  echo "压测工具         : emqtt-bench $BENCH_IMAGE"
  echo "压测工具 digest  : $BENCH_DIGEST"
  echo "EMQX 镜像        : $(docker inspect "$CONTAINER" --format '{{.Config.Image}}')"
  echo "EMQX image id    : $(docker inspect "$CONTAINER" --format '{{.Image}}')"
  echo "mem_limit(bytes) : $(docker inspect "$CONTAINER" --format '{{.HostConfig.Memory}}')"
  echo "节点             : $(docker exec "$CONTAINER" /opt/emqx/bin/emqx ctl status 2>&1 | head -1)"
  echo "-------- ① 空闲基线（压测前） --------"
  free -m | sed 's/^/  /'
  df -h / | sed 's/^/  /'
  docker stats --no-stream --format '  {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'
  echo "  OOMKilled=${M0_OOM} RestartCount=${M0_RESTART} Health=$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')"
  echo "  EMQX 指标基线：publish.received=${M0_RECV} delivery.dropped.queue_full=${M0_DROPQ} delivery.dropped.expired=${M0_DROPE} client.connected=${M0_CONN}"
} | tee "$SUMMARY"

############################ 测试账号 ############################
PW_D="$(openssl rand -hex 12)"; PW_S="$(openssl rand -hex 12)"
for pair in "$LOAD_DEV:$PW_D" "$LOAD_SVC:$PW_S"; do
  # 预清理 + 建号：返回码**必须检查**（否则压测端可能因为账号没建上而白跑，甚至"零负载 PASS"）
  rc=$(api DELETE "/api/v5/authentication/$AUTH_ID/users/${pair%%:*}")
  case "$rc" in 204|404) : ;; *) echo "  ⚠️ 预清理 ${pair%%:*} 返回 HTTP $rc（非 204/404）" >&2 ;; esac
  rc=$(api POST "/api/v5/authentication/$AUTH_ID/users" \
      "{\"user_id\":\"${pair%%:*}\",\"password\":\"${pair#*:}\",\"is_superuser\":false}")
  case "$rc" in 201) : ;; *) echo "  ⚠️ 建号 ${pair%%:*} 返回 HTTP $rc（期望 201）—— 压测端多半连不上" >&2 ;; esac
done
rc=$(api POST /api/v5/authorization/sources/built_in_database/rules/users \
    "[{\"username\":\"$LOAD_SVC\",\"rules\":[{\"action\":\"subscribe\",\"permission\":\"allow\",\"topic\":\"$SUB_TOPIC\"}]}]")
case "$rc" in 204) : ;; *) echo "  ⚠️ 建订阅规则 $LOAD_SVC 返回 HTTP $rc（期望 204）" >&2 ;; esac
rc=$(api DELETE /api/v5/authorization/cache)
[ "$rc" = 204 ] || echo "  ⚠️ 清授权缓存返回 HTTP $rc（非 204）" >&2
echo "测试账号：$LOAD_DEV（publish 自己的 up/）、$LOAD_SVC（subscribe $SUB_TOPIC）；口令不回显；但对压测端 emqtt-bench 只能经 -P 传入 ⇒ 压测期间会出现在容器 argv/inspect 里；结束即删账号（口令随之失效）并删除 inspect 产物（见文件头「凭据口径」）" | tee -a "$SUMMARY"

############################ ② 施压 ############################
echo "-------- ② 施压（$TIER 连接 × 1 msg/s × ${DURATION}s）--------" | tee -a "$SUMMARY"
docker rm -f "emqx-load-pub-$TIER" >/dev/null 2>&1

# 订阅端：**只读延迟观测器**（latency-probe.py，订阅平台将来要订阅的 `ypbin/v1/+/+/up/#`）。
# 不用 emqtt-bench 的 sub 统计 p95 —— 0.6.2 的控制台只给 publish 延迟**均值**，P95 只在
# `-Q log`（dlog）模式写入且官方 dlog→CSV 导出实测拿不到 Publish 列（见脚本头部说明）。
PROBE_PY="$(dirname "${BASH_SOURCE[0]}")/latency-probe.py"
PROBE_PYTHON=/opt/emqx/venv/bin/python
LATENCY_LOG="$OUT/latency.txt"
: >"$LATENCY_LOG"
# ⚠️ 观测器跑在**宿主机**上（不在 emqx-edge 网里）⇒ 必须用回环地址；`ypbin-emqx` 解析不了
#    （实测踩过：socket.gaierror Name or service not known）。1883 在宿主侧只绑 127.0.0.1，正好。
MQTT_PASSWORD="$PW_S" nohup "$PROBE_PYTHON" "$PROBE_PY" \
  --host 127.0.0.1 --port "$MQTT_PORT" --username "$LOAD_SVC" \
  --topic "$SUB_TOPIC" --qos "$QOS" --duration "$((DURATION+15))" --out "$LATENCY_LOG" \
  >"$BENCH_OUT/latency-probe.log" 2>&1 &
PROBE_PID=$!
sleep 5

# 发布端：TIER 个连接，连接速率 TIER/s（全部在 ~1s 内建连），每连接 1 msg/s
docker run -d --name "emqx-load-pub-$TIER" --pull=never --network "$NETWORK" \
  "$BENCH_IMAGE" pub -h "$MQTT_HOST" -p "$MQTT_PORT" -u "$LOAD_DEV" -P "$PW_D" \
  -c "$TIER" -R "$TIER" -I "$PERIOD_MS" -L "$((TIER*DURATION))" -q "$QOS" \
  -t "$UP_TOPIC" --payload-hdrs ts \
  >"$BENCH_OUT/pub.cid" 2>&1

echo "ts_utc,sample,elapsed_s,host_avail_mb,disk_use_pct,emqx_mem_used,emqx_mem_limit,emqx_mem_pct,oomkilled,restartcount,health,bench_pub_mem,pub_received,dropped_queue_full,dropped_expired,messages_dropped,no_subscribers,client_connected_total" > "$SAMPLES"
start=$(date +%s); i=0
min_avail=999999; max_mem_pct=0; max_disk="$M0_DISK"
while docker inspect "emqx-load-pub-$TIER" --format '{{.State.Running}}' 2>/dev/null | grep -q true; do
  i=$((i+1)); now=$(date +%s); el=$((now-start))
  avail="$(host_avail_mb)"; du="$(disk_use_pct)"
  read -r used limit pct <<<"$(docker stats --no-stream --format '{{.MemUsage}}|{{.MemPerc}}' "$CONTAINER" | awk -F'|' '{split($1,a," / "); print a[1], a[2], $2}')"
  bmem="$(docker stats --no-stream --format '{{.MemUsage}}' "emqx-load-pub-$TIER" 2>/dev/null | awk '{print $1}')"
  oom="$(docker inspect "$CONTAINER" --format '{{.State.OOMKilled}}')"
  rc="$(docker inspect "$CONTAINER" --format '{{.RestartCount}}')"
  hl="$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')"
  read -r pr dq de md ns cc al <<<"$(m_snapshot | tr ',' ' ')"
  echo "$(date -u +%FT%TZ),$i,$el,$avail,$du,$used,$limit,$pct,$oom,$rc,$hl,${bmem:-NA},$pr,$dq,$de,$md,$ns,$cc" >> "$SAMPLES"
  [ "$avail" -lt "$min_avail" ] && min_avail="$avail"
  if [[ "${pct}" =~ ^[0-9.]+%$ ]]; then
    pc=$(printf '%.0f' "${pct%\%}")
  else
    pc=-1   # 读不到就**不参与峰值统计**，而不是静默当成 0（避免"看起来内存很低"）
    echo "  ⚠️ 第 $i 次采样读不到 emqx 的 MemPerc（'$pct'），该点不计入峰值" >&2
  fi
  [ "$pc" -gt "$max_mem_pct" ] && max_mem_pct="$pc"
  [ "$du" -gt "$max_disk" ] && max_disk="$du"
  sleep 10
done
elapsed=$(( $(date +%s) - start ))
echo "施压结束：实际时长 ${elapsed}s（采样 $i 次）" | tee -a "$SUMMARY"

# ── 负载自证（**没有这一条，压测端启动失败时也会打印 PASS**）──────────────────
# 三条独立证据：① 发布端容器真的在运行过；② 施压时长接近标称；③ 采样到至少 1 次。
LOAD_OK=1; LOAD_WHY=""
if ! docker inspect "emqx-load-pub-$TIER" >/dev/null 2>&1; then
  LOAD_OK=0; LOAD_WHY="发布端容器 emqx-load-pub-$TIER 从未创建（docker run 失败，看 $BENCH_OUT/pub.cid）"
elif [ "$i" -lt 1 ]; then
  LOAD_OK=0; LOAD_WHY="一次采样都没做（发布端即刻退出）"
elif [ "$elapsed" -lt "$((DURATION * 8 / 10))" ]; then
  LOAD_OK=0; LOAD_WHY="施压只持续 ${elapsed}s，远低于标称 ${DURATION}s（≥80% 才算成立）"
fi
if [ "$LOAD_OK" -eq 1 ]; then
  echo "负载自证：PASS（发布端容器存在、施压 ${elapsed}s ≥ 80%×${DURATION}s、采样 $i 次）" | tee -a "$SUMMARY"
else
  echo "负载自证：FAIL（$LOAD_WHY）" | tee -a "$SUMMARY"
fi
# 收尾：取压测端日志与容器的 inspect（-d 模式下 stdout 不会进文件）
docker logs "emqx-load-pub-$TIER" >"$BENCH_OUT/pub.log" 2>&1 || true
docker inspect "emqx-load-pub-$TIER" >"$BENCH_OUT/pub.inspect.json" 2>&1 || true
# 等延迟观测器自然结束（它比压测多跑 15s，用于覆盖收尾窗口）
wait "$PROBE_PID" 2>/dev/null
echo "延迟观测器输出：$(cat "$LATENCY_LOG" 2>/dev/null || echo '(无)')" | tee -a "$SUMMARY"

############################ ③ 观测与判据 ############################
# ⚠️ 变量顺序有坑（踩过两次，勿再改）：
#   ① `HAVE_SAMPLES` 必须在**下面 ③ 报告块**用到它之前赋值（曾把赋值写在 `verdict=0` 前，
#      而报告块在它之前 ⇒ `set -u` 报 unbound variable，整段 ③ 被吃掉）；
#   ② `METRICS_OK` 必须在 `read -r M1_*` **之后**（它要读 M1_DROPQ/M1_DROPE/M1_NOSUB）。
#      `m_snapshot` 失败会回退成 -1，而 `-1 <= -1` 会让 dropped 类判据**假 PASS**（复核者指出）
#      ⇒ 这里要求两端读数都 ≥ 0，否则该项判 FAIL（标注"不可求值"）。
HAVE_SAMPLES=$([ "$i" -ge 1 ] && echo 1 || echo 0)
read -r M1_RECV M1_DROPQ M1_DROPE M1_MSGDROP M1_NOSUB M1_CONN M1_ALLOW <<<"$(m_snapshot | tr ',' ' ')"
M1_OOM="$(docker inspect "$CONTAINER" --format '{{.State.OOMKilled}}')"
M1_RESTART="$(docker inspect "$CONTAINER" --format '{{.RestartCount}}')"
M1_DISK="$(disk_use_pct)"

METRICS_OK=1
for v in "$M0_DROPQ" "$M1_DROPQ" "$M0_DROPE" "$M1_DROPE" "$M0_NOSUB" "$M1_NOSUB"; do
  [ "$v" -ge 0 ] 2>/dev/null || METRICS_OK=0
done

{
  echo "-------- ③ 观测结果 --------"
  if [ "$HAVE_SAMPLES" -eq 1 ]; then
    echo "宿主机 available 最低点 : ${min_avail} MB（基线 ${M0_AVAIL} MB）"
    echo "EMQX MemUsage 峰值占比  : ${max_mem_pct}% of mem_limit（阈值 < 85%）"
  else
    echo "宿主机 available 最低点 : **不可求值（0 次采样）**"
    echo "EMQX MemUsage 峰值占比  : **不可求值（0 次采样）**"
  fi
  echo "OOMKilled               : ${M1_OOM}（基线 ${M0_OOM}）"
  echo "RestartCount            : ${M0_RESTART} -> ${M1_RESTART}（要求不增长）"
  echo "健康状态                : $(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')"
  echo "publish.received 增量   : $(( M1_RECV - M0_RECV ))（期望 ≈ $((TIER*DURATION))）"
  echo "client.connected 累计    : ${M0_CONN} -> ${M1_CONN}（**累计计数器**，非当前连接数）"
  echo "messages.dropped.no_subscribers : ${M0_NOSUB} -> ${M1_NOSUB}（压测期必须为 0 ⇒ 订阅端在场且跟得上）"
  echo "delivery.dropped.queue_full : ${M0_DROPQ} -> ${M1_DROPQ}"
  echo "delivery.dropped.expired    : ${M0_DROPE} -> ${M1_DROPE}"
  echo "messages.dropped            : ${M0_MSGDROP} -> ${M1_MSGDROP}"
  echo "authorization.matched.allow : ${M0_ALLOW} -> ${M1_ALLOW}"
  echo "宿主机 / 使用率          : 基线 ${M0_DISK}% -> 峰值 ${max_disk}%（要求 < 95%）"
  echo "宿主机 / 余量            : $(disk_avail)"
} | tee -a "$SUMMARY"

# 延迟：由只读观测器 latency-probe.py（paho-mqtt）逐条统计
LAT_LINE="$(grep -h '^n=' "$LATENCY_LOG" 2>/dev/null | tail -1)"
[ -n "$LAT_LINE" ] || LAT_LINE="未能求值（观测器未产出结果，见 $BENCH_OUT/latency-probe.log）"
echo "消息延迟（发布→订阅端接收，latency-probe.py / paho-mqtt）：$LAT_LINE" | tee -a "$SUMMARY"

# （`METRICS_OK` 已在 ③ 段开头算好 —— 依赖 `read -r M1_*`，别再在这里重复实现）
verdict=0
chk() { if [ "$2" = "1" ]; then printf '  ✅ PASS  %-34s %s\n' "$1" "$3" | tee -a "$SUMMARY"
        else printf '  ❌ FAIL  %-34s %s\n' "$1" "$3" | tee -a "$SUMMARY"; verdict=1; fi; }
chk "OOMKilled=false"                "$([ "$M1_OOM" = false ] && echo 1 || echo 0)" "$M1_OOM"
chk "RestartCount 窗口内不增长"      "$([ "$M1_RESTART" -eq "$M0_RESTART" ] && echo 1 || echo 0)" "$M0_RESTART -> $M1_RESTART"
chk "MemUsage < 85% mem_limit"       "$([ "$HAVE_SAMPLES" -eq 1 ] && [ "$max_mem_pct" -lt 85 ] && echo 1 || echo 0)" "$([ "$HAVE_SAMPLES" -eq 1 ] && echo "${max_mem_pct}%" || echo "无采样，不可求值")"
chk "宿主机 available ≥ 400MB"       "$([ "$HAVE_SAMPLES" -eq 1 ] && [ "$min_avail" -ge 400 ] && echo 1 || echo 0)" "$([ "$HAVE_SAMPLES" -eq 1 ] && echo "${min_avail}MB" || echo "无采样，不可求值")"
chk "压测期 available ≥ 200MB"       "$([ "$HAVE_SAMPLES" -eq 1 ] && [ "$min_avail" -ge 200 ] && echo 1 || echo 0)" "$([ "$HAVE_SAMPLES" -eq 1 ] && echo "${min_avail}MB" || echo "无采样，不可求值")"
chk "dropped.queue_full 不增长"      "$([ "$METRICS_OK" -eq 1 ] && [ "$M1_DROPQ" -le "$M0_DROPQ" ] && echo 1 || echo 0)" "$([ "$METRICS_OK" -eq 1 ] && echo "$M0_DROPQ -> $M1_DROPQ" || echo "指标读不到（-1），不可求值")"
chk "dropped.expired 不增长"         "$([ "$METRICS_OK" -eq 1 ] && [ "$M1_DROPE" -le "$M0_DROPE" ] && echo 1 || echo 0)" "$([ "$METRICS_OK" -eq 1 ] && echo "$M0_DROPE -> $M1_DROPE" || echo "指标读不到（-1），不可求值")"
chk "无 no_subscribers 丢弃"         "$([ "$METRICS_OK" -eq 1 ] && [ "$M1_NOSUB" -eq "$M0_NOSUB" ] && echo 1 || echo 0)" "$([ "$METRICS_OK" -eq 1 ] && echo "$M0_NOSUB -> $M1_NOSUB" || echo "指标读不到（-1），不可求值")"
chk "宿主机 / 使用率 < 95%"          "$([ "$max_disk" -lt 95 ] && echo 1 || echo 0)" "${max_disk}%"
chk "负载真的发生了（自证三条）"     "$LOAD_OK" "$LOAD_WHY"
chk "publish.received 增量 ≥ 95%×N×D" "$([ "$(( M1_RECV - M0_RECV ))" -ge "$(( TIER * DURATION * 95 / 100 ))" ] && echo 1 || echo 0)" "$(( M1_RECV - M0_RECV )) / $(( TIER * DURATION ))"
chk "容器保持 healthy"               "$([ "$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" = healthy ] && echo 1 || echo 0)" "$(docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')"

docker rm -f "emqx-load-pub-$TIER" >/dev/null 2>&1
echo "结束(UTC)        : $(date -u +%FT%TZ)" >> "$SUMMARY"
echo "采样明细         : $SAMPLES" | tee -a "$SUMMARY"
[ "$verdict" -eq 0 ] && { echo "档位结论: PASS（$TIER 连接 / $DURATION s）" | tee -a "$SUMMARY"; exit 0; }
echo "档位结论: FAIL（$TIER 连接 / $DURATION s）" | tee -a "$SUMMARY"; exit 1
