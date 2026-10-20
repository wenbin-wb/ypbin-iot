# IoT 前端部署（打开即见页面）

> 目标：**一条命令**把 IoT 前端拉起来，登录后左侧出现「设备台账 / 产品与物模型 / 设备分组 / 维护窗口（计划停机）」，
> 并且**可用率/断档**能直接看到（逐台设备的抽屉里）。

## 0. 前置

- 后端已按 `deploy/install.sh` 起好：`ypbin-gateway`（18080）、
  `ypbin-auth`、`ypbin-system`、`ypbin-iot`（18084）、`ypbin-access`（18086）与 MySQL/Nacos/Redis；
- 数据库里已执行 `deploy/sql/006-iot-schema.sql` + `007-iot-data.sql`（或走 `install.sh`）；
  菜单与权限码由 `007-iot-data.sql` 写入，并已授给平台管理员角色与租户模板；
- **时序库（Apache IoTDB）已随 `deploy/docker-compose.yml` 起好**（服务 `iotdb` + 一次性 `iotdb-init`）；
  历史曲线/时序写入的部署、初始化与排障见 [`DEPLOY-TIMESERIES.md`](DEPLOY-TIMESERIES.md)；
- 前端仓 `ypbin-iot-ui`（与后端仓同级目录，或在 `UI_REPO=` 指定）；
  ⚠️ **IoT 页面在 `feat/iot-pages-slice1`（PR #15）之后才在 `main` 上**：若 `main` 还没有 `views/iot`，
  请用 `git clone -b <该分支>` 或 `UI_REPO=` 指向已含页面的工作副本，否则菜单能出来但页面会落到 fallback。

## 1. 一条命令

```bash
# 与后端仓同级 clone 前端仓（首次）
git clone https://github.com/wenbin-wb/ypbin-iot-ui.git

cd ypbin-iot/deploy
./ui-up.sh                     # 构建前端产物 → 暂存到 ../iot-ui-dist → 起 ypbin-iot-ui 容器
```

脚本做三件事（都可在 `.env` 里覆盖）：构建 `ypbin-iot-ui` 的 `@vben/web-antd` 产物、
拷到 `IOT_UI_DIST_DIR`（默认 `../iot-ui-dist`）、`docker compose up -d --no-deps ypbin-iot-ui`。

- 产物已就绪（例如 CI 产出）时：`SKIP_BUILD=1 ./ui-up.sh`
- 改端口/产物目录：`deploy/.env` 里 `IOT_UI_PORT=<端口>`、`IOT_UI_DIST_DIR=<相对 deploy 的产物目录>`
  （脚本会 `source .env`，与 `docker compose` 读到的是同一份值）

### 端口口径（三处必须一致，别再各写一套）

| 位置 | 口径 |
|---|---|
| `deploy/ui-up.sh`（`IOT_UI_PORT` 默认值） | **19001** |
| `deploy/docker-compose.yml`（`${IOT_UI_PORT:-…}` 默认值） | **19001** |
| `deploy/.env`（生产/安装时写入的值） | 本生产实例为 **19000**（见下） |

- **默认 19001 而不是 19000** 是刻意的：`deploy/docker-compose.yml` 同一个 compose 项目里
  `ypbin-admin-ui` 的默认端口就是 19000（`${ADMIN_UI_PORT:-19000}`），而 `install.sh` 会执行
  **全量** `up -d` —— 两个前端默认同端口会让其中一个以「端口被占用」启动失败。
  所以代码默认值取 19001，与管理台前端**并存**。
- **生产用 `.env` 覆盖为 19000**：本生产实例未部署 `ypbin-admin-ui`，IoT 前端即主入口，
  故 `deploy/.env` 里 `IOT_UI_PORT=19000`（`docker ps` 实测 `ypbin-iot-ui` 映射 `0.0.0.0:19000->80/tcp`）。
- ⚠️ **同一台机器将来要同时跑 `ypbin-admin-ui` 与 `ypbin-iot-ui` 时**，必须显式给其中一个改端口
  （本生产 `.env` 里 `ADMIN_UI_PORT` 与 `IOT_UI_PORT` 目前**都是 19000**，全量 `up -d` 会撞端口；
  由于本机没有 admin-ui 容器，现状不影响访问）。改 `IOT_UI_PORT` 后按上面「改端口」一项覆盖即可。

## 2. 打开

```
http://<服务器IP>:<IOT_UI_PORT>        # 本生产实例 = http://<服务器IP>:19000
```

用**平台管理员**登录（与管理台同一账号体系）。左侧应出现上述四个 IoT 菜单。

> ⚠️ 上面的地址是**明文 HTTP**，只适合内网/受控环境（登录口令与 `Authorization: Bearer`
> 会明文过网）。**生产环境必须加 TLS**，见 [§6 生产必须 TLS](#6-生产必须-tls本仓不内置证书)。

## 3. 为什么这样部署

- **前后端解耦**：前端是纯静态产物 + nginx（与本仓既有的 `ypbin-admin-ui` 服务同一模式）；
  后端换版本不必重建前端，前端换版本也不必动后端；
- **同源接口**：nginx 把 `/api/` 反代到 `ypbin-gateway:18080`（见 `deploy/nginx-microservice.conf`），
  前端用相对路径 `/api` ⇒ 不需要 CORS，也不需要在前端里写死后端地址；
- **菜单/权限只有一个事实源**：页面路径与权限码来自后端 `007-iot-data.sql`（`sys_menu.component` 与
  `auth_code`），前端按 `import.meta.glob('../views/**/*.vue')` 解析对应文件 ⇒ 加页面时不要在前端另立一套路由。

## 4. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 页面能开但接口 502/404 | 网关没起或路由不对：`docker compose ps` 看 `ypbin-gateway`；`docker logs ypbin-iot-ui` 看反代错误 |
| 登录后左侧**没有** IoT 菜单 | ① 数据库没执行 `007-iot-data.sql`；② 当前角色没有被授予（平台管理员角色 1 与租户模板在 SQL 里已授）；③ 菜单缓存——重新登录 |
| 打开是 404 或白屏 | `IOT_UI_DIST_DIR` 指错（需要的是 **dist 目录的内容**，`index.html` 必须在其根） |
| 维护窗口页报 404 | 后端未包含维护窗口管理端点（`ypbin-iot` 的 PR #25 之后才有 `/iot/maintenance/windows`） |
| 可用率显示成很长的小数/科学计数 | 后端 Long/BigDecimal 按字符串序列化；页面已统一 `Number()`，若自研页面请照做 |

## 5. 更新前端

```bash
cd ypbin-iot/deploy && ./ui-up.sh      # 重新构建 + 覆盖产物 + 重启容器（几秒）
```

> 说明：本仓的 CI（`ypbin-iot-ui` 的 `CI`）只做 lint/typecheck/build，不产出发行镜像；
> 部署形态保持「静态产物 + nginx」，与 `ypbin-admin-ui` 一致。

## 6. 生产必须 TLS（本仓不内置证书）

**结论：本仓只提供明文 80 的容器（`docker-compose.yml` 里 `ypbin-iot-ui` 映射
`0.0.0.0:${IOT_UI_PORT}->80`），不包含任何证书/443/TLS 配置。生产部署必须在前面加一层
TLS 终止，否则登录口令、`Authorization: Bearer`、设备凭据与 OpenAPI Secret（页面可复制）
会以明文在网络上传输，同网段可嗅探/劫持。**

### 6.1 方案 A（推荐）：前置反代 / 云负载均衡终止 TLS

用一台前置 nginx（或 caddy、云 LB）持有证书，对外 443，回源到本机 `http://127.0.0.1:19000`
（或 compose 映射端口）。示例（前置 nginx，仅示意，证书路径按实际环境替换）：

```nginx
server {
    listen 443 ssl;
    server_name iot.example.com;

    ssl_certificate     /etc/ssl/iot/fullchain.pem;   # 证书由运维申请/续期，不在本仓
    ssl_certificate_key /etc/ssl/iot/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # HSTS 只能在 **HTTPS 响应** 上加，且确认 HTTPS 稳定后再逐步加大 max-age。
    # 未完全 HTTPS 化之前不要加 includeSubDomains（会把子域一起锁死）。
    add_header Strict-Transport-Security "max-age=31536000" always;

    location / {
        proxy_pass http://127.0.0.1:19000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        # SSE（/api/iot/realtime/stream）不能被缓冲，否则实时事件「看起来不动」
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}

# 可选：HTTP 永久跳转（先确认外部访问入口只有这一个，避免管理台端口被绕过）
server {
    listen 80;
    server_name iot.example.com;
    return 301 https://$host$request_uri;
}
```

### 6.2 方案 B：容器内直接 443

需要自行给 `ypbin-iot-ui` 增加 `443` 端口映射与证书卷，并在
`deploy/nginx-microservice.conf` 的 `server` 块补 `listen 443 ssl;` +
`ssl_certificate`/`ssl_certificate_key`（该文件当前是 80 明文，刻意不写死证书路径，
以免把运维凭据/路径固化进仓库）。证书申请与续期（certbot/ACME、云证书服务）同样由运维负责。
**注意**：同一个 compose 文件里 `ypbin-admin-ui` 与 `ypbin-iot-ui` 共用这份 nginx 配置，
改之前确认两个前端都需要 443。

### 6.3 验证清单（部署后逐条执行）

```bash
# 1) 证书与协议（应看到 TLSv1.2/1.3 与有效证书链）
openssl s_client -connect iot.example.com:443 -servername iot.example.com </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -dates

# 2) HSTS + 安全响应头（应看到 strict-transport-security / x-content-type-options / x-frame-options / referrer-policy）
curl -sI https://iot.example.com/ | grep -iE 'strict-transport-security|x-content-type-options|x-frame-options|referrer-policy'

# 3) 明文入口不再直接提供应用（应 301/302 到 https，或连接被拒绝）
curl -sI http://iot.example.com/ | head -3

# 4) 静态资源压缩（返回 Content-Encoding: gzip）
JS=$(curl -s https://iot.example.com/ | grep -oE '/assets/[^"]+\.js' | head -1)
curl -sI -H 'Accept-Encoding: gzip' "https://iot.example.com${JS}" | grep -i 'content-encoding'

# 5) 浏览器打开控制台：不应有 Mixed Content / 证书告警；登录后 Network 里所有请求应为 https
```

### 6.4 暂时无法上 TLS 时的最低要求

- **不要把 19000（或任何明文端口）直接暴露到公网**：用安全组/防火墙只放行运维与可信网段；
- 上线前把本节作为**发布阻塞项**登记，而不是"以后再说"；
- 本仓不内置证书文件、私钥与 ACME 账户，也不在 compose 里写死域名——这些都属部署环境配置。

> 本次已在 `deploy/nginx-microservice.conf` 补了 `X-Content-Type-Options: nosniff`、
> `X-Frame-Options: DENY`、`Referrer-Policy` 与 `gzip`；但 **HSTS 必须加在 TLS 终止层**
> （80 明文的 server 上加 HSTS 不产生任何效果，还可能让人误以为已启用 HTTPS）。

