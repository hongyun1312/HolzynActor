-- V209：AI 调用日志补记「游戏内秒」，用于按游戏天统计缓存命中率。
--
-- 为什么需要：缓存命中率是成本主指标，但它的分母（prompt token）和命中量都与
-- 「世界内正在发生什么」强相关——同一个游戏日内语气、地点、日程稳定，前缀可复用；
-- 跨到下一个游戏日，日程/天气/日期都变了，命中率自然掉一档。按真实时间聚合会把
-- 两个游戏日混在一起，看不出「一天之内缓存到底稳不稳」。
--
-- 口径：写入时刻的世界时钟权威游戏秒（actor_world_clock.last_game_second 折算），
-- 游戏天 = game_second / 86400 取整；0 表示当时拿不到时钟（不参与日维度聚合）。

ALTER TABLE actor_ai_call_log ADD COLUMN IF NOT EXISTS game_second BIGINT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_ai_call_log_game_second ON actor_ai_call_log (game_second);
