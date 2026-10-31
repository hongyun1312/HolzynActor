-- V1.1.2-beta.1 目录库增量迁移：actor_project 从「项目表」升级为「世界存档索引」
--
-- 为什么单独开 V101 而不是塞回 V100：
--   V100 是 gen_worldsplit_schema.py 从 db/legacy/V1~V7 过滤生成的「纯复刻基线」，
--   WorldSchemaParityTest 会逐列逐索引比对「V100 == legacy」。把新列写进 V100 会让
--   这个等价性断掉，以后再出问题就分不清「生成器错了」还是「真加过列」。
--   故本版本所有新列一律新版本号：目录库 V101+，世界库 V201+（两边逐列同构）。
--
-- 为什么 actor_project 需要这些列：分档后「存档列表」必须在**不打开任何世界库**的前提下
-- 渲染出卡片（世界名/游戏内时间/角色数/体积/最后打开时间/状态）。这些摘要只能存在目录库，
-- 否则列表页就得逐个打开世界库——那会让「浏览存档」变成「启动 N 个数据库」。
--
-- 列语义：
--   data_dir_name    世界目录名（= project_uid，冗余一列便于人工核对磁盘）
--   last_opened_at   最近一次挂载时间（UI「最后游玩」）
--   last_closed_at   最近一次卸载时间（据此算「未保存时长」告警）
--   game_second      关闭时回写的世界时间快照（列表显示游戏内日期）
--   character_count  角色数快照（不打开世界库也能显示）
--   size_bytes       世界目录体积快照（含库文件与资源）
--   world_status     closed / mounted / broken / missing / incompatible（见 WorldStatus）
--   manifest_json    该世界 manifest 的最近一次快照（列表页免读磁盘）
--   token_in_total / token_out_total / ai_call_count  跨世界用量汇总（UsageService 改读这里）
--   schema_version   建库时的应用版本（诊断用；不作硬门槛）

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

-- project_uid 是世界身份（跨机器唯一），必须唯一：否则启动收编会把同一个磁盘目录
-- 收编成两行索引，列表里出现两个「同名世界」且其中一个永远打不开。
-- H2 唯一索引允许多个 NULL，历史行（project_uid 为空）不会因此失败。
create unique index if not exists uk_project_uid on actor_project (project_uid);
