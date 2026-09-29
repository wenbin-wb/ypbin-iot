#!/usr/bin/env bash
# =============================================================================
# emqx-tls-rollback.sh —— 撤回 8883（MQTT over TLS）对外暴露（**一行式回滚**）
# =============================================================================
# 在**中间件机**上运行。作用域**刻意很窄**：只把 TLS 8883 这一件事撤回，不动 1883（明文）
# —— 本批（看板 #9 第一步）的边界就是"只开 TLS，不收回明文"。
#
# 做三件事（幂等，可重复执行）：
#   ① `/opt/emqx/.env`：`EMQX_MQTT_TLS_BIND_ADDR` 改回 `127.0.0.1`（= 不再对外发布 8883）
#   ② `/opt/emqx/emqx.conf`：`listeners.ssl.default.enable` 改回 `false`（= 容器内关停 listener）
#   ③ 重建**仅 EMQX 容器**（数据卷/网络/别的容器一律不动）
#
# ⚠️ 与 `emqx-mqtt-expose.sh close` 的区别：那个管的是 **1883** 的宿主 INPUT 规则（纵深防御）；
#    本脚本管的是 **8883** 的绑定地址与容器内 listener。两者互不影响，别互相替代。
#
# 🔴 复原（重新开 8883）：把 ① 改回 `0.0.0.0`、② 改回 `true`，再 `docker compose up -d emqx`。
#    等价的显式写法（不改脚本）：
#      sed -i 's/^EMQX_MQTT_TLS_BIND_ADDR=.*/EMQX_MQTT_TLS_BIND_ADDR=0.0.0.0/' /opt/emqx/.env
#      sed -i '/listeners.ssl.default {/,/^}/ s/enable *= *false/enable = true/' /opt/emqx/emqx.conf
#      cd /opt/emqx && docker compose up -d emqx
#
# 退出码：0 = 已撤回（或本就已撤回）；1 = 失败
# =============================================================================
set -uo pipefail

ENV_FILE="${ENV_FILE:-/opt/emqx/.env}"
CONF_FILE="${CONF_FILE:-/opt/emqx/emqx.conf}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/emqx}"
BACKUP_DIR="${BACKUP_DIR:-/opt/emqx/backup}"
fail=0
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; fail=1; }
info(){ printf '  ·  %s\n' "$1"; }

echo "=== 撤回 8883 对外发布（$(date -u +%FT%TZ) UTC）==="
[ -r "$ENV_FILE" ]  || { bad "读不到 $ENV_FILE"; exit 1; }
[ -r "$CONF_FILE" ] || { bad "读不到 $CONF_FILE"; exit 1; }

# 改动前留一份带时间戳的备份（回滚物；权限 600，配置文件里无明文口令，但仍按紧口径处理）
install -d -m 700 "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
cp -a "$ENV_FILE"  "$BACKUP_DIR/env.$STAMP"  && chmod 600 "$BACKUP_DIR/env.$STAMP"
cp -a "$CONF_FILE" "$BACKUP_DIR/emqx.conf.$STAMP" && chmod 600 "$BACKUP_DIR/emqx.conf.$STAMP"
ok "改动前快照：$BACKUP_DIR/{env,emqx.conf}.$STAMP"

# ① 绑定地址 → 回环
if grep -q '^EMQX_MQTT_TLS_BIND_ADDR=' "$ENV_FILE"; then
  sed -i 's/^EMQX_MQTT_TLS_BIND_ADDR=.*/EMQX_MQTT_TLS_BIND_ADDR=127.0.0.1/' "$ENV_FILE"
  ok "① $ENV_FILE：EMQX_MQTT_TLS_BIND_ADDR=127.0.0.1"
else
  info "① $ENV_FILE 本就没有 EMQX_MQTT_TLS_BIND_ADDR ⇒ compose 默认值即 127.0.0.1（幂等）"
fi

# ② 容器内 listener → false（只改 listeners.ssl.default 块内那一行）
if sed -n '/^listeners\.ssl\.default[[:space:]]*{/,/^}/p' "$CONF_FILE" | grep -qE '^[[:space:]]*enable[[:space:]]*=[[:space:]]*true'; then
  sed -i '/^listeners\.ssl\.default[[:space:]]*{/,/^}/ s/^\([[:space:]]*enable[[:space:]]*=[[:space:]]*\)true/\1false/' "$CONF_FILE"
  ok "② $CONF_FILE：listeners.ssl.default.enable = false"
else
  info "② listeners.ssl.default 本就不是 enable=true（幂等）"
fi

# 回读（不信"我刚改过"，只信回读）
grep -q '^EMQX_MQTT_TLS_BIND_ADDR=127.0.0.1$' "$ENV_FILE" \
  && ok "回读：绑定地址 = 127.0.0.1" || bad "回读：绑定地址未落到 127.0.0.1"
sed -n '/^listeners\.ssl\.default[[:space:]]*{/,/^}/p' "$CONF_FILE" | grep -qE 'enable[[:space:]]*=[[:space:]]*false' \
  && ok "回读：listeners.ssl.default.enable = false" || bad "回读：listener 未落到 enable=false"

# ③ 重建**仅 EMQX 容器**
if command -v docker >/dev/null 2>&1 && [ -f "$COMPOSE_DIR/docker-compose.yml" ]; then
  ( cd "$COMPOSE_DIR" && docker compose up -d emqx >/dev/null 2>&1 ) \
    && ok "③ 已重建仅 EMQX 容器（其他容器/卷/网络未动）" \
    || bad "③ docker compose up -d emqx 失败"
  # 自证：宿主侧不该再有 8883 监听
  sleep 3
  addrs="$(ss -lnt 2>/dev/null | awk '{print $4}' | grep -E '[:.]8883$' | sort -u | tr '\n' ' ')"
  [ -z "$addrs" ] && ok "回读：宿主已无 8883 监听" || bad "回读：宿主仍有 8883 监听（$addrs）"
  docker exec ypbin-emqx /opt/emqx/bin/emqx ctl listeners 2>/dev/null \
    | awk '/^ssl:default/{f=1} f&&/^[a-z]+:/&&!/^ssl:default/{f=0} f' | sed 's/^/     /'
else
  info "③ 未找到 $COMPOSE_DIR/docker-compose.yml ⇒ 跳过重建（请手工重建容器）"
fi

echo
[ "$fail" -eq 0 ] && echo "结论: 8883 已撤回（1883 未受影响）" || echo "结论: 回滚未完成（见上面 ❌）"
exit "$fail"
