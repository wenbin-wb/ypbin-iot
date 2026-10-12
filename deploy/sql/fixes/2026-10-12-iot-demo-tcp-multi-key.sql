-- =============================================================
-- 演示设备 9300012（demo-dev-curve）其余 3 个点位的**协议地址修正**（承接 2026-09-27 的 temperature 修正）
--
-- 背景：
--   · 2026-09-27 只修正了 temperature（映射 id 9500011，raw_address 'holding:0' → 'TEMP'）。
--     当时的理由是「TCP 适配器只投递订阅地址列表第 0 个点位，其余点位本就不会收到数据」——
--     该前提**已被 #212 推翻**：starter 0.2.0 把 TCP 定义为单点位流并对多地址 fail-fast，
--     本仓 access 改为**逐点位单地址订阅**后，**4 个点位都能收到同一帧**。
--   · 因此其余 3 个点位的 `holding:N` 形态地址不再"反正收不到"，而是**会收到帧却取不到键**
--     ⇒ 持续 `iot.access.decode.failure{reason="key-not-found"}` + WARN 刷屏。
--   · 喂数源 `docs/tools/access-tcp-simulator.py` 已同步扩展为 4 键帧
--     （`TEMP=…,HUMIDITY=…,SERIALNO=…,BOUNDARY=…,SEQ=…`），键名与本脚本的 raw_address 对应。
--
-- 改动对象（均限 device_id=9300012 + 原值匹配，幂等）：
--   · id 9500012 humidity     holding:1   → HUMIDITY
--   · id 9500013 serialNo     holding:100 → SERIALNO
--   · id 9500014 demoBoundary holding:200 → BOUNDARY
--
-- 回滚：deploy/sql/rollback/2026-10-12-iot-demo-tcp-multi-key-rollback.sql
--
-- ⚠️ 与 2026-09-27 同一处置：放 `fixes/` 而非 `migration/`（后者受 SQL 等价门禁约束；
--   本文件是运行时演示数据的订正，不在仓库种子脚本里）。
-- =============================================================

UPDATE iot_point_mapping SET raw_address = 'HUMIDITY'
WHERE id = 9500012 AND device_id = 9300012 AND raw_address = 'holding:1';

UPDATE iot_point_mapping SET raw_address = 'SERIALNO'
WHERE id = 9500013 AND device_id = 9300012 AND raw_address = 'holding:100';

UPDATE iot_point_mapping SET raw_address = 'BOUNDARY'
WHERE id = 9500014 AND device_id = 9300012 AND raw_address = 'holding:200';
