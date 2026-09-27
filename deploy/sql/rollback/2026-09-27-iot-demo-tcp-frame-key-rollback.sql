-- =============================================================
-- 回滚：演示设备 9300012 的 temperature 点位地址回到 Modbus 形态
--
-- 回滚后行为：解码层在帧里找不到 `holding:0` 这个键 ⇒ 丢弃该读数 + 计数
-- （iot.access.decode.failure{reason="key-not-found"}）+ WARN；
-- 库里不再新增该设备的行（**不会**退回 `[B@<hash>` 垃圾形态——那是本次改动要消灭的）。
-- 若还要恢复「有值但无语义」的旧行为，需同时回滚 access 镜像到
-- /opt/ypbin/ypbin-access-jar-backup-<TS>.jar（见部署回报）。
-- =============================================================

UPDATE iot_point_mapping
SET raw_address = 'holding:0'
WHERE id = 9500011
  AND device_id = 9300012
  AND raw_address = 'TEMP';
