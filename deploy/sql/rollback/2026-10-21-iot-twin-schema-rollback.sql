-- 回滚：2026-10-21-iot-twin-schema.sql（数字孪生 4 张表）。
-- 影响：回滚后数字孪生功能整体不可用；**全部场景树、点位、属性绑定随表删除而丢失，不可恢复**。
--       若要保留配置，请先 mysqldump 这 4 张表：
--         mysqldump ... iot_twin_model_asset iot_twin_scene iot_twin_point iot_twin_point_binding
-- 顺序：先回滚 jar（新代码不再读写这 4 张表）再执行本脚本；反过来会让新 jar 因表不存在持续报错。
-- 模型文件：本脚本只删元数据，**不删存储里的模型文件**（SQL 脚本无法访问存储）。
--       清理物理文件请手工删除 ypbin-iot 容器卷内 /data/ypbin/twin/models/ 下对应文件。
DROP TABLE IF EXISTS iot_twin_point_binding;
DROP TABLE IF EXISTS iot_twin_point;
DROP TABLE IF EXISTS iot_twin_scene;
DROP TABLE IF EXISTS iot_twin_model_asset;
