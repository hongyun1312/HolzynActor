-- ============================================================================
-- V6：新手引导 / 界面导览 的「已看过」状态（跨重启持久化）
--
-- 背景（2026-10-23 实测定位）：
--   引导完成/跳过状态此前只写浏览器 localStorage。桌面端每次启动用
--   getFreePort() 取随机端口打开窗口（http://127.0.0.1:<随机端口>），而 localStorage
--   按 origin（scheme+host+port）隔离 —— 端口一变 origin 就变，localStorage 整体失效，
--   于是「已经跳过/看完的引导」在重启后又出现；同理「界面导览」（tourSeen）每次启动
--   都会因为读不到标记而自动再播一遍。
--
-- 方案：两类「已看过」状态都落到本地账户（后端为唯一权威），前端 localStorage 只保留
--   「本次会话待启动」等短周期标记。
--
-- 迁移原则：只加列、带默认值，存量账户默认 0（升级后仍会看到一次新引导，完成后即持久）。
-- ============================================================================

alter table actor_local_account add column if not exists guide_completed integer not null default 0;
alter table actor_local_account add column if not exists guide_completed_at timestamp(6);
alter table actor_local_account add column if not exists layout_tour_seen integer not null default 0;

