-- =============================================================
-- 迁移：IoT 结构迁移**登记表**（升级运行器的账本，v1）
-- 表名：iot_schema_migration
-- 用途：deploy/migrate.sh 用它记录「哪些迁移已应用、应用时文件 sha256 是多少」，
--       从而在升级时做到：只跑未登记文件、重跑幂等、文件被改动时校验和判红。
-- 语义：
--   filename   主键 = deploy/sql/migration/ 下的文件名（basename，与 iot-migrations.list 一致）
--   checksum   应用当时文件的 sha256（小写十六进制）；文件后来被改动 ⇒ --status 判「校验和不一致」
--   applied_at 应用/登记时刻
--   applied_by 执行者：install.sh | migrate.sh:<user>@<host> | grandfather:<来源> | 手工
--   success    1=已成功应用；0=尝试过但未成功（可重跑；migrate.sh 会把它当「待应用」）
-- 幂等性：CREATE TABLE IF NOT EXISTS ⇒ 可重复执行。install.sh 全新安装与 migrate.sh 都依赖它。
-- 与 tools/check-iot-sql-equivalence.sh 的关系：
--   本文件是**运行器基础设施**，不属于「006+007 全新安装镜像」，故被等价门禁按名排除在
--   「语句等价」比较之外；但仍在 iot-migrations.list 内，且门禁会断言它排在清单首位。
-- =============================================================

CREATE TABLE IF NOT EXISTS iot_schema_migration
(
    filename   VARCHAR(255) NOT NULL COMMENT '迁移文件名（deploy/sql/migration/ 下的 basename）',
    checksum   CHAR(64)     NOT NULL COMMENT '应用时迁移文件的 sha256（小写十六进制）',
    applied_at DATETIME     NOT NULL COMMENT '应用/登记时刻',
    applied_by VARCHAR(255) NOT NULL COMMENT '执行者：install.sh | migrate.sh:<user>@<host> | grandfather:<来源> | 手工',
    success    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已成功应用；0=尝试过但未成功（可重跑）',
    PRIMARY KEY (filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT 'IoT 结构迁移登记表（升级运行器账本）';
