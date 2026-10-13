-- ypbin-iot Modbus 寄存器解码 v2 结构（增量迁移；与 007-iot-data.sql 追加部分的 DDL 段语句等价）
-- 内容：iot_point_mapping 新增 register_format（int|float|bits，默认 int）、bit_offset/bit_length（仅 bits）。
-- ⚠️ 文件名用 `v2-register-format` 前缀：等价脚本按文件名字典序拼接，`register-format...` 会排在
--    v1 的 `...-register-schema.sql` 之前，与 007 的追加顺序（v1 后 v2）不一致 ⇒ 门禁必红。
-- 真库 IT 的 ItSchema 清单同样追加在 v1 之后。
-- =============================================================
ALTER TABLE iot_point_mapping
    ADD COLUMN register_format VARCHAR(16) NOT NULL DEFAULT 'int' COMMENT '寄存器值形态：int|float|bits（默认 int = v1 行为）',
    ADD COLUMN bit_offset      INT          NULL COMMENT '仅 bits：起始位（0..15）',
    ADD COLUMN bit_length      INT          NULL COMMENT '仅 bits：位宽（1..16-bit_offset）';
