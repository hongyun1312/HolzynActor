-- =====================================================================
-- V203：决策模型通用化 —— A/B 评估表与列改名
--
-- 背景：决策模型接入从「只支持单一模型」改为「多家适配器 + 线协议」，
--       表名/列名里的厂商字样必须清掉，否则后续每接一家都要再改一次 schema。
--
-- 旧名 → 新名：
--   actor_nanojev_ab_run            → actor_decision_ab_run
--   actor_nanojev_ab_sample         → actor_decision_ab_sample
--   ..._sample.nanojev_latency_ms    → decision_latency_ms
--   ..._sample.nanojev_status        → decision_status
--   ..._sample.nanojev_response_json → decision_response_json
--
-- 幂等性：新装库的 V200 基线仍是旧名（V200 不可再改——已应用库的 checksum
--         一旦变化，Flyway 校验会让应用启动失败），本迁移是其唯一收口点；
--         因此这里不用 IF EXISTS，让"表不存在"成为真正的异常信号。
-- =====================================================================

alter table actor_nanojev_ab_run rename to actor_decision_ab_run;
alter table actor_nanojev_ab_sample rename to actor_decision_ab_sample;

alter table actor_decision_ab_sample alter column nanojev_latency_ms rename to decision_latency_ms;
alter table actor_decision_ab_sample alter column nanojev_status rename to decision_status;
alter table actor_decision_ab_sample alter column nanojev_response_json rename to decision_response_json;
