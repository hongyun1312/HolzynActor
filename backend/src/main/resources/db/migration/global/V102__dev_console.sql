-- V1.1.2-beta.1 目录库增量迁移：开发者模式控制台（参数覆盖 + 操作审计）
--
-- 为什么开 V102 而不是塞回 V100/V101：
--   V100 是 gen_worldsplit_schema.py 生成的「纯复刻基线」，WorldSchemaParityTest 会逐列比对
--   「V100 == legacy」；V101 是本版本世界存档索引列。开发者模式的表是**纯新增能力**，
--   与既有基线和索引迁移都无关，按「新能力 = 新版本号」的既有约定单开一版。
--
-- 两张表的职责（单一写入口都是 DevParamService）：
--   actor_dev_param  参数覆盖行（UI 展示/审计对照的唯一事实源）。effect 语义：
--                    immediate = 通过 bean 反射直改，写库即生效；
--                    restart  = 只写覆盖文件（data/dev-param-overrides.properties），重启后端时由
--                               DevOverrideEnvironmentPostProcessor 置顶注入。
--   actor_dev_audit  操作审计（只增不改）：谁、何时、在哪个页面、改了哪个键、旧值/新值、生效方式。
--                    不设外键：审计要能在参数行被「恢复默认」删掉之后继续独立可查。
--
-- 为什么 param_value 给到 4000：引擎参数里有场景路由表、category-map 这类短 JSON/长串，
-- 留足余量；真正的长文本（Prompt 模板正文）不走本表（走 actor_prompt_template）。

create table if not exists actor_dev_param (
    id          bigint auto_increment primary key,
    param_key   varchar(200)  not null,
    param_value varchar(4000) not null,
    effect      varchar(16)   not null,
    updated_by  varchar(120),
    updated_at  timestamp(6)  not null,
    constraint uk_dev_param_key unique (param_key)
);

create table if not exists actor_dev_audit (
    id         bigint auto_increment primary key,
    action     varchar(32)   not null,
    target     varchar(200)  not null,
    old_value  varchar(4000),
    new_value  varchar(4000),
    effect     varchar(16),
    operator_name varchar(120),
    page       varchar(120),
    note       varchar(1000),
    created_at timestamp(6)  not null
);

create index if not exists idx_dev_audit_created on actor_dev_audit (created_at);
create index if not exists idx_dev_audit_target on actor_dev_audit (target);
