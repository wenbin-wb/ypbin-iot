#!/usr/bin/env bash
# ============================================================
# 生成 Redis `requirepass` 受管配置文件（deploy/redis-requirepass.conf）
# ============================================================
#
# 为什么需要这个文件：
#   旧写法 `command: ["redis-server", "--appendonly", "yes", "--requirepass", "<口令>"]` 会把**明文口令**
#   留在容器 argv ⇒ `docker top`、宿主 `ps`、`docker events` 的 exec 属性都能直接读到（2026-09-26 实测）。
#   改成「配置里写 requirepass、口令只落一个 600 文件」后，口令不进任何 argv，也不进 `docker inspect`。
#
# 为什么不是「环境变量 + 覆盖 entrypoint 写临时配置」：
#   官方 redis 镜像的 entrypoint 只在首个参数是 `redis-server` 时才做 `chown /data` + `setpriv` 降权；
#   覆盖 entrypoint 就得自己复刻降权逻辑，且会把口令额外注入容器 env（`docker inspect` 可读）。
#   本脚本让 compose 继续以 `redis-server <conf>` 启动 —— 官方启动路径与运行身份（镜像内 uid=999）不变。
#
# 用法：
#   sudo bash deploy/redis-auth-conf.sh                     # 读同目录 .env，写同目录 redis-requirepass.conf
#   ENV_FILE=/path/.env OUT=/path/redis-requirepass.conf bash deploy/redis-auth-conf.sh
#
# 安全约定：**绝不**把口令写入 stdout/stderr；只输出长度与文件属主/权限。
# 幂等：内容由 .env 的 REDIS_PASSWORD 决定，重复执行结果一致（值变了则覆盖）。
#
# 依赖：只需要 bash + sed（无 openssl）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/.env}"
OUT="${OUT:-$SCRIPT_DIR/redis-requirepass.conf}"

# 镜像内 redis 用户（redis:7-alpine 实测 `id redis` = uid=999 gid=1000）。
# 容器启动时 entrypoint 会 setpriv 降权到该用户后才解析配置文件 ⇒ 文件必须对它可读。
# 600 + chown 到该 uid/gid：宿主机上除 root 与 uid 999 之外无人可读。
REDIS_UID="${REDIS_UID:-999}"
REDIS_GID="${REDIS_GID:-1000}"

die() { printf '!! %s\n' "$1" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "找不到 $ENV_FILE（先复制 deploy/.env.example 或用 install.sh 生成）"

# 只取最后一处赋值（与 .env 的「后写覆盖」语义一致），值不进 argv。
PW="$(sed -n 's/^REDIS_PASSWORD=//p' "$ENV_FILE" | tail -n 1)"
[ -n "$PW" ] || die "$ENV_FILE 里 REDIS_PASSWORD 为空/缺失；本脚本只服务「compose 内建 Redis 需要口令」的场景
   （NO_DOCKER=1 用外部无认证 Redis 时不要调用本脚本，也不要挂载该文件）"

# redis.conf 的指令是「空格分隔的单行」：值里出现空白/引号/换行会让解析歧义 ⇒ 直接拒绝，不静默写出坏配置。
case "$PW" in
  *[[:space:]\'\"\\]*) die "REDIS_PASSWORD 含空白/引号/反斜杠，requirepass 单行写法会歧义；请改用十六进制等无空白字符集" ;;
esac

OUT_DIR="$(dirname "$OUT")"
[ -d "$OUT_DIR" ] || die "输出目录不存在：$OUT_DIR"

# umask 077：临时文件与目标文件都不会短暂出现「组/他人可读」的窗口。
umask 077
printf 'requirepass %s\n' "$PW" > "$OUT"
chmod 600 "$OUT"

if [ "$(id -u)" = "0" ]; then
  chown "$REDIS_UID:$REDIS_GID" "$OUT"
  OWNER="$(stat -c '%u:%g' "$OUT")"
else
  # 非 root 下 chown 必然失败；此时容器内 redis 用户可能读不到文件 ⇒ 明确告警而不是让 redis 启动期才报错。
  printf '!  当前非 root，未执行 chown %s:%s；容器内 redis 用户可能读不到 %s（请以 root 重跑）\n' \
    "$REDIS_UID" "$REDIS_GID" "$OUT" >&2
  OWNER="$(stat -c '%u:%g' "$OUT")"
fi

printf 'ok: %s  mode=%s owner=%s pw_len=%s（口令值未打印）\n' \
  "$OUT" "$(stat -c '%a' "$OUT")" "$OWNER" "${#PW}"
