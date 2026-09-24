# IoT 前端部署（打开即见页面）

> 目标：**一条命令**把 IoT 前端拉起来，登录后左侧出现「设备台账 / 产品与物模型 / 设备分组 / 维护窗口（计划停机）」，
> 并且**可用率/断档**能直接看到（逐台设备的抽屉里）。

## 0. 前置

- 后端已按 [`docs/IOT-ROADMAP.md`](IOT-ROADMAP.md) / `deploy/install.sh` 起好：`ypbin-gateway`（18080）、
  `ypbin-auth`、`ypbin-system`、`ypbin-iot`（18084）、`ypbin-access`（18086）与 MySQL/Nacos/Redis；
- 数据库里已执行 `deploy/sql/006-iot-schema.sql` + `007-iot-data.sql`（或走 `install.sh`）；
  菜单与权限码由 `007-iot-data.sql` 写入，并已授给平台管理员角色与租户模板；
- 前端仓 `ypbin-iot-ui`（与后端仓同级目录，或在 `UI_REPO=` 指定）。

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
- 改端口：`.env` 里 `IOT_UI_PORT=19001`（与 `ypbin-admin-ui` 的 19000 并存，互不影响）

## 2. 打开

```
http://<服务器IP>:19001
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
