-- =====================================================================
-- V217：目标生命周期（目标状态机）
--
-- 背景：澄江镇二日实跑的目标审计是「零状态流转」——51 个目标全程 status=active，
-- 达成 0、放弃 0、卡住 0。也就是说目标只是 prompt 里的一行装饰：
-- 它既不会因为推进而向前，也不会因为做不到而退场，于是行动层永远围着生理需求打转。
--
-- 缺的不是状态枚举（done/abandoned 早就在表里），而是**时间维度**：
-- 没有「上次有进展是哪一天」，就无法判定「卡住了」。
-- 因此这里只补两列：进展基准日 + 卡住原因。
--
-- 为什么是加列不是建表：目标表 actor_goal 已是权威落点，
-- 「卡住」是同一实体的状态而非新实体；多一张表要同步改架构守卫断言，收益为零。
-- =====================================================================

-- ① 最近一次「有进展」的游戏日（天序号 = floorDiv(gameSecond, 86400)；NULL=尚未观测）
alter table actor_goal add column if not exists progress_game_day bigint;

-- ② 卡住原因（改成 blocked 时写入；恢复 active 时清空）
alter table actor_goal add column if not exists blocked_reason varchar(200);

-- ③ 生命周期扫描按「项目 + 状态」批量取行，补一个索引避免全表扫
create index if not exists idx_goal_project_status on actor_goal (project_id, status);
