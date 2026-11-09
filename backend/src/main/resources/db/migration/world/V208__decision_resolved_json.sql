-- ============================================================================
-- V208：决策落点留痕（空间一致性三方对账）
--
-- 背景（§4.3）：
-- 把 AI 的输出契约从「给坐标」改成「给目标引用」，坐标由 TargetResolver 在服务端解析。
--   一旦「模型给的引用」和「服务端算出的落点」之间有了这一层换算，就必须留下换算过程，
--   否则事后无法区分三种情况：
--     ① 模型守约（给了 f_house_xxx），落点正确；
--     ② 模型给了坐标，被容错收下（resolvedBy=coordinate_fallback）；
--     ③ 落点不可走，被就近纠正（resolvedBy=nearest_walkable）。
--   这三类的比例是「模型守不守契约」的唯一可观测口径，所以落一列 JSON。
--
-- 为什么加在 actor_decision_log 而不是 actor_ai_call_log：
--   actor_ai_call_log 在 AI 调用**完成时**就落库了，而落点解析发生在**行动执行时**（之后），
--   回头改日志要按 (project, character, 秒) 反查，一旦同秒多次调用就会改错行。
--   决策台账是按「决策点」落的，每个决策点一行，天然一对上，直接在同事务里补写即可。
--
-- 为什么是 LONGTEXT 而不是拆列：
--   {targetRef, x, z, featureCode, resolvedBy, snapped, pathLengthM, etaGameSecond} 是一组
--   强耦合的产物，拆列会让「加一个字段就要一次迁移」。它只被读取展示与对账，不被 SQL 条件过滤，
--   因此不需要索引、不需要拆列。
-- ============================================================================

alter table actor_decision_log add column if not exists resolved_json LONGTEXT;

comment on column actor_decision_log.resolved_json is
    'F9 决策落点解析留痕：{targetRef,x,z,featureCode,resolvedBy,snapped,pathLengthM,etaGameSecond}';
