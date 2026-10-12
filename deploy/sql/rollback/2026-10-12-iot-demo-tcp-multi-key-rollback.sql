-- =============================================================
-- 回滚：演示设备 9300012 其余 3 个点位的协议地址回到 Modbus 形态
--
-- 回滚后行为：解码层在帧里找不到 `holding:1/100/200` ⇒ 丢弃 + key-not-found 计数 + WARN
-- （即回到 2026-10-12 修正前的刷屏状态）。仅回滚本 SQL 即可，无需回滚 jar
-- （#212 的逐点位订阅必须保留，否则更早的"整机零采集"会复发）。
-- =============================================================

UPDATE iot_point_mapping SET raw_address = 'holding:1'
WHERE id = 9500012 AND device_id = 9300012 AND raw_address = 'HUMIDITY';

UPDATE iot_point_mapping SET raw_address = 'holding:100'
WHERE id = 9500013 AND device_id = 9300012 AND raw_address = 'SERIALNO';

UPDATE iot_point_mapping SET raw_address = 'holding:200'
WHERE id = 9500014 AND device_id = 9300012 AND raw_address = 'BOUNDARY';
