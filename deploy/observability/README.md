# deploy/observability —— 最小可观测栈（issue #222）

Prometheus（含告警规则）+ Grafana（provisioning 数据源/大盘）+ 可选 Alertmanager，
以及一个把 Spring Boot Actuator `/actuator/metrics` 桥接成 Prometheus 文本的
**只读**桥接器 `actuator-exporter`。

- 规则清单、指标核实证据、长期做法：**`docs/OPS-MONITORING.md`**（唯一权威清单）。
- 当前环境为什么需要桥接器：见 `actuator-exporter/actuator-exporter.py` 头部注释
  （jar 内无 micrometer-registry-prometheus；禁止改业务源码/重启 dev 容器）。

## 快速开始（目标机执行）

```bash
# 1) 桥接器（systemd；只读本地 Actuator）
cd deploy/observability/actuator-exporter && bash install.sh

# 2) Prometheus + Grafana
cd ..                       # deploy/observability
cp .env.example .env && vi .env      # 填 GRAFANA_ADMIN_PASSWORD
docker compose up -d

# 3) 可选 Alertmanager（noop receiver）
docker compose --profile alerting up -d
```

## 验收（可复制）

```bash
curl -s http://127.0.0.1:9110/metrics | head                       # 桥接指标
curl -s http://127.0.0.1:9090/-/ready && echo OK                    # Prometheus 就绪
curl -s http://127.0.0.1:9090/api/v1/rules | head -c 400            # 规则已装载
curl -s 'http://127.0.0.1:9090/api/v1/targets?state=active' | head -c 400
# 规则单测（不需要集群，纯离线）：
docker run --rm -v "$PWD/prometheus/rules:/r:ro" --entrypoint /bin/promtool \
  prom/prometheus:v2.55.1 test rules /r/ypbin-iot.rules.test.yml
```

## 回滚

```bash
cd deploy/observability
docker compose --profile alerting down        # 停容器（保留数据卷）
docker compose --profile alerting down -v     # 连数据卷一起删
systemctl disable --now ypbin-actuator-exporter.service
rm -f /etc/systemd/system/ypbin-actuator-exporter.service && systemctl daemon-reload
```
