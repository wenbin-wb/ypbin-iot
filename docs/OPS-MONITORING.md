# OPS-MONITORING —— ypbin IoT 最小 Prometheus + Grafana 大盘（issue #222）

> **结论先行**：已在 **dev 机（ypbin-prod / 113.142.217.42）** 落地 Prometheus + Grafana +
> Alertmanager + 一个只读桥接器 `actuator-exporter`，**8 条关键告警规则**全部装载并通过
> `promtool test rules`；Prometheus 实际抓到 47 条 `ypbin_*` 序列，Grafana 数据源与大盘已
> provisioning。**规则里的每个指标名都在 dev 只读核实存在**（§4）。
> ⚠️ 一个必须知道的事实：当前 jar **没有** `micrometer-registry-prometheus`，`/actuator/prometheus`
> 不存在（实测应用层 404）⇒ 用桥接器把 `/actuator/metrics` 转成 Prometheus 文本是**当前唯一
> 不改业务源码 / 不重启那两个容器**的办法；业务侧补齐依赖是**长期做法**（§7）。

## 1. 交付物与部署位置

| 交付物 | 仓内路径 | dev 落地路径 |
|---|---|---|
| Prometheus + Grafana + Alertmanager 编排 | `deploy/observability/docker-compose.yml` | `/opt/observability/` |
| Prometheus 抓取配置 | `deploy/observability/prometheus/prometheus.yml` | 同上 |
| 告警规则（8 条） | `deploy/observability/prometheus/rules/ypbin-iot.rules.yml` | 同上 |
| 规则单测 | `deploy/observability/prometheus/rules/ypbin-iot.rules.test.yml` | 同上 |
| Grafana provisioning | `deploy/observability/grafana/provisioning/**` | 同上 |
| Grafana 大盘 JSON | `deploy/observability/grafana/dashboards/ypbin-iot-overview.json` | 同上 |
| 只读桥接器 + systemd | `deploy/observability/actuator-exporter/**` | `/opt/observability/actuator-exporter/` |

监听地址（**全部只绑 127.0.0.1，公网打不到**）：Prometheus `:9090`、Grafana `:3000`、
Alertmanager `:9093`、桥接器 `:9110`。

## 2. 为什么是桥接器（当前环境约束，已实测）

- `docker exec ypbin-iot sh -c "jar tf /app/app.jar | grep -i 'micrometer-registry|prometheus'"`
  → **无任何命中**（2026-10-14 在 dev 实测）⇒ 没有 `/actuator/prometheus` 端点；
- `curl http://127.0.0.1:18084/actuator/prometheus` → **应用层 404 JSON**（`{"code":404,...}`），
  而 `/actuator/health`、`/actuator/metrics` 正常；
- 补齐依赖属**业务源码改动**（本任务禁止），且生效需重建 `ypbin-iot`/`ypbin-access`
  （本任务明确禁止动这两个容器）。
⇒ `actuator-exporter` 只读地轮询 `/actuator/health/liveness` 与 `/actuator/metrics/<name>`，
输出 Prometheus 文本；**不改任何业务代码、不重启业务容器**。

桥接后的指标命名（稳定契约，见脚本头注释）：
`ypbin_service_up` / `ypbin_service_health`、`ypbin_actuator_counter_total`、
`ypbin_actuator_gauge`、`ypbin_actuator_timer_{count,sum_seconds,max_seconds}`
（均带 `service`、`metric` 标签）、`ypbin_actuator_metric_ok`。

## 3. 告警规则清单（8 条，编号与任务要求对应）

| # | 规则名 | 表达式（核心） | 依据指标 | 任务要求 |
|---|---|---|---|---|
| ① | `YpbinServiceDown` | `ypbin_service_health < 1` | liveness 探针 | 服务健康 DOWN |
| ② | `YpbinAccessSubscribeFailure` | `increase(...{metric="iot.access.subscribe.failure"}[5m]) > 0` | `iot.access.subscribe.failure` | 接入订阅失败 |
| ③a | `YpbinIotDbWriteFailed` | `increase(...{metric="iot.timeseries.write.failed"}[5m]) > 0` | `iot.timeseries.write.failed` | IoTDB 写入失败 |
| ③b | `YpbinIotDbWriteStalled` | `increase(...write.rows[10m]) == 0 and on(service) increase(...points.collected[10m]) > 0` | `iot.timeseries.write.rows` / `.points.collected` | IoTDB 写入落后 |
| ③c | `YpbinTimeseriesDbRowsStalled` | `delta(...db.rows[30m]) <= 0 and on(service) increase(...write.rows[30m]) > 0` | `iot.timeseries.db.rows` | IoTDB 落库落后（审计建议 `db.rows`） |
| ④ | `YpbinAlertEvaluatorStalled` | `ypbin_actuator_gauge{metric="iot.alert.evaluate.lag"} > 300000` | `iot.alert.evaluate.lag`（毫秒） | 告警评估停滞（`PLATFORM_EVALUATOR_STALLED` 同义） |
| ④补 | `YpbinPlatformAlertAccessReportLag` | `...{metric="iot.platform_alert.access.report.lag"} > 45000` | `iot.platform_alert.access.report.lag` | 平台自告警 `PLATFORM_INSTANCE_UNREACHABLE` 口径 |
| ⑤ | `YpbinAccessLeaseTickSlow` | `ypbin_actuator_timer_max_seconds{metric="iot.access.lease.tick.duration"} > 20` | `iot.access.lease.tick.duration` | 审计建议 `lease/tick` |

> **规则③c 的窗口为什么是 30m（不是 10m）**：`iot.timeseries.db.rows` 由**低频对账探针**
> 刷新，默认每 **10 分钟**一次（`ypbin.timeseries.db-rows-probe-interval-ms=600000`，
> `IotDbRowCountProbe#111`）。2026-10-14 上线初版用 10m 窗口，dev 实测该规则**误入 pending**
> （窗口内恰好没有 / 只有一次探针更新）⇒ 窗口拉到 30m（≈3 个探针周期）并要求同期 `write.rows`
> 确实在增长后，才恢复 `inactive`。这是「阈值 / 窗口必须对齐数据产生频率」的一个实例。

## 4. 指标存在性核实证据（dev 只读，2026-10-13/14）

```bash
# 指标清单（144 条 iot / 92 条 access）
curl -s http://127.0.0.1:18084/actuator/metrics   # -> names[...] 144 条
curl -s http://127.0.0.1:18086/actuator/metrics   # -> names[...] 92 条
# 关键指标的 measurements 形态（决定桥接成 Counter/Gauge/Timer）
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.write.failed
#   -> measurements:[{COUNT,0}]                        => counter
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.db.rows
#   -> measurements:[{VALUE,750607}] description「低频 SELECT COUNT(*) 真值对账」 => gauge
curl -s http://127.0.0.1:18084/actuator/metrics/iot.alert.evaluate.lag
#   -> measurements:[{VALUE,1037}]  源码 AlertMetrics#54 注释「（毫秒）」      => gauge(ms)
curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.lease.tick.duration
#   -> measurements:[{COUNT..},{TOTAL_TIME..},{MAX..}]                        => timer
```

**未在 dev 上核实**：`iot.alert.evaluate.lag` 的阈值（300000ms=5min）是取评估周期（15s）×20 的
经验值，不是代码里的常量；上线后应按实际评估周期复核。

## 5. dev 验收（可复制，均已实跑）

```bash
# 桥接器
curl -s http://127.0.0.1:9110/metrics | grep -E "ypbin_service_(up|health)"
#   -> ypbin_service_up{service="ypbin-iot"} 1 ; ..._health{...} 1 （access 同为 1）
# Prometheus 就绪 + 目标
curl -s http://127.0.0.1:9090/-/ready                     # -> Prometheus Server is Ready.
curl -s "http://127.0.0.1:9090/api/v1/targets?state=active"   # job=ypbin-actuator health=up
# 规则已装载（8 条）
curl -s http://127.0.0.1:9090/api/v1/rules | grep -o '"name":"Ypbin[^"]*"'
# 规则单测（离线，不用集群）
docker run --rm -v /opt/observability/prometheus/rules:/r:ro --entrypoint /bin/promtool \
  quay.io/prometheus/prometheus:v2.55.1 test rules /r/ypbin-iot.rules.test.yml   # -> SUCCESS
# Grafana 健康 + 已 provisioning
curl -s http://127.0.0.1:3000/api/health                  # -> database ok, version 11.3.0
#   datasources -> Prometheus(prometheus) ; search?type=dash-db -> ypbin IoT 平台健康总览
```

实测抓取到的 `ypbin_*` 序列数：**47**（`count({__name__=~"ypbin_.*"})`）。

## 6. 部署 / 回滚

部署见 `deploy/observability/README.md`。**回滚（dev）**：
```bash
cd /opt/observability && docker compose --profile alerting down -v
systemctl disable --now ypbin-actuator-exporter.service
rm -f /etc/systemd/system/ypbin-actuator-exporter.service && systemctl daemon-reload
rm -rf /opt/observability            # 目录此前不存在，删除即回到改动前
```
**dev 改动前状态**：`/opt/observability` 不存在、无 prometheus/grafana 容器、9090/9093/3000/9110
端口空闲（已记录）。**未触碰** `ypbin-iot`/`ypbin-access` 及其 jar。

## 7. 长期做法（建议尽快，属业务侧改动，本次未做）

1. 给 `ypbin-service/ypbin-iot`、`ypbin-service/ypbin-access` 加 `io.micrometer:micrometer-registry-prometheus`
   （本仓 pom 属业务源码，本次禁改；建议评审/反哺后加）；
2. 在 `deploy/nacos/ypbin-iot.yaml`/`ypbin-access.yaml` 的 `management.endpoints.web.exposure.include`
   里加 `prometheus`（**仍不要用 `*`**，理由见这两份文件既有的安全边界注释）；
3. 重建这两个容器后，把 `prometheus.yml` 的 `ypbin-actuator` job 换成直抓
   `127.0.0.1:18084/actuator/prometheus`、`127.0.0.1:18086/actuator/prometheus`，
   **告警规则改回用原生指标名**（`iot.timeseries.write.failed` 等），桥接器退役。

## 8. 未核实 / 边界

1. 规则目前**没有接真实通知渠道**（Alertmanager receiver 是 noop）：Webhook 是项目明确不做的
   SSRF 面，邮件需用户决策。规则会 FIRING（Prometheus/Grafana 可见），但不会发信。
2. 桥接器按 Prometheus 15s 抓取**逐指标串行 HTTP** 请求 Actuator，未做并发/缓存；指标数量增长
   后需评估对服务的影响（当前 ~18 个指标）。
3. **未做**：日志聚合（Loki/ELK）、Alertmanager 高可用、Grafana 告警（统一用 Prometheus 规则）。
4. 本栈是 **dev 单机**最小实现，未做 HA / 持久化异地；`prometheus-data`/`grafana-data` 为本地卷。
