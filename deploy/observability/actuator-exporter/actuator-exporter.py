#!/usr/bin/env python3
# =============================================================================
# actuator-exporter —— 把 Spring Boot Actuator /actuator/metrics 桥接成
#                      Prometheus 文本格式（issue #222）
# =============================================================================
# 为什么需要它（不是"多此一举"，是当前环境的事实约束）：
#   · ypbin-iot / ypbin-access 的**已构建 jar 里没有 micrometer-registry-prometheus**
#     （2026-10-14 在 dev 实测：docker exec <ctr> jar tf /app/app.jar | grep -i
#      'micrometer-registry|prometheus' 无任何命中）；
#   · 因而不存在 /actuator/prometheus 端点（实测返回应用层 404 JSON，
#     而 /actuator/health、/actuator/metrics 正常）；
#   · 补依赖 + 暴露端点属**业务源码改动**（本任务明确禁止改业务源码），且验证需重建
#     容器（任务明确禁止动 dev 的 ypbin-iot / ypbin-access 容器）。
#   ⇒ 在业务侧修复落地前，用本桥接器把**已存在**的 Actuator 指标以 Prometheus
#     格式暴露出来，使 4+ 条关键告警规则**今天就能真实评估**。
#   · 只读：仅对 /actuator/health/* 与 /actuator/metrics/<name> 发 GET，不缓存跨抓取状态；
#   · 无第三方依赖（只用 Python 3 标准库），便于在目标机以 systemd 直接运行。
#
# 指标命名（稳定契约，告警规则与本文件必须同时改）：
#   ypbin_service_up{service}                         1/0  HTTP 可达性
#   ypbin_service_health{service}                     1/0  liveness==UP
#   ypbin_actuator_counter_total{service,metric}      Counter（Actuator COUNT）
#   ypbin_actuator_gauge{service,metric}              Gauge（Actuator VALUE）
#   ypbin_actuator_timer_count{service,metric}        Counter（Actuator COUNT）
#   ypbin_actuator_timer_sum_seconds{service,metric}  Counter（Actuator TOTAL_TIME）
#   ypbin_actuator_timer_max_seconds{service,metric}  Gauge（Actuator MAX）
#   ypbin_actuator_metric_ok{service,metric}          1/0  单个 metric 抓取成功
#   ypbin_actuator_scrape_duration_seconds{service}   本服务本轮抓取耗时
# =============================================================================
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_CONFIG = "/etc/actuator-exporter/config.json"
EXPORTER_VERSION = "1.0.0"
METRIC_NAME_RE = re.compile(r"[^a-zA-Z0-9_:]")


def _escape_label(value):
    return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _sample(name, labels, value):
    if labels:
        pairs = ",".join('%s="%s"' % (k, _escape_label(v)) for k, v in labels.items())
        return "%s{%s} %s" % (name, pairs, value)
    return "%s %s" % (name, value)


def _get_json(url, timeout):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _classify(measurements):
    stats = {m.get("statistic") for m in measurements}
    if "TOTAL_TIME" in stats or "MAX" in stats:
        return "timer"
    if "COUNT" in stats and "VALUE" not in stats:
        return "counter"
    if "VALUE" in stats:
        return "gauge"
    return "unknown"


def _measure(measurements, statistic):
    for m in measurements:
        if m.get("statistic") == statistic:
            return m.get("value")
    return None


def render(config):
    timeout = float(config.get("timeout_seconds", 4))
    lines = []
    lines.append("# HELP ypbin_actuator_exporter_build_info Bridge build info.")
    lines.append("# TYPE ypbin_actuator_exporter_build_info gauge")
    lines.append(_sample("ypbin_actuator_exporter_build_info", {"version": EXPORTER_VERSION}, 1))
    declared_types = {
        "counter": "ypbin_actuator_counter_total",
        "gauge": "ypbin_actuator_gauge",
        "timer_count": "ypbin_actuator_timer_count",
        "timer_sum": "ypbin_actuator_timer_sum_seconds",
        "timer_max": "ypbin_actuator_timer_max_seconds",
        "metric_ok": "ypbin_actuator_metric_ok",
        "scrape_duration": "ypbin_actuator_scrape_duration_seconds",
    }
    for out_name in declared_types.values():
        lines.append("# TYPE %s %s" % (out_name, "counter" if out_name.endswith("_total") else "gauge"))
    lines.append("# TYPE ypbin_service_up gauge")
    lines.append("# TYPE ypbin_service_health gauge")

    for svc in config.get("services", []):
        name = svc["name"]
        base = svc["base_url"].rstrip("/")
        started = time.time()
        up = 0
        health = 0
        try:
            data = _get_json(base + svc.get("health_path", "/actuator/health/liveness"), timeout)
            up = 1
            if str(data.get("status", "")).upper() == "UP":
                health = 1
        except Exception:
            up = 0
            health = 0
        lines.append(_sample("ypbin_service_up", {"service": name}, up))
        lines.append(_sample("ypbin_service_health", {"service": name}, health))

        for metric in svc.get("metrics", []):
            labels = {"service": name, "metric": metric}
            try:
                data = _get_json("%s/actuator/metrics/%s" % (base, metric), timeout)
                measurements = data.get("measurements", [])
                kind = svc.get("metric_types", {}).get(metric) or _classify(measurements)
                if kind == "counter":
                    lines.append(_sample("ypbin_actuator_counter_total", labels, _measure(measurements, "COUNT")))
                elif kind == "gauge":
                    lines.append(_sample("ypbin_actuator_gauge", labels, _measure(measurements, "VALUE")))
                elif kind == "timer":
                    lines.append(_sample("ypbin_actuator_timer_count", labels, _measure(measurements, "COUNT")))
                    lines.append(_sample("ypbin_actuator_timer_sum_seconds", labels, _measure(measurements, "TOTAL_TIME")))
                    lines.append(_sample("ypbin_actuator_timer_max_seconds", labels, _measure(measurements, "MAX")))
                else:
                    raise ValueError("无法判定的 measurement 形态: %s" % measurements)
                lines.append(_sample("ypbin_actuator_metric_ok", labels, 1))
            except Exception:
                # 单个指标抓不到要让上层看得见（0），不能静默吞掉
                lines.append(_sample("ypbin_actuator_metric_ok", labels, 0))
        lines.append(_sample("ypbin_actuator_scrape_duration_seconds", {"service": name},
                             "%.4f" % (time.time() - started)))
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    config = None

    def do_GET(self):
        if self.path.startswith("/metrics"):
            try:
                body = render(self.config).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except Exception as exc:  # noqa: BLE001 - 必须把失败暴露给抓取方
                msg = ("actuator-exporter render failed: %s\n" % exc).encode("utf-8")
                self.send_response(500)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(msg)))
                self.end_headers()
                self.wfile.write(msg)
        elif self.path.startswith("/-/healthy"):
            body = b"ok\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.log_date_time_string(), fmt % args))


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ACTUATOR_EXPORTER_CONFIG", DEFAULT_CONFIG)
    with open(config_path, "r", encoding="utf-8") as fh:
        config = json.load(fh)
    Handler.config = config
    listen = config.get("listen", "127.0.0.1:9110")
    host, _, port = listen.rpartition(":")
    server = ThreadingHTTPServer((host or "127.0.0.1", int(port)), Handler)
    sys.stderr.write("actuator-exporter listening on %s (config=%s)\n" % (listen, config_path))
    server.serve_forever()


if __name__ == "__main__":
    main()
