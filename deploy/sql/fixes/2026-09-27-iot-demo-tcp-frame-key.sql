-- =============================================================
-- 演示设备 9300012（demo-dev-curve）temperature 点位的**协议地址修正**
--
-- 背景（2026-09-27 一手核实）：
--   · 该设备的接入协议是 `tcp`（纯透传），喂数源是 `docs/tools/access-tcp-simulator.py`，
--     帧格式 `TEMP=23.5,SEQ=N\n`；
--   · 但它的点位映射沿用了 Modbus 形态（raw_address='holding:0'），而 TCP 透传适配器
--     （ypbin-iot-protocol-tcp 的 TcpSession#dispatch）**没有寄存器语义**，只会把整帧原始字节
--     交给宿主 ⇒ 该地址永远匹配不到任何东西，读数落成 `[B@<hash>`（曲线画不出来）。
--   · 采集侧解码层（access 的 TextFrameValueDecoder）按约定「TCP 文本帧的 raw_address 就是
--     `KEY=VALUE` 的键」，因此这里把键声明为 `TEMP`。
--
-- 只改 temperature（映射 id 9500011）：TCP 适配器把**每一帧**都投递给订阅地址列表的第 0 个，
-- 即 temperature 这一个点位，其余三个点位（humidity/serialNo/demoBoundary）本来就不会收到数据
-- （见 docs/ACCESS-ENABLE.md §3.1），因此不需要（也不应该）为它们编造帧键。
--
-- 回滚：deploy/sql/rollback/2026-09-27-iot-demo-tcp-frame-key-rollback.sql
--
-- ⚠️ 为什么放 `fixes/` 而不是 `migration/`：`migration/*-iot-*.sql` 会被同步门禁
--   （tools/check-iot-sql-equivalence.sh）按语句逐条与 006+007 比对；本文件是**一次性的生产数据订正**
--   （演示数据不在仓库种子脚本里，是运行时建的），不是安装脚本的一部分，放进去会让门禁必然转红。
-- =============================================================

UPDATE iot_point_mapping
SET raw_address = 'TEMP'
WHERE id = 9500011
  AND device_id = 9300012
  AND raw_address = 'holding:0';
