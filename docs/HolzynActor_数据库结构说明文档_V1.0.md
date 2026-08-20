# HolzynActor · 数据库结构说明文档 V1.0

> **文档类型**：数据库结构说明
> **日期**：2026-08-17
> **数据来源**：远程库 `mysql.mbfsr.com:23306/holzyn` 实测（2026-08-17 采集）+ 历史迁移脚本语义整理
> **建表脚本**：`docs/sql/V1.0__holzyn_actor_mysql_all_tables.sql`（MySQL）、`docs/sql/H2__holzyn_actor_all_tables.sql`（H2 本地）
> **说明**：本模块业务表统一 `actor_` 前缀，存放在 web 共享库 `holzyn` 中；`sys_user` 为 web 模块共享表。

---

## 一、总览

共 **24 张 `actor_` 业务表 + 1 张新增本地账户表（共 25 张）**，按功能域分组：

| 分组 | 表 | 功能域 |
|---|---|---|
| 项目/世界观 | actor_project / actor_world_setting | project / world |
| 角色 | actor_character / actor_character_card / actor_character_relation | character |
| 对话 | actor_conversation / actor_conversation_member / actor_message / actor_group_chat_config | conversation |
| 行动 | actor_action_plan / actor_action_log | action |
| 人群 | actor_crowd / actor_crowd_member | crowd |
| 知识/记忆 | actor_knowledge_doc / actor_memory | knowledge / memory |
| 平台 | actor_model_provider / actor_prompt_template / actor_usage_log | settings / settings / usage |
| 世界 | actor_world_clock / actor_event / actor_scene / actor_evolution / actor_evolution_participant / actor_evolution_turn | world |
| 本地账户 | actor_local_account（新增） | account |

通用约定：
- 主键 `id BIGINT AUTO_INCREMENT`；软删字段 `deleted`（0 正常/1 删除）；
- 审计字段 `created_at` / `updated_at DATETIME DEFAULT CURRENT_TIMESTAMP [ON UPDATE CURRENT_TIMESTAMP]`；
- 长文本 `TEXT` / `LONGTEXT`；JSON 字段 `JSON`（MySQL）；H2 下分别映射 CLOB / JSON（见 H2 脚本）。

---

## 二、表结构与说明

### 2.1 actor_project（项目主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| user_id | BIGINT | 归属用户（本地单用户=1，关联 sys_user.id） |
| name / code / cover_url / summary | VARCHAR/TEXT | 名称 / 编码 / 封面 / 概要 |
| status | TINYINT | 0 草稿 / 1 已生成角色卡 / 2 进行中 |
| deleted | TINYINT | 软删除 |
| project_uid | VARCHAR(36) | 项目唯一标识（.holzyn 导入幂等检测） |
| 索引 | idx_project_user / idx_project_status / idx_project_uid |

### 2.2 actor_world_setting（世界观设定，版本化）

| 字段 | 说明 |
|---|---|
| project_id / version | 项目 + 版本号 |
| name / genre / era | 名称 / 题材 / 时代 |
| geography / factions / magic_system / culture / history | 地理 / 势力 / 规则体系 / 文化 / 历史（TEXT） |
| free_text | 完整世界观自由文本（知识库注入源，LONGTEXT） |
| status | 0 草稿 / 1 生效 |

### 2.3 actor_character（角色主表）

| 字段 | 说明 |
|---|---|
| project_id / type | 项目 / 类型（special 特殊型 / common 普通型） |
| name / title / avatar_url | 姓名 / 头衔 / 头像 |
| detail | 角色详细信息（用户输入完整档案，角色卡生成知识源，LONGTEXT） |
| is_protagonist / importance | 是否主角 / 重要度（1-5，决定 AI 投入） |
| status / deleted | 状态 / 软删除 |
| current_activity / location | 行动模拟执行实时更新（当前行动/位置） |

### 2.4 actor_character_card（结构化角色卡，版本化）

| 字段 | 说明 |
|---|---|
| character_id / version | 角色 + 版本 |
| persona_json | 结构化角色卡 JSON（身份/性格/背景/关系/说话风格/知识边界/行为模式） |
| system_prompt | 渲染后的对话系统 Prompt（TEXT） |
| source | generated / manual / edited |

### 2.5 actor_character_relation（角色社会关系图）

from_character_id → to_character_id，relation_type（亲属/师徒/敌对等）+ description；索引 on from/to/project。

### 2.6 actor_conversation（会话）

| 字段 | 说明 |
|---|---|
| project_id / user_id | 项目 / 归属用户 |
| mode | single 单聊 / group 群聊 |
| title / world_event_enabled | 标题 / 世界事件注入开关 |
| last_message_at | 最后消息时间（列表排序） |

### 2.7 actor_conversation_member（群聊成员）

conversation_id + character_id + join_time。

### 2.8 actor_message（消息）

| 字段 | 说明 |
|---|---|
| conversation_id / character_id | 会话 / 角色（assistant 归属） |
| role | user / assistant / system |
| type | text / action / event |
| content / raw_stream | 正文 / SSE 流式原始增量（调试用） |
| status | streaming / done / failed |
| token_in / token_out / prompt_cache_hit_tokens / prompt_cache_miss_tokens | 用量（含 DeepSeek 缓存计费） |

### 2.9 actor_group_chat_config（群聊配置，用户级）

user_id（唯一）+ max_replies（每轮回复上限 1~20）。

### 2.10 actor_action_plan（行动决策）

| 字段 | 说明 |
|---|---|
| character_id / conversation_id | 角色 / 触发会话 |
| action_json | 行动决策 JSON（UE5 契约预留：type/target/params/urgency/duration） |
| trigger_type | after_dialog / scheduled / event / manual |
| status | planned / executing / done / cancelled |
| planned_time / executed_at | 计划 / 实际执行时间 |

### 2.11 actor_action_log（行动执行日志，时间线数据源）

character_id + plan_id + summary/detail + log_time。

### 2.12 actor_crowd（普通型人群组）

| 字段 | 说明 |
|---|---|
| config_json | 批量生成参数（数量/职业分布/密度） |
| member_count / status | 成员数 / 状态 |
| enabled / last_schedule_at / latest_summary | 自动调度开关 / 最近调度时间 / 最近摘要 |

### 2.13 actor_crowd_member（人群成员，参数化个体）

crowd_id + name + profile_json（职业/作息/活动圈）+ state（走/停/对话/休息）+ last_action；索引 on crowd_id/state。

### 2.14 actor_knowledge_doc（知识库文档）

| 字段 | 说明 |
|---|---|
| project_id / character_id | 项目 / 角色（空=项目级知识） |
| title / content | 标题 / 内容（LONGTEXT） |
| embedding | 向量（JSON，RAG 用） |
| status | 0 草稿 / 1 生效 |

### 2.15 actor_memory（长期记忆）

| 字段 | 说明 |
|---|---|
| project_id / character_id | 项目级（NULL 角色 = 项目级记忆）/ 角色级 |
| kind | summary 摘要 / fact 事实 |
| content / importance | 内容 / 重要度（1-5，低重要度滚动淘汰） |
| deleted | 软删除 |

### 2.16 actor_model_provider（AI 模型供应商配置）

| 字段 | 说明 |
|---|---|
| user_id / project_id | 归属用户 / 项目级配置（NULL=用户级默认） |
| name / base_url | 供应商名 / API Base URL |
| api_key_cipher | 加密后的 API Key（AES-256-GCM，禁止明文） |
| model / is_default / supports_stream | 默认模型 / 是否默认（同归属互斥）/ 是否流式 |
| priority / enabled / remark | 路由优先级 / 启用 / 备注 |
| embedding_enabled / embedding_model / purpose | embedding 开关 / 模型 / 用途（chat/embedding） |

### 2.17 actor_prompt_template（Prompt 模板）

| 字段 | 说明 |
|---|---|
| user_id / project_id | 归属：0=内置 / >0=用户覆盖；project_id=项目级覆盖 |
| code | 模板编码（character_card_gen / dialog_system / group_orchestrator / world_event / action_gen / crowd_orchestrator / memory_extract / memory_summarize / scene_generate / evolution_orchestrator / evolution_schedule / project_import* 等） |
| template | 模板内容（{{world_setting}} {{character_json}} 等占位符） |
| 唯一约束 | (user_id, code) 与 (user_id, project_id, code) |

### 2.18 actor_usage_log（AI 调用用量日志）

user_id + project_id + character_id + provider_id + model + scene（card_gen/dialog/action/crowd/evolution）+ token_in/out + prompt_cache_hit/miss + duration_ms + cost；索引 on user/scene/created_at/project_id。

### 2.19 actor_world_clock（世界时钟，每项目一条）

| 字段 | 说明 |
|---|---|
| rate | 速率：每真实小时推进的游戏小时数（默认 24） |
| world_start_at / world_start_game_hour | 真实锚点 / 对应游戏起始小时 |
| last_sim_time / last_game_hour | 最近推进时刻 / 游戏时刻 |
| paused / paused_at | 暂停标记 / 暂停时刻（恢复时补算） |
| last_summary | 最近推进摘要 |

### 2.20 actor_event（事件，时间线/演化归档）

| 字段 | 说明 |
|---|---|
| project_id / character_id | 项目 / 关联角色（可空） |
| title / content / kind | 标题 / 内容 / 类型 |
| source | manual 手动 / ai 生成 / evolution 演化归档 |
| scene_id / evolution_id / game_hour | 场景 / 演化会话 / 游戏时刻 |

### 2.21 actor_scene（场景，世界演化地点）

name + location + description + background + source（AI 生成时记录来源依据，逻辑自洽）+ enabled。

### 2.22 actor_evolution（世界演化会话）

| 字段 | 说明 |
|---|---|
| project_id / scene_id | 项目 / 场景 |
| mode | manual / ai |
| status | running / finished |
| turn_count / background | 轮次 / 背景设定 |
| event_id / ai_summary / finished_at | 归档事件 / AI 摘要 / 结束时刻 |

### 2.23 actor_evolution_participant（演化参与者）

evolution_id + character_id + status（active/left）+ join_at/leave_at；唯一约束 (evolution_id, character_id)。

### 2.24 actor_evolution_turn（演化轮次）

evolution_id + character_id + role（user/assistant/system/narrator）+ type（text/join/leave/finish）+ content + game_hour。

### 2.25 actor_local_account（本地个人账户，本轮新增）

| 字段 | 说明 |
|---|---|
| user_id | 归属用户（本地单用户=1，唯一约束） |
| nickname / avatar_url / signature | 展示类：昵称 / 头像 / 个性签名 |
| identity / occupation / hobbies / taboos | NPC 结构化档案：身份 / 职业 / 喜好 / 禁忌（注入 NPC 上下文） |
| profile_text | 自由长文本「个人档案」（注入 NPC 上下文，LONGTEXT/CLOB） |
| onboarded | 首次设置向导完成标记（0/1） |

---

## 三、关系图（核心）

```
actor_project (1) ──< actor_world_setting
      │ 1 ──< actor_character (1) ──< actor_character_card
      │        │ 1 ──< actor_character_relation (from/to → actor_character)
      │        │ 1 ──< actor_action_plan (1) ──< actor_action_log
      │        │ 1 ──< actor_crowd_member（经 actor_crowd）
      │ 1 ──< actor_crowd ──< actor_crowd_member
      │ 1 ──< actor_conversation ──< actor_message
      │        └──< actor_conversation_member → actor_character
      │ 1 ──< actor_knowledge_doc（可挂角色）
      │ 1 ──< actor_memory（可挂角色）
      │ 1 ──1  actor_world_clock
      │ 1 ──< actor_event ──< actor_scene / actor_evolution
      │        actor_evolution ──< actor_evolution_participant / actor_evolution_turn
      │ 1 ──< actor_usage_log（scene 维度）
      │ 1 ──< actor_model_provider / actor_prompt_template（项目级归属）
sys_user (1) ──< actor_project / actor_conversation / actor_usage_log / actor_model_provider / actor_group_chat_config / actor_local_account
```

---

## 四、设计要点与优化记录

1. **索引补全**：远程库中 `actor_event` / `actor_scene` / `actor_evolution` / `actor_evolution_participant` / `actor_evolution_turn` / `actor_local_account` 缺少外键索引，已在本版脚本补齐（`idx_*`）；
2. **类型归一**：`TINYINT`/`INT` 语义字段统一；`DATETIME` 精度统一（事件/场景/演化用 `DATETIME(3)` 支持毫秒，前端时间线展示需要）；
3. **缓存计费字段**：`actor_message` 与 `actor_usage_log` 均含 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`（DeepSeek 计费口径）；
4. **本地单用户**：`user_id` 语义恒为 1；`actor_local_account` 承载本地用户展示与 NPC 个性化档案；
5. **双库一致**：MySQL 与 H2 脚本表结构一致；H2 下 `LONGTEXT→CLOB`、`JSON→JSON`、`DATETIME(3)→TIMESTAMP(3)`。
