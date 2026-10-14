# 容器镜像制品化：GHCR 主路径 + docker save tar 兜底（issue #234）

> 结论先行：镜像**两种都产**——GHCR 推送为主、`docker save` tar.gz 为兜底；同一作业复用 jar 作业已构建的 jar，
> **不重复编译**；失败**显式判红**，不静默跳过。部署侧校验入口 `deploy/verify-release-images.sh`。
> 实施处：`.github/workflows/release-artifacts.yml` 的 `images` 作业。

## 0. 一句话选择

| 方案 | 取舍 | 本项目决定 |
|---|---|---|
| ③ 不做镜像，继续部署机 `docker compose build` | 就是问题本身（目标机无外网、镜像源 502/超时是常态） | **否决** |
| ① GHCR 推送 | 规范、digest 内容寻址、registry 按层去重不胀 Release；但需要外网 + 权限，私有包要凭据 | **主路径** |
| ② `docker save` tar.gz | 无外部依赖、离线可用；但体积大（含 base 层） | **兜底（必做）** |

**只做一种行不行**：若只允许一种，应选 ②，因为本仓部署机**无外网出口**（见 §1）。
但 GHCR 对多机/未来环境价值明确、成本又低，故二者并存，不是重复而是覆盖两种网络现实。

## 1. 背景与动机

- issue #234 此前已落地 jar + SBOM + sha256 清单；**镜像仍由部署机 `docker compose build`**（Dockerfile 只
  `COPY target/*.jar`、不编译源码，故安全）。
- 但部署机镜像源 502/超时是常态，且该机**无外网出口**：`registry-1.docker.io`、`repo1.maven.org`、
  `github.com` 三端点实测全部超时（一手证据：`deploy/PROD-OPS-NOTES.md` §15，2026-10-08）。
  ⇒ 「有 jar」并不等于「有镜像」。
- 目标：镜像也变成**可下载、可校验、可复现**的 Release 资产，且**不破坏**既有 jar/SBOM Release 流程。

## 2. 方案对比（决策依据）

| 维度 | ① GHCR 推送 | ② `docker save` tar.gz |
|---|---|---|
| 外部依赖 | 需 ghcr.io 可达 + 推送权限 | 无（就是文件） |
| Release 体积 | 不占（镜像在 registry，按层去重） | 整镜像含 base 层；实测单服务 **115 MB**（`eclipse-temurin:21-jre` 基线，gzip -9） |
| 部署机可用性 | 需外网；**本仓 dev 部署机不可用** | 下载后 `docker load`，**离线可用** |
| 可追溯性 | digest（内容寻址，最强 pin） | sha256(文件) + image id |
| 权限要求 | job `packages: write`；首次发布默认**私有** | 无 |
| 拉取凭据 | 公共包匿名；私有包需 `read:packages` 的 PAT/GITHUB_TOKEN | 无 |
| 失败面 | 权限 / 可见性 / 网络都可能被拒 | 仅构建 / save 本身 |
| 适合 | 有网环境、其他构建机、未来 IaC | 无网部署机、离线灾备、审计留档 |

## 3. 镜像契约

- **命名**：`ghcr.io/<owner>/ypbin-<service>`，`<service>` ∈ {gateway, auth, system, ai, access, iot}，
  与本地镜像 `ypbin/<service>:local` 一一对应。
- **Tags**（每次 release）：
  - `<version>`：pom 的 `project.version`（= Release tag 去掉 `v`），如 `1.0.0`；
  - `<release-tag>`：GitHub Release tag，如 `v1.0.0`；
  - `latest`：**仅为人查看方便**。部署一律用 `<version>` 或 `@sha256:<digest>`，
    **禁止 floating latest**（与本仓既有「镜像 pin 具体 tag」纪律一致）。
- **为什么是两段式命名**：GitHub 官方文档给的是 `ghcr.io/NAMESPACE/IMAGE_NAME` 两段式；多段斜杠
  （`ghcr.io/<owner>/<repo>/<service>`）未在本文核实到的官方文档中确认，故**不采用未核实写法**。
- **Release 资产**：
  - `images.digests.tsv`：TSV；注释头含 `# git` / `# built` / `# release-tag` / `# version` / `# registry`；
    每服务一行（8 列）：`service`, `local_image`, `ghcr_repo`, `tags`, `digest`, `image_id`, `tar_file`, `tar_sha256`；
  - `images.manifest.sha256`：`sha256sum -c` 兼容，覆盖 6 个 tar.gz；
  - `ypbin-iot-<service>-<version>.tar.gz` × 6。

## 4. 权限与可见性（拉取凭据写清）

**推送（Actions 内）**
- job 级 `permissions.packages: write`；用 `GITHUB_TOKEN` `docker login ghcr.io`。
- 官方文档：从工作流发布包会**自动链接到本仓库**；若同名包已存在但**未**链接该仓库，`GITHUB_TOKEN`
  会**无权重推**——需先在包设置里连接仓库，或删除该包后重推（失败指引已写进工作流）。

**可见性**
- **首次发布默认私有**。修改：进入该包页面 → `Package settings` → 改可见性/访问权限。
- 公共包：Container registry 支持**匿名拉取**（无需认证/登录）。
- 私有包：需要认证。

**拉取**
- 公共包：`docker pull ghcr.io/<owner>/ypbin-<service>:1.0.0`，无需登录。
- 私有包：`echo "$CR_PAT" | docker login ghcr.io -u <user> --password-stdin`，PAT（classic）至少
  `read:packages`；`GITHUB_TOKEN` 只在有仓库上下文的 Actions 里可直接用。

## 5. 部署侧用法

### 5.1 GHCR 主路径（能访问 ghcr.io 的机器）

```bash
TAG=v1.0.0
docker login ghcr.io -u <user>        # 私有包；公共包可跳过
for s in gateway auth system ai access iot; do
  docker pull "ghcr.io/wenbin-wb/ypbin-$s:$TAG"
  docker tag  "ghcr.io/wenbin-wb/ypbin-$s:$TAG" "ypbin/$s:local"
done
```

校验 digest（推荐，比 tag 更强）：

```bash
deploy/verify-release-images.sh --tag "$TAG" --pull     # 逐服务 pull repo@digest 并断言 RepoDigests
```

### 5.2 tar 兜底（无外网部署机）

```bash
# 在能访问 GitHub 的机器上下载（部署机若不可达 GitHub，用 scp 把目录传过去）
deploy/verify-release-images.sh --tag v1.0.0 --dir /tmp/release-v1.0.0 \
  --load --jars-manifest /tmp/release-v1.0.0/jars.manifest.sha256

# 校验通过后，把 compose 认的本地 tag 指过去（脚本 --load 已 load 出 ypbin/<svc>:<ver>）
for s in gateway auth system ai access iot; do
  docker tag "ypbin/$s:1.0.0" "ypbin/$s:local"
done
```

随后与既有流程一致：**先备份旧镜像 tag**（`docker tag ypbin/<svc>:local ypbin/<svc>:rollback-<TS>`），
再 `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml up -d --no-deps <svc>`。

> 为什么 `docker tag ... ypbin/<svc>:local` 就够了：compose 里这些服务同时声明了 `build:` 与
> `image: ypbin/<svc>:local`；**本地已存在该 tag 的镜像时 compose 不会再 build**（除非传 `--build`）。
> 这既让镜像制品**无需改 compose**即可生效，也不破坏既有 rollback tag 机制。

## 6. 验证与边界（诚实清单）

**本机真跑**（2026-10-14，2 核，Docker 29.6.0；证据见交付汇报）：
- 工作流 `yaml.safe_load` 通过；**每个 step 的 `run` 脚本**逐个过 `bash -n`；`deploy/verify-release-images.sh` 过 `bash -n`。
- 用**真实** `ypbin-gateway/Dockerfile` + **真实** base `eclipse-temurin:21-jre` 构建：镜像内
  `/app/app.jar` 的 sha256 == 源 jar 的 sha256。
- `docker save | gzip -9` → tar.gz（115 MB）+ sha256；`docker rmi` 后 `docker load`，**image id 不变**。
- 把工作流镜像作业的 shell **原样抽出**，以 `GHCR_REGISTRY=localhost:5000` 对**本地 registry** 跑通
  6 服务 build → tar → push → 读回 digest → 生成清单；再用 `deploy/verify-release-images.sh --load / --pull` 通过。
- 负例：篡改 tar / 篡改 digest 后校验脚本**必须失败**。

**未在 GitHub Actions 真跑 / 未核实**：
- 未对**真实 GHCR** 推送或拉取（本机无可用凭据）；`packages: write`、首次发布默认私有、GITHUB_TOKEN
  自动链接仓库等**均取自官方文档**，非本机实测。
- `actions/download-artifact@v7` 与 `actions/upload-artifact@v7` 的**跨作业路径还原**按官方语义编写，
  **未在 Actions 真跑**。
- `ubuntu-latest` 的 docker/buildx 版本差异对 `imagetools inspect` 取值的影响未核实。
- 本地测试的 jar 是 4 KB 占位文件，**不是真实 Spring Boot 可执行 jar**（真实 jar 由上游作业自检保证
  `BOOT-INF/`，本文件验证的是镜像流水线而非 jar 本身）。

## 7. 出处

- GitHub Docs《Working with the container registry》(zh)，访问 2026-10-14：
  https://docs.github.com/zh/packages/working-with-a-github-packages-registry/working-with-the-container-registry
  —— 命名 `ghcr.io/NAMESPACE/IMAGE_NAME`、首次发布默认私有、`GITHUB_TOKEN` 自动链接仓库、PAT 范围。
- GitHub Docs《配置包的访问控制和可见性》(zh)，访问 2026-10-14：
  https://docs.github.com/zh/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility
  —— 公共包匿名拉取、私有包需认证、可见性设置。
- Docker Docs《docker image save》，访问 2026-10-14：https://docs.docker.com/reference/cli/docker/image/save/
- 本仓一手证据：`.github/workflows/release-artifacts.yml`、`deploy/verify-release-images.sh`、
  `deploy/PROD-OPS-NOTES.md` §15、`docs/DEPLOY-BACKEND.md` §2.0。
