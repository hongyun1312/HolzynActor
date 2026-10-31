-- V1.1.2-beta.1 世界库增量迁移：actor_project 镜像表补齐目录库 V101 的新列
--
-- 为什么世界库也要加同样的列：世界库里的 actor_project 是「只镜像自己那一行」的只读副本
-- （MirrorTables.OWN_ROW_TABLE），而 Hibernate 的 ActorProject 实体在两个库里是同一个映射。
-- 只要两边列不一致，世界上下文里任何一次读取该实体会因为「缺列」直接 500 —— 这类错误
-- 不会在启动时暴露，只在用户已经进入世界、点了某个页面之后才炸。
-- 因此本文件必须与 V101 逐列同构（WorldIndexColumnsTest 会比对两库 actor_project 的列集合）。
--
-- 注意：镜像同步（WorldMirrorSyncService）按「两库列交集」搬运，所以即使某天只有一边加了列
-- 也不会同步失败；但缺列的读失败是真实风险，故仍要求两边同步演进。

alter table actor_project add column if not exists data_dir_name   varchar(64);
alter table actor_project add column if not exists last_opened_at  timestamp(6);
alter table actor_project add column if not exists last_closed_at  timestamp(6);
alter table actor_project add column if not exists game_second     bigint;
alter table actor_project add column if not exists character_count integer;
alter table actor_project add column if not exists size_bytes      bigint;
alter table actor_project add column if not exists world_status    varchar(16) default 'closed';
alter table actor_project add column if not exists manifest_json   text;
alter table actor_project add column if not exists token_in_total  bigint;
alter table actor_project add column if not exists token_out_total bigint;
alter table actor_project add column if not exists ai_call_count   bigint;
alter table actor_project add column if not exists schema_version  varchar(32);

create unique index if not exists uk_project_uid on actor_project (project_uid);
