-- =============================================================
-- 迁移：M-1 物模型域菜单与权限（产品/分组/点位/影子/标签）
-- 适用：**已上线的库**；与 deploy/sql/007-iot-data.sql 的 **M-1 追加段**语句等价。
-- 顺序：迁移目录按文件名排序拼接，本文件（2026-09-20 前缀）排在
--       *-schema / *-menu-data 之后，与 006（结构）→ 007（数据）的顺序一致。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3201, 0, 'IotProduct', 'menu', 0, '/iot/products', '/iot/products/index', 'iot:product:list', 'page.iot.product.title', 'carbon:product', 7, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320101, 3201, 'IotProductCreate', 'button', 0, 'iot:product:create', 'common.create', 1, NOW(), 1, 0),
       (320102, 3201, 'IotProductUpdate', 'button', 0, 'iot:product:update', 'common.edit', 2, NOW(), 1, 0),
       (320103, 3201, 'IotProductDelete', 'button', 0, 'iot:product:delete', 'common.delete', 3, NOW(), 1, 0),
       (320104, 3201, 'IotProductPublish', 'button', 0, 'iot:product:publish', 'page.iot.product.publish', 4, NOW(), 1, 0),
       (320105, 3201, 'IotProductTslImport', 'button', 0, 'iot:product:tsl-import', 'page.iot.product.tslImport', 5, NOW(), 1, 0),
       (320106, 3201, 'IotProductTslExport', 'button', 0, 'iot:product:tsl-export', 'page.iot.product.tslExport', 6, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3202, 0, 'IotDeviceGroup', 'menu', 0, '/iot/groups', '/iot/groups/index', 'iot:group:list', 'page.iot.group.title', 'carbon:group', 8, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320201, 3202, 'IotDeviceGroupCreate', 'button', 0, 'iot:group:create', 'common.create', 1, NOW(), 1, 0),
       (320202, 3202, 'IotDeviceGroupUpdate', 'button', 0, 'iot:group:update', 'common.edit', 2, NOW(), 1, 0),
       (320203, 3202, 'IotDeviceGroupDelete', 'button', 0, 'iot:group:delete', 'common.delete', 3, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320003, 3200, 'IotPointList', 'button', 0, 'iot:point:list', 'page.iot.point.title', 3, NOW(), 1, 0),
       (320004, 3200, 'IotPointCreate', 'button', 0, 'iot:point:create', 'common.create', 4, NOW(), 1, 0),
       (320005, 3200, 'IotPointUpdate', 'button', 0, 'iot:point:update', 'common.edit', 5, NOW(), 1, 0),
       (320006, 3200, 'IotPointDelete', 'button', 0, 'iot:point:delete', 'common.delete', 6, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320007, 3200, 'IotShadowGet', 'button', 0, 'iot:shadow:get', 'page.iot.shadow.get', 7, NOW(), 1, 0),
       (320008, 3200, 'IotShadowUpdate', 'button', 0, 'iot:shadow:update', 'page.iot.shadow.update', 8, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320009, 3200, 'IotTagList', 'button', 0, 'iot:tag:list', 'page.iot.tag.title', 9, NOW(), 1, 0),
       (320010, 3200, 'IotTagCreate', 'button', 0, 'iot:tag:create', 'common.create', 10, NOW(), 1, 0),
       (320011, 3200, 'IotTagDelete', 'button', 0, 'iot:tag:delete', 'common.delete', 11, NOW(), 1, 0),
       (320012, 3200, 'IotTagUpdate', 'button', 0, 'iot:tag:update', 'common.edit', 12, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3201, 320101, 320102, 320103, 320104, 320105, 320106, 3202, 320201, 320202, 320203, 320003, 320004, 320005, 320006, 320007, 320008, 320009, 320010, 320011, 320012);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3201, 320101, 320102, 320103, 320104, 320105, 320106, 3202, 320201, 320202, 320203, 320003, 320004, 320005, 320006, 320007, 320008, 320009, 320010, 320011, 320012);
