-- =====================================================================
-- V104：目录库 actor_project 增加「世界初始化状态」四列
--
-- 背景：世界画廊卡片 / 世界详情横幅需要在**不打开世界库**的前提下显示
--       「这个世界是否初始化完成」角标，以及每一步的状态（跑了没 / 失败原因）。
--
-- 为什么不做成一张 actor_world_init_step 表：世界库基线被
--       WorldSchemaParityTest / WorldTableClassificationTest / WorldSplitRulesTest
--       冻结为 legacy 的 113 张表（逐表逐列与 db/legacy 对齐），新增表会同时打破
--       表集合等式与 ORM 映射归类；而「每个项目一行步骤状态」本质上是 actor_project
--       的从属性摘要，落在 actor_project 上既满足单一写入口（WorldIndexDao），
--       也不用改任何 schema 治理基线。
--
-- 列语义：
--   init_status   NOT_STARTED / PARTIAL / DONE / DEGRADED / NULL（未知，历史世界未算过）
--                 DONE     = 全部必需步骤已完成
--                 DEGRADED = 必需步骤已完成，但有可选步骤未完成（仍可进世界）
--                 PARTIAL  = 必需步骤未完成
--   init_done     已完成（含跳过）步骤数，用于卡片进度角标
--   init_total    步骤总数（随初始化流程版本变化，故落库而非前端写死）
--   init_steps    步骤明细 JSON：{"LOCATIONS":{"status":"DONE","count":12,"attempts":1,
--                 "message":"","finishedAt":"2026-01-01T10:00:00"}...}
--                 权威明细：区分「从未跑过」与「跑过但 AI 失败」——实时数据校验做不到这点
--                 （例如角色卡生成失败时，角色表为空，与"没跑过"无法区分）。
--
-- 归属：WorldIndexDao 独占写入（见 WorldIndexDao.updateInitSummary / updateInitSteps），
--       世界库侧镜像列见 world/V205（列集合必须与目录库逐列相同）。
-- =====================================================================

alter table actor_project add column if not exists init_status varchar(16);
alter table actor_project add column if not exists init_done   integer;
alter table actor_project add column if not exists init_total  integer;
alter table actor_project add column if not exists init_steps  varchar(8000);
