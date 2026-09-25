# IoT 后端部署纪律（构建机 / 部署机分工）

> 为什么单独一份：`docs/microservice-deployment.md` 是**继承自 admin 基座的既有文件**
> （不在 `SYNC.md` 第二节的 8 文件白名单内 ⇒ 改它会让 `Sync Whitelist` 门禁转红）。
> 本文件是**本仓新增**的文档，用来承载 IoT 自己的部署纪律与教训。

## 1. 铁律：不要在「无法访问 GitHub 的部署机」上重建 jar

**现象（2026-09-25 实测）**：生产部署机（`113.142.217.58`）**连不上 GitHub** ——
`git fetch` 会挂到超时（独立复核复测 `git ls-remote` ⇒ `exit=124`），
而该机仓库里的源码树**停留在较早的提交**；与此同时 `ypbin-service/ypbin-iot/target/*.jar`
是**从外部上传的新产物**（由构建机构建）。

**后果**：此时若在该机执行 `mvn package` / `mvn clean package`，
会从**陈旧源码**编译出一个**缺少新端点**的 jar（真实风险示例：丢掉
`GET /iot/devices/{deviceId}/latest`，或本轮之前的「物模型读路径放开」），
紧接着 `docker compose build ypbin-iot` 会把旧 jar 打进镜像并 `up -d` ⇒
**线上能力静默回退**，而日志里看不出任何异常。

> 换句话说：`docker compose build` 本身是安全的（Dockerfile **只 `COPY target/*.jar`，不编译源码**），
> **危险的是在部署机上跑 Maven**。两者不要混为一谈：
> 允许 `compose build`，禁止 `mvn`。

## 2. 正确流程（本轮实际执行）

```bash
# ① 构建机（能访问 GitHub / 有完整源码 + 依赖缓存）：构建 fat jar
cd <repo>
MAVEN_OPTS=-Xmx768m mvn -s <settings.xml> -Dmaven.repo.local=<m2repo> \
  -pl ypbin-service/ypbin-iot -am -DskipTests -Djacoco.skip=true package
md5sum ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar   # 记下 md5，部署后核对

# ② 上传（只传 jar，不传源码、不在服务器编译）
scp -i <key> -P 22 ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar root@<server>:/tmp/new.jar

# ③ 部署机：备份 → 替换 jar → 打镜像 → 只重启这一个服务
cd /opt/ypbin/ypbin-iot
TS=$(date +%Y%m%d-%H%M%S)
docker tag ypbin/ypbin-iot:local ypbin/ypbin-iot:rollback-$TS               # 旧镜像留 tag（回滚用）
cp -a ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar /root/ypbin-iot-jar-rollback-$TS.jar
install -m 644 /tmp/new.jar ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml build ypbin-iot
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml up -d --no-deps ypbin-iot
rm -f /tmp/new.jar

# ④ 核对：容器内的 jar 必须等于构建机上那个 md5，再验 health 与关键端点
docker exec ypbin-iot md5sum /app/app.jar
curl -s http://127.0.0.1:18084/actuator/health
```

- `--no-deps` + 只点名 `ypbin-iot`：**不动别人的容器**；`down -v` 一律禁止（会连数据卷一起删）。
- 前端产物同理：服务器**没有 node/pnpm**，`iot-ui-dist` 必须用 CI 真构建的产物覆盖
  （下载 artifact → 覆盖 `iot-ui-dist` → `docker restart ypbin-iot-ui`），并核对
  「CI 产物与服务器文件逐字节一致」的 md5。

## 3. 回滚

```bash
# 后端：把 rollback tag 打回 local 再重建容器
docker tag ypbin/ypbin-iot:rollback-<TS> ypbin/ypbin-iot:local
cd /opt/ypbin/ypbin-iot && docker compose -f deploy/docker-compose.yml \
  -f deploy/docker-compose.override.yml up -d --no-deps ypbin-iot
# 或直接用备份的 jar 覆盖 target/ 后重打镜像（jar 备份路径见第 2 节）

# 前端：换回备份目录再重启
cd /opt/ypbin/ypbin-iot && rm -rf iot-ui-dist && cp -a iot-ui-dist.bak-<TS> iot-ui-dist
docker restart ypbin-iot-ui
```

> **前提**：每次部署前必须留下「旧镜像 tag + 旧 jar + 旧 dist 目录」三件套；
> 少了任何一件，回滚就只剩「在部署机上重建」这条**被第 1 节禁止**的路。

## 4. 为什么部署机源码树与运行产物会不一致（如实说明）

部署机不是源码事实源：它只有部署所需的最小检出，且**无法从 GitHub 更新**。
因此「服务器上的源码」**不代表**「正在运行的产物」——
判断线上到底跑的是哪一版，**只认容器内的 jar md5**（或前端 dist 的文件 md5），
不要用 `git log` 去推断。要追溯「哪个提交对应这个 jar」，看构建机上的构建记录与 `docs/` 里的部署回执。
