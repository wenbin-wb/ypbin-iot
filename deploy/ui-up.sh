#!/usr/bin/env bash
# 一条命令把 IoT 前端拉起来：构建静态产物 → 放到 IOT_UI_DIST_DIR → 起 nginx 容器 → 打印访问地址。
#
# 为什么这样做：本项目的部署形态是「前端静态产物 + nginx 容器」（与 ypbin-admin-ui 同一模式），
# 这样前端与后端解耦——后端换版本不需要重建前端镜像，前端换版本也不需要动后端。
#
# 用法：
#   cd ypbin-iot/deploy && ./ui-up.sh                 # 默认：构建 ../ypbin-iot-ui 并起 ypbin-iot-ui
#   UI_REPO=/path/to/ypbin-iot-ui ./ui-up.sh          # 指定前端仓路径
#   SKIP_BUILD=1 ./ui-up.sh                           # 前端产物已就绪（如 CI 产出），只起容器
set -euo pipefail
cd "$(dirname "$0")"

UI_REPO="${UI_REPO:-../ypbin-iot-ui}"
DIST_DIR="${IOT_UI_DIST_DIR:-../iot-ui-dist}"
UI_PORT="${IOT_UI_PORT:-19001}"
APP_FILTER="${UI_APP_FILTER:-@vben/web-antd}"

log() { printf '\033[1;34m[ui-up]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[ui-up] %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1（请先安装）"; }

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  need node
  need pnpm
  [ -d "$UI_REPO" ] || die "找不到前端仓：$UI_REPO（可用 UI_REPO=... 指定；或先 git clone https://github.com/wenbin-wb/ypbin-iot-ui.git）"
  log "构建前端：$UI_REPO（filter=$APP_FILTER）"
  ( cd "$UI_REPO" && pnpm install --frozen-lockfile && pnpm -F "$APP_FILTER" build )
  SRC="$UI_REPO/apps/web-antd/dist"
  [ -d "$SRC" ] || die "构建完成但找不到产物目录：$SRC"
  log "暂存产物：$SRC → $DIST_DIR"
  rm -rf "$DIST_DIR"
  mkdir -p "$DIST_DIR"
  cp -r "$SRC"/. "$DIST_DIR"/
else
  log "跳过构建（SKIP_BUILD=1）；使用已有产物：$DIST_DIR"
fi

[ -f "$DIST_DIR/index.html" ] || die "$DIST_DIR/index.html 不存在——产物不对（需要的是 dist 目录内容，不是 dist 本身）"

need docker
log "启动/更新 ypbin-iot-ui 容器"
docker compose up -d --no-deps ypbin-iot-ui

HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
log "完成。打开： http://${HOST_IP:-<服务器IP>}:${UI_PORT}"
log "登录后左侧应出现「设备台账 / 产品与物模型 / 设备分组 / 维护窗口（计划停机）」（菜单/权限来自 ypbin-iot 的 007-iot-data.sql）"
log "排障：docker logs ypbin-iot-ui；接口 404/502 时确认 ypbin-gateway 已起（docker compose ps）"
