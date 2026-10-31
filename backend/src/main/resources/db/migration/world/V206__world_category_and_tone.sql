-- ============================================================
-- 世界分类与程度（第 104 轮）
-- ------------------------------------------------------------
-- 两个新概念的存储口径：
--   1) genre（世界类别）改成固定八类（奇幻/科幻/都市/历史/末世/仙侠/日常/悬疑）。
--      库里仍存中文标签，因为 {{genre}} 会直接拼进十余处提示词，中文读起来最通顺；
--      历史数据是自由文本，这里用关键字把它归一（映射不到的保持原样，
--      由前端提示用户重新选——猜错分类比空着更糟）。
--   2) tone（世界程度）：休闲/轻度/标准/硬核，控制能力上限与设定边界。
--      存稳定编码（casual/light/standard/hard），显示名与边界文案走 Java 枚举，
--      避免数据库里出现「改文案就要改数据」的耦合。
--
-- 为什么要同时给 actor_project 加两列：WorldIndexColumnsTest 断言「两库 actor_project
-- 列集合逐列相同」（同一实体要能在两个库读）；另外画廊列表读的是目录库，
-- 卡片上的「仙侠 · 休闲」标签就来自 actor_project 的快照列（世界库这份只是镜像）。
--
-- 历史世界一律归 standard —— 等于改造前的行为，不产生任何回归。
-- ============================================================

alter table actor_world_setting add column if not exists tone varchar(16);

update actor_world_setting set tone = 'standard' where tone is null or tone = '';

-- 题材归一：命中关键字的改成固定标签（与 WorldCategory.normalize 同一套口径）
update actor_world_setting set genre = '仙侠' where genre is not null
  and (genre like '%仙侠%' or genre like '%修仙%' or genre like '%修真%' or genre like '%武侠%'
       or genre like '%江湖%' or genre like '%宗门%');
update actor_world_setting set genre = '末世' where genre is not null
  and (genre like '%末世%' or genre like '%废土%' or genre like '%丧尸%' or genre like '%末日%'
       or genre like '%灾变%');
update actor_world_setting set genre = '奇幻' where genre is not null
  and (genre like '%奇幻%' or genre like '%魔法%' or genre like '%魔幻%' or genre like '%西幻%'
       or genre like '%幻想%' or genre like '%精灵%');
update actor_world_setting set genre = '科幻' where genre is not null
  and (genre like '%科幻%' or genre like '%赛博%' or genre like '%太空%' or genre like '%星际%'
       or genre like '%机甲%' or genre like '%人工智能%');
update actor_world_setting set genre = '悬疑' where genre is not null
  and (genre like '%悬疑%' or genre like '%推理%' or genre like '%侦探%' or genre like '%恐怖%'
       or genre like '%惊悚%' or genre like '%诡秘%');
update actor_world_setting set genre = '历史' where genre is not null
  and (genre like '%历史%' or genre like '%古代%' or genre like '%王朝%' or genre like '%中世纪%'
       or genre like '%民国%' or genre like '%战国%');
update actor_world_setting set genre = '日常' where genre is not null
  and (genre like '%日常%' or genre like '%治愈%' or genre like '%休闲%' or genre like '%田园%'
       or genre like '%生活模拟%' or genre like '%慢生活%' or genre like '%海岛%' or genre like '%岛屿%'
       or genre like '%小镇%');
update actor_world_setting set genre = '都市' where genre is not null
  and (genre like '%都市%' or genre like '%现代%' or genre like '%校园%' or genre like '%职场%'
       or genre like '%言情%' or genre like '%青春%');

alter table actor_project add column if not exists genre varchar(32);
alter table actor_project add column if not exists tone varchar(16);
