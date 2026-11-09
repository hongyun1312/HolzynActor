-- =====================================================================
-- V216：开局供给所需的物品定义（对接）
--
-- 背景：物品域把耐久/腐坏/装备/交易/制作都做完了，但真实世界开局时所有持有栈为空，
-- 物品域等于没通电。开局供给（ItemProvisionService）要按职业与地点铺货，因此先要把
-- 「铁匠的锤子、农户的锄头、猎户的弓、铺子里的钱」这些键登记成正经定义——
-- 否则懒建会把它们全建成 material/堆叠 99/基准价 0，市场报价与装备槽全部失真。
--
-- 为什么只动定义表、不建新表：定义是数据不是结构；世界表集合被架构守卫冻结，
-- 多一张表要同时改三处断言，而这里一个键都不需要。
-- =====================================================================

-- ① 「水」的语义修正：它一直挂在 food 分类下，会被「饿了就吃」的规则当成干粮。
--    渴和饿是两条需求，混在一起就会出现「拿水当饭吃」。
update actor_item_def set category = 'drink' where item_key = '水' and category = 'food';

-- ② 补齐供给会用到的定义（幂等：MERGE 按 (project_id, item_key) 覆盖）
--    base_price 是市场报价的基准；堆叠上限按「一次能拿多少」的直觉给。
merge into actor_item_def
(project_id, item_key, name, category, stack_max, base_price, decay_hours, equip_slot) key (project_id, item_key)
values
-- 货币：它就是一个物品（market.currency-item-key 默认 coin），基准价 1 文
(0, 'coin', '铜钱', 'currency', 9999, 1, null, null),
-- 工具/武器：hand 槽，能砍人能磨损
(0, '铁锤', '铁锤', 'weapon', 1, 40, null, 'hand'),
(0, '锄头', '锄头', 'tool', 1, 20, null, null),
(0, '猎弓', '猎弓', 'weapon', 1, 60, null, 'hand'),
(0, '菜刀', '菜刀', 'weapon', 1, 25, null, 'hand'),
-- 材料：箭是消耗品，布匹/药草/纸是常见家当
(0, '箭', '箭', 'material', 99, 2, null, null),
(0, '布匹', '布匹', 'material', 20, 30, null, null),
(0, '药草', '药草', 'material', 50, 8, null, null),
(0, '纸', '纸', 'material', 50, 3, null, null);

-- ③ 老存档里的懒建占位行升级（签名：name = item_key 且仍是 material）
--    只认这个签名，显式定义过的行不动。
update actor_item_def set category = 'weapon', equip_slot = 'hand', base_price = 40, stack_max = 1
where project_id > 0 and item_key = '铁锤' and category = 'material' and name = item_key;
update actor_item_def set category = 'weapon', equip_slot = 'hand', base_price = 60, stack_max = 1
where project_id > 0 and item_key = '猎弓' and category = 'material' and name = item_key;
update actor_item_def set category = 'weapon', equip_slot = 'hand', base_price = 25, stack_max = 1
where project_id > 0 and item_key = '菜刀' and category = 'material' and name = item_key;
update actor_item_def set category = 'tool', base_price = 20, stack_max = 1
where project_id > 0 and item_key = '锄头' and category = 'material' and name = item_key;
update actor_item_def set category = 'currency', base_price = 1, stack_max = 9999
where project_id > 0 and item_key = 'coin' and category = 'material' and name = item_key;
