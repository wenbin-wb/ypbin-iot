-- ypbin-iot Modbus 寄存器解码结构（增量迁移；与 007-iot-data.sql 追加部分的 DDL 段语句等价）
-- 内容：iot_point_mapping 新增 word_count（1|2）与 `signed`（0/1），默认 1/0 = 今天的行为。
-- 为什么单独成文件：真库 IT 的建表助手 ItSchema 只加载 006 + 显式清单里的 *-schema.sql；
--   006 的 CREATE TABLE 不含这两列，不登记本文件 ⇒ 按实体读 iot_point_mapping 会撞 Unknown column。
-- 与 migration/2026-09-19-iot-m1-thing-model-schema.sql 的分工：后者是 M1 的建表，本文件是 v1 的追加列。
-- =============================================================
ALTER TABLE iot_point_mapping
    ADD COLUMN word_count INT NOT NULL DEFAULT 1 COMMENT '连续寄存器个数（1|2；默认 1 = 旧行为）',
    ADD COLUMN `signed`   TINYINT NOT NULL DEFAULT 0 COMMENT '整型符号：0=无符号 / 1=有符号（Java 侧 Boolean）';
