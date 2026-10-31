-- ============================================================
-- 世界初始化状态快照（世界库侧镜像列）
-- ------------------------------------------------------------
-- 为什么世界库也要加这几列：WorldIndexColumnsTest 断言
-- 「两库 actor_project 列集合逐列相同」——同一个 Hibernate 实体（ActorProject）
-- 要能在两个库里都读，差一列就是进世界后读实体 500。初始化快照本身只写目录库
-- （画廊列表与初始化向导读的都是目录库），世界库这几列留空即"未初始化"的默认语义。
--
-- init_steps 语义见 global/V104 注释（步骤明细 JSON）。
-- ============================================================

alter table actor_project add column if not exists init_status varchar(16);
alter table actor_project add column if not exists init_done int;
alter table actor_project add column if not exists init_total int;
alter table actor_project add column if not exists init_steps varchar(8000);
