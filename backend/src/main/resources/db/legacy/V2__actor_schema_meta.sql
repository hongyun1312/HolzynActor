-- ============================================================
-- V2__actor_schema_meta.sql —— U3 迁移示例（2026-10-15）
--
-- 迁移模式演示（后续每个涉及 DDL/DML 的迭代都新增 V3、V4… 版本化脚本）：
--   1. V1__baseline.sql 已由 Hibernate 从当前实体元数据生成（全新库建全表；
--      既有库经 baseline-on-migrate 标记为 V1 后跳过执行，玩家数据 0 风险）；
--   2. 本脚本 V2 展示「新增一张业务表 + 种子行」的标准写法：
--      - actor_schema_meta：后端 schema 元数据表（key-value），
--        供桌面端更新机制/运维确认「当前库处于哪个迁移版本」；
--      - 幂等设计（CREATE TABLE 无 IF NOT EXISTS：Flyway 保证每个版本只执行一次，
--        且 V1 与 V2 的先后顺序由版本号严格保证）。
--
-- 兼容性：本脚本为 H2（MODE=MySQL 兼容）方言；remote=MySQL profile 已关闭 Flyway
-- （见 application-remote.yml spring.flyway.enabled=false），不受本脚本影响。
-- ============================================================

-- 应用级元数据表：记录库 schema 迁移状态与当前应用版本口径
CREATE TABLE actor_schema_meta (
    meta_key   VARCHAR(64)  NOT NULL,
    meta_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_actor_schema_meta PRIMARY KEY (meta_key)
);

-- 种子行：当前迁移版本（供桌面端更新机制校验「后端 schema 已就绪」）
INSERT INTO actor_schema_meta (meta_key, meta_value, updated_at) VALUES
    ('schema_version', '2', CURRENT_TIMESTAMP),
    ('baseline_app_version', '1.1.0-beta.1', CURRENT_TIMESTAMP);
