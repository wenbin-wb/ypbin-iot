# IoT 前端部署（打开即见页面）

> 目标：**一条命令**把 IoT 前端拉起来，登录后左侧出现「设备台账 / 产品与物模型 / 设备分组 / 维护窗口（计划停机）」，
> 并且**可用率/断档**能直接看到（逐台设备的抽屉里）。

## 0. 前置

- 后端已按 [`docs/IOT-ROADMAP.md`](IOT-ROADMAP.md) / `deploy/install.sh` 起好：`ypbin-gateway`（18080）、
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
