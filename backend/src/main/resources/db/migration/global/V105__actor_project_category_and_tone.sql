-- ============================================================
-- 目录库：世界分类与程度的展示快照（第 104 轮）
-- ------------------------------------------------------------
-- 画廊列表是一次性的 actor_project 扫描（WorldIndexDao.readAll），卡片上要显示
-- 「仙侠 · 休闲」就必须把分类与程度也放进目录库，否则要么每张卡再查一次世界库（N+1），
-- 要么只能打开世界才看得到。
--
-- 写入口径：只有一个写入口 —— WorldIndexDao#updateClassification，
-- 由「保存世界观」「改档位」两条业务链在写世界库成功后同步；
-- 世界库的 actor_project 同名两列只是镜像（WorldIndexColumnsTest 要求两库列集合逐列相同）。
--
-- 取值为空 = 历史世界还没填过：画廊不显示标签，运行时按 standard 处理（WorldTone.ofCode 的缺省）。
-- ============================================================

alter table actor_project add column if not exists genre varchar(32);
alter table actor_project add column if not exists tone varchar(16);
