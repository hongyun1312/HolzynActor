-- ============================================================
-- V3__prompt_template_category.sql —— Prompt 模板功能分类（2026-10-19）
--
-- 需求：设置页「Prompt 模板」按功能分类展示（横向 Tab 分组）。
-- 为 actor_prompt_template 增加 category 列（功能分类键，如 dialogue/character/worldgen…），
-- 由 PromptTemplateService 的 CODE_CATEGORY 分类表在启动 seed 时回填：
--   - 内置模板：seed 时按编码写入分类（缺失/不一致则同步）；
--   - 用户/项目覆盖：创建时继承其编码对应的分类（旧数据为 NULL 时在 VO 层按编码回退分类）。
-- category 为空不破坏任何既有行为（VO 有按编码的分类兜底）。
--
-- 兼容性：H2（MODE=MySQL 兼容）方言；remote=MySQL profile 已关闭 Flyway，不受影响。
-- ============================================================

ALTER TABLE actor_prompt_template ADD COLUMN category VARCHAR(40);

-- 更新 schema 元数据（桌面端更新机制以此确认迁移版本已就绪）
UPDATE actor_schema_meta SET meta_value = '3', updated_at = CURRENT_TIMESTAMP
 WHERE meta_key = 'schema_version';
