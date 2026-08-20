# HolzynActor · LLM 游戏智能体六组件框架 · 设计文档 V1.0

> **文档类型**：详细设计（目标态全量设计 + 分期落地；接口为概念化签名，不含详细 Java 代码）
> **日期**：2026-08-19
> **版本**：V1.0
> **关联**：
> - 理论研究：`docs/设计文档/HolzynActor_六组件框架_理论研究_v1.0.md`（六组件与 Smallville 理论详讲）
> - 评估与缺口：`docs/HolzynActor_NPC自决策自推进_闭环评估与设计方案_V1.0.md`（20 条漏洞 + F1~F17）
> - 项目约定：`docs/主对接文档.md` / `docs/次对接文档_20260819.md` / `AGENTS.md` 约定
> **本文回答**：六组件（感知/记忆/思考规划/角色扮演/行动/学习）在本项目中**每个组件实现什么功能、能力范围多大、组件之间如何通信**，以及如何分期落地。

---

## 0. 文档定位与前置（用户确认，勿推翻）

| # | 前提 | 确认结论 |
|---|---|---|
| P1 | 目标形态 | 沙盒工具（验证功能）；后期对接/嵌入游戏引擎作为控制后端面板（双形态） |
| P2 | 自推进强度 | 无人值守持续自推进 + 架构预留游戏内近实时调用接口 |
| P3 | 规模与成本 | 当前分层 AI（特殊 NPC 走 AI、普通 NPC 程序化/低 AI）；后期全员 AI（特殊用好模型、普通用廉价模型） |
| P4 | 交付物 | 本文 = 目标态全量设计 + 分期落地；含接口（概念签名）/数据模型/与 F1~F17 映射；**不含详细 Java 代码** |
| P5 | 引擎对接 | 双向：引擎可调用本后端接口 + 本后端主动推送事件（协议后续定，先预留） |
| P6 | 代码组织 | **新增智能体核心域 `domain/agent`**；既有域降级为世界状态与能力提供方 |
| P7 | 角色分层 | **统一 Agent 抽象 + 脑力配置（brainProfile）分层** |
| P8 | Learning 范围 | 反思记忆 + 动态关系/好感 + 行为偏好 + **世界/生态级学习**（群体涌现、组织变迁、地点兴衰） |

---

## 1. 总体架构

### 1.1 目标架构分层

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    交互层（现有前端 + 后期引擎对接）                        │
│   Web（项目空间/对话/拓扑/控制台）   │   游戏引擎（REST 决策 + WS 事件订阅） │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │ 调用 / SSE / WebSocket
┌───────────────────────────────▼──────────────────────────────────────────┐
│                  ★ 智能体核心域 domain/agent（新增）                       │
│   AgentRuntime（决策循环 + 触发调度 + 预算）                               │
│   ┌──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐    │
│   │Perception│  Memory  │ Thinking │ Roleplay │  Action  │ Learning │    │
│   │  感知     │  记忆     │ 思考规划  │  角色扮演 │  行动    │  学习    │    │
│   └──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘    │
│   世界事件总线 WorldEventBus（组件间通信主干，F1）                          │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ 单向依赖（读世界状态 / 写行动与事件 / 读写记忆）
┌───────────────▼──────────────────────────────────────────────────────────┐
│   既有功能域（降级为「世界状态与能力提供方」，不改结构，仅暴露能力）          │
│   world(时钟/场景/事件/演化/地点)  character(角色/卡/关系)  conversation(对话)│
│   action(行动引擎/日志)  crowd(普通NPC/字段字典/调度)  memory(记忆)          │
│   knowledge(知识/RAG)  settings(Prompt/API)  usage(用量)  project(项目)     │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.2 新增 `domain/agent` 域结构

```
domain/agent/
├── runtime/     AgentRuntime（决策循环编排）、AgentTrigger（触发源）、BudgetGuard（预算）
├── perception/  PerceptionService、PerceptionVO（感知上下文）
├── memory/      AgentMemoryService（记忆流封装，可代理既有 MemoryService 增强）
├── thinking/    DecisionService（决策）、PlanningService（目标/计划）、ReflectionService（反思）
├── roleplay/    RoleplayService（对话/群聊/演化桥接）
├── action/      AgentActionService（行动执行桥接，经世界事件总线发布）
├── learning/    LearningService（反思巩固/关系演化/偏好/生态统计）
├── event/       WorldEventBus（世界事件总线：发布/查询/广播）
├── brain/       BrainProfile（脑力配置）、BrainProfileService
└── entity/ dto/ vo/ repository/    （按需）
```

> 依赖规则（遵循既有约定）：`agent` 域**单向依赖**既有域（读/写），既有域不反向依赖 `agent`；`agent` 域内部按 `runtime → 组件 → event/brain` 单向依赖，禁止环。

### 1.3 分层脑力配置（BrainProfile，P7 落地）

**脑力配置 = 角色级一行配置**，表达"这个角色用什么脑力跑六组件"。存于 `actor_character`（特殊）/ `actor_ordinary_npc`（普通）扩展或统一新表 `actor_agent_profile`（推荐统一表，两表共用）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `agent_type` | VARCHAR(10) | `special` / `ordinary`（对应两套实体） |
| `agent_ref_id` | BIGINT | 特殊=actor_character.id / 普通=actor_ordinary_npc.id |
| `brain_level` | VARCHAR(20) | `full`（全六组件）/ `light`（精简：感知+思考[廉价]+行动，无深度反思/规划）/ `programmatic`（纯程序化，AI 0 成本） |
| `model_tier` | VARCHAR(20) | `premium`（好模型）/ `standard`（一般模型）/ `auto`（随脑力级默认）——对应 P3 后期"特殊好模型/普通廉价模型"，映射到 API 配置分组（F14） |
| `decision_frequency` | VARCHAR(20) | `realtime` / `per_day` / `per_interval`（低频批量） |
| `thinking_enabled` / `planning_enabled` / `reflection_enabled` | INT 0/1 | 组件级能力开关（light 默认关 planning/reflection） |
| `memory_budget` | INT | 记忆预算覆盖（默认继承全局） |
| `ai_budget_per_tick` | INT | 个人 AI 调用预算（默认继承项目） |
| `updated_at` | DATETIME | 更新时间 |

**默认值**：特殊 NPC 新建 → `full / premium(auto) / realtime`；普通 NPC 新建 → `light / standard(auto) / per_interval`；老数据启动时按现有 `type` 字段幂等补默认配置（对齐 `OldCrowdTableCleaner` 思路）。

### 1.4 统一 Agent 抽象（P7 落地）

- 引入**角色引用（characterRef）**概念：`special:{id}` 或 `crowd:{id}` 的统一字符串，六组件所有"以角色为视角"的接口都用它入参（解决普通 NPC 无 `actor_character` 行的问题）。
- `Agent` 是一个**概念抽象**（运行时按 ref 解析脑力配置分派），不是强制继承体系：`AgentRuntime` 拿到 ref → 查 `actor_agent_profile` → 按 brain_level 走全量或精简管线。

---

## 2. 组件间通信机制（核心章节）

> 六组件不是六个孤立服务，而是被 `AgentRuntime` 编排的**一条认知管线**，外加**世界事件总线**作为"世界级消息主干"。

### 2.1 通信原语（三种）

| 原语 | 说明 | 示例 |
|---|---|---|
| **同步调用（方法级）** | 组件 A 直接调用组件 B 的方法拿结果（进程内，虚拟线程，低延迟） | Perception → Thinking：`决策输入 = perception.build(角色)`；Memory → Thinking：`记忆 = memory.retrieve(角色, query)` |
| **事件发布/订阅（世界事件总线）** | 组件/世界产生的"事实"发布为事件，任何感知方按可见性订阅；**写一次、多方读** | Action 执行后 `eventBus.publish(action事件)`；Perception 检索近期事件 |
| **数据引用（共享世界状态）** | 组件通过既有域 repository 读写世界状态（角色/地点/关系/时钟），不复制 | Action 更新 `current_activity`；Learning 更新关系好感 |

### 2.2 世界事件总线 WorldEventBus（F1，通信主干）

**定位**：六组件之间、组件与世界之间**唯一的"事实通道"**。任何"世界发生了什么"都发布为一条事件（action / crowd / world / evolution / system），带**地点/参与角色/可见性/游戏时间/载荷**，落 `actor_event` + 实时广播（前端 SSE + 预留引擎 WS）。

**事件可见性（visibility）**：`global`（全项目知晓）/ `location`（同地点知晓）/ `private`（仅当事人）——这是"感知不是全知"的关键实现。

**组件如何用它通信**：

| 发布方 | 事件 | 订阅方 |
|---|---|---|
| Action 组件 | 特殊 NPC 行动（visibility=location/global） | Perception（他角色检索）、Memory（沉淀）、Learning（关系/偏好）、前端/引擎 |
| crowd 状态机 | 居民活动聚合（每小时 1 条） | Perception（对话注入）、Learning（生态统计） |
| WorldEngine | 世界事件（跨日） | Roleplay（触发在场回应）、Perception |
| WorldEvolution | 演化归档 | Memory（角色记忆）、Timeline |
| Learning | 关系/组织变迁 | Perception、前端拓扑 |

### 2.3 数据流矩阵（六组件 × 六组件）

> 行=生产者，列=消费者；格内=传递内容（同步调用 / 事件 / 数据引用）。"—"=无直接通路。

| 生产＼消费 | 感知 Perception | 记忆 Memory | 思考 Thinking | 扮演 Roleplay | 行动 Action | 学习 Learning |
|---|---|---|---|---|---|---|
| **感知** | — | 观察事件入记忆流（事件） | 决策输入=感知上下文（调用） | 对话注入【当前所见】（调用） | 情境描述（调用） | 行为样本（调用） |
| **记忆** | 检索结果注入感知（调用） | — | 检索 top-K 记忆（调用） | 记忆注入对话（调用） | 决策依据（调用） | 待巩固记忆（调用） |
| **思考** | 需求：我要什么信息（调用） | 计划/反思写记忆流（调用） | — | 对话意图/决策（调用） | 决策指令（调用） | 决策结果样本（事件） |
| **扮演** | 对话产出（事件） | 对话抽取记忆（调用） | 对话中的新信息（事件） | — | 对话引发的行动评估（调用） | 关系/偏好样本（事件） |
| **行动** | 行动结果（事件） | 行动沉淀记忆（事件） | 计划执行反馈（调用） | 行动对在场者影响（事件） | — | 行为熟练度（事件） |
| **学习** | 关系/生态新知（事件） | 反思/巩固写入（调用） | 高层洞察供决策（调用） | 人格演化约束（调用） | 关系变化影响行动（调用） | — |

### 2.4 一次完整决策循环的时序（AgentRuntime 管线）

```
触发（对话 / 事件 / 世界引擎 tick / 引擎实时 API）
  │
  ▼
① Perception：按 characterRef 组装「我的视角下的世界」
   = 当前游戏时间 + 我所在地/在场者 + 我可见的近期事件(事件总线) + 我的关系 + 我的状态
  ▼
② Memory：按当前情境检索（重要度 + 新近 + 语义相关）→ 我的相关经历/计划/反思
  ▼
③ Thinking：情境 + 记忆 + 感知 + (目标/计划) → 推理 → 决策（行动 or 对话意图）
   ├─ 若需规划：更新我的计划（日计划/行动序列）→ 写记忆流
   └─ 若到反思周期：触发 Reflection（洞察 → 写记忆流）
  ▼
④ Roleplay（对话场景）或 Action（行动场景）
   ├─ Roleplay：组装消息（角色卡 + 感知 + 记忆 + RAG + 场景 + 历史）→ AI 流式回复
   └─ Action：执行行动（改世界状态）→ 发布行动事件
  ▼
⑤ Learning（异步，低优先级）
   = 记忆抽取（对话）/ 行动沉淀记忆 / 关系好感更新 / 行为偏好更新 / 生态统计
  ▼
⑥ 世界状态已更新 → 等待下一次触发（循环）
```

---

## 3. 六组件详细设计

> 每组件统一小节：**职责 / 能力范围 / 输入 / 输出 / 数据模型 / 接口（概念签名）/ 与其它组件通信 / 现有代码与 F1~F17 映射 / 分期落地 / 测试要点**。

---

### 3.1 感知组件 Perception（F2 落地）

#### 职责
为一切"以角色视角"的认知（思考/对话/行动/演化）提供**当前世界状态的视角化视图**——解决上一轮 L-04「决策是盲的」。

#### 能力范围
- 组装感知上下文（游戏时间、所在地、在场者、可见近期事件、关系、自身状态）；
- 可见性过滤（global/location/private）；
- 位置归一（L-08 起点：把角色/居民自由位置字符串归一到地点表）；
- 预留：天气/资源/多模态（后期）。

#### 输入 / 输出
- 输入：characterRef、当前游戏时间点（秒）、（可选）关注的 query/话题；
- 输出：`PerceptionVO`（结构化：gameTimeText / locationName / presentRefs / visibleEvents[≤5] / relationSummary / ownState / memoryHints[≤5]）。

#### 数据模型
| 表/字段 | 说明 |
|---|---|
| （复用）`actor_world_location` | 地点主表（位置归一基准） |
| （复用）`actor_event`（扩展 event_type/location_id/visibility/character_ids/game_time，F1） | 事件查询源 |
| （复用）`actor_character_relation` | 关系摘要源 |
| （新增）`actor_agent_presence`（可选，P1） | 在场登记：agent_ref / location_id / arrived_game_time / left_game_time（后期精细在场管理用） |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `buildPerception` | characterRef, gameSecond, query? | PerceptionVO | 组装角色视角的世界视图（核心） |
| `presentCharacters` | locationId, gameSecond | List<ref> | 某地点的在场者（P1 精细版） |
| `visibleEvents` | characterRef, visibility, sinceGameSecond | List<event> | 可见事件（事件总线查询封装） |

#### 与其它组件通信
- 调用：Memory（记忆检索）、WorldEventBus（事件查询）、world/character/crowd 域（读状态）；
- 被调用：Thinking（决策输入）、Roleplay（对话注入）、Action（情境）、WorldEvolution（演化注入）。

#### 现有代码与映射
- 现状：`ActionEngine.buildSituation/buildPersonaSummary`（仅自身）、`ChatService.resolveSceneMessage`（场景快照）；
- 映射：**F2**；修 L-04/L-06（事件路由感知）/L-07（统一游戏时间）/L-08（位置归一起步）。

#### 分期落地
| 期 | 内容 |
|---|---|
| P0 | PerceptionService 基础版：游戏时间 + 自身状态 + 近期可见事件 + 关系摘要；接入 ActionEngine 决策输入（替换真实时间）与 ChatService 对话注入（【当前所见】段，开关 `holzyn.actor.perception.enabled`） |
| P1 | 在场者聚合（地点粒度）、位置归一到地点表（配合 F11）、query 相关检索 |

#### 测试要点
无在场/无事件空态、visibility 过滤、时间窗过滤、special/crowd 双 ref、与既有开关关闭时行为不变。

---

### 3.2 记忆组件 Memory（F5 + Smallville 检索评分落地）

#### 职责
承接并增强既有 `MemoryService`：把"对话日志式记忆"升级为**记忆流**（observation/fact/summary/reflection/plan 多类），实现**检索评分（重要度 + 新近衰减 + 语义相关）**、**行动/事件记忆沉淀（F5）**、**遗忘工程**。

#### 能力范围
- 记忆流写入（多类、带时间戳/访问时间/重要度）；
- 检索（Smallville 评分公式适配；顶层 top-K 注入）；
- 巩固（摘要）与遗忘（预算淘汰 + 时间衰减）；
- 行动/事件记忆钩子（F5）；
- 事实权威性（当前/历史/被取代，P2，参考 MistScale）。

#### 输入 / 输出
- 输入：characterRef、事件/对话文本、写记忆请求、检索 query；
- 输出：记忆流条目 / 检索 top-K 记忆 / 记忆注入文本。

#### 数据模型（扩展 `actor_memory`）
| 字段（新/改） | 类型 | 说明 |
|---|---|---|
| `character_ref` | VARCHAR(50) NULL | 统一角色引用（special:{id} / crowd:{id}；兼容既有 character_id 双轨，P0 起新写用 ref） |
| `kind`（扩展枚举） | VARCHAR(20) | 既有 fact/summary + 新增 `observation`（感知观察）/ `reflection`（反思洞察）/ `plan`（计划） |
| `importance` | INT | 既有（1-10 对齐 Smallville） |
| `last_access_at` | DATETIME | 检索时更新（recency 衰减依据） |
| `access_count` | INT | 访问计数（"访问频率强化"，遗忘工程参考 §2.2） |
| `evidence_ids` | TEXT NULL | 反思洞察的证据记忆 id 列表（指针） |
| `ttl_hours` | INT NULL | 语义类别 TTL（事实=null 永久 / 临时上下文=小时级；P2） |
| `supersedes_id` | BIGINT NULL | 被取代事实指针（P2，事实修正而非删除） |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `recordObservation` | characterRef, eventId?, content, importance | memoryId | 观察/行动/事件沉淀（F5 钩子） |
| `retrieve` | characterRef, query, topK | List<memory> | 检索评分：importance + recency(指数衰减) + relevance(embedding 余弦，复用 knowledge 向量设施；无 embedding 降级文本/仅重要度) |
| `reflect`（委托 Learning） | characterRef, trigger | insight list | 反思（见 3.6） |
| `concludeRound` | characterRef, 会话窗口 | — | 对话轮次记忆抽取（桥接既有 extractAfterRound） |
| `evict` | characterRef | — | 预算淘汰 + 衰减（既有 evictToBudget 扩展） |
| `memoryContext`（保留兼容） | userId, projectId, characterRef | text | 既有对话注入入口不动 |

#### 与其它组件通信
- 调用：既有 `MemoryService`、`KnowledgeRetrievalService`（embedding）、`actor_memory` repository；
- 被调用：Perception（记忆检索）、Thinking（决策依据）、Roleplay（对话注入）、Learning（巩固）。

#### 现有代码与映射
- 现状：`MemoryService`（fact/summary、top-K、预算淘汰、寒暄门控）已相当完善；
- 映射：**F5**（行动/事件沉淀）、Smallville 检索评分（新增 relevance/recency）、上一轮 L-05/L-15；为 F7（计划/反思记忆）打底。

#### 分期落地
| 期 | 内容 |
|---|---|
| P0 | 记忆流多类（observation/reflection/plan）+ character_ref；行动/事件沉淀（F5）；检索评分加 recency 衰减（relevance 若有 embedding 则启用） |
| P1 | 反思记忆（配合 Learning 3.6）、会话摘要增强 |
| P2 | 事实权威性（supersedes）、TTL 语义分类、访问频率强化 |

#### 测试要点
多类记忆存取、character_ref 双类型、recency 衰减边界、relevance 降级路径、行动沉淀去重（复用 isDuplicate）、预算淘汰含 ref。

---

### 3.3 思考规划组件 Thinking · Planning（F7 / L-13 落地）

#### 职责
承载"决策 + 目标 + 计划 + 反思"的认知核心——把 Perception+Memory 的输入转化为**有方向、可连贯的行动/对话**。

#### 能力范围
- **决策**：情境+记忆+感知 → 决策输出（行动 or 对话意图 or 计划更新）；
- **目标（可选，特殊 NPC）**：长期目标（actor_character_goal）；
- **计划（可选，特殊 NPC）**：日计划/行动序列（对齐 Smallville 层级规划），存记忆流（kind=plan）；
- **重新规划**：计划被世界事件打断时重排；
- **反思（可选）**：周期性调用 Learning.reflect（触发逻辑归本组件或 Learning，推荐归 Learning 统管，本组件声明"何时需要反思"）。

#### 输入 / 输出
- 输入：PerceptionVO + 检索记忆 + （特殊 NPC）目标/计划 + 触发情境；
- 输出：`DecisionVO`（type/action/target/reason/urgency/duration/params/plannedTime，扩展现有 action_decision Schema）+（可选）计划更新。

#### 数据模型
| 表/字段 | 说明 |
|---|---|
| （扩展）`actor_action_plan` | 既有；新增 `plan_kind`（reactive 反应式 / planned 计划内）、`goal_id` |
| （新增）`actor_character_goal` | 特殊 NPC 目标：agent_ref / title / description / status(active/done/abandoned) / priority / created_at / finished_at |
| （新增）`actor_character_plan`（可选，或存记忆流 kind=plan） | 计划快照：agent_ref / date_game_day / plan_items_json（[{time,action,location}]）/ status |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `decide` | characterRef, perception, memoryHints, context | DecisionVO | 单步决策（brain_level=light 走廉价模型/程序化；full 走完整推理） |
| `planDay` | characterRef, gameSecond | plan | 生成/刷新日计划（对齐 Smallville 5~8 条） |
| `updatePlan` | characterRef, eventId | plan | 世界事件打断后重新规划 |
| `setGoal` / `completeGoal` | characterRef, goal | goal | 目标生命周期（P1） |

#### 与其它组件通信
- 调用：Perception、Memory、既有 `ActionEngine`（决策 Schema 校验/程序化兜底复用）、`PromptTemplateService`（模板）；
- 被调用：Action（执行）、Roleplay（对话意图）、Learning（决策结果样本）。

#### 现有代码与映射
- 现状：`ActionEngine.generateDecision`（单步，AI+Schema+重试+程序化兜底，已很好）；
- 映射：**F7**（目标/计划/反思）；修 L-13；对齐 Smallville 层级规划；决策 Prompt 建议接入 RAG（**F8**，P1）。

#### 分期落地
| 期 | 内容 |
|---|---|
| P0 | 决策管线收编到 agent/thinking（复用 ActionEngine 决策逻辑，不改行为）；决策输入改 Perception+Memory 检索（修 L-04 的直接闭环） |
| P1 | 日计划/行动序列（特殊 NPC，存记忆流 kind=plan）；目标系统（actor_character_goal）；计划中断重排；决策接 RAG（F8） |
| P2 | 反思触发调度（与 Learning 联动）、多步剧情规划 |

#### 测试要点
决策 Schema 兼容、程序化兜底不变、计划生成/重排纯逻辑、目标状态机、RAG 注入开关。

---

### 3.4 角色扮演组件 Roleplay（现状收敛 + F4 落地）

#### 职责
把"认知结果"翻译为**符合人设的语言/行为表达**——对话（单聊/群聊）、演化、世界事件回应；并让普通 NPC 也能进入对话（F4）。

#### 能力范围
- 对话消息组装（角色卡 system_prompt + 用户档案 + 感知 + 记忆 + RAG + 场景 + 历史 + 摘要）——现有 `ChatService.buildMessages` 收敛于此；
- 群聊多角色编排（现有 `GroupChatService`）；
- 世界演化逐拍编排（现有 `WorldEvolutionService`）；
- 普通 NPC 对话（F4：member 类型化 + 普通 NPC 档案渲染）；
- 世界事件回应（在场角色自动回应）。

#### 输入 / 输出
- 输入：characterRef、对话/场景/事件上下文、AI 配置；
- 输出：AI 回复流 / 消息落库 / 用量 /（经 Action 钩子）行动评估。

#### 数据模型（扩展 `actor_conversation_member`）
| 字段（新） | 类型 | 说明 |
|---|---|---|
| `member_type` | VARCHAR(10) | `special`（既有，characterId 指向 actor_character）/ `crowd`（新增，characterRef=crowd:{id}，name 快照到 member_name） |
| `member_name` | VARCHAR(50) | 名称快照（普通 NPC 展示/渲染） |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `buildReplyMessages` | convRef, characterRef, perception, memoryHints, scene | message list | 单角色消息组装（桥接 ChatService 内部逻辑） |
| `resolveCharacterPrompt` | characterRef | systemPrompt | 特殊=角色卡 / 普通=NPC 档案渲染（姓名/种族/职业/归属/详情+世界观片段） |
| `ensureCrowdMember` | conversationId, crowdRef | member | F4：把普通 NPC 拉进会话 |

#### 与其它组件通信
- 调用：Perception（【当前所见】）、Memory（记忆注入）、Thinking（对话意图）、Knowledge（RAG）、`LocalAccountService`（用户档案）；
- 被调用：AgentRuntime（对话场景触发）、WorldEngine（事件回应）、前端。

#### 现有代码与映射
- 现状：`ChatService`/`GroupChatService`/`WorldEvolutionService` 已实现大部分；
- 映射：**F4**（普通 NPC 参与，修 L-03/L-09）；对话注入感知段（F2 联动的对话侧）；L-07（场景时间可刷新）。

#### 分期落地
| 期 | 内容 |
|---|---|
| P0 | 对话注入【当前所见】感知段（开关控制）；场景时间改为随世界时钟刷新（修 L-07） |
| P1 | 普通 NPC 进会话（F4，方案 A：member 类型化 + 档案渲染）；群聊/演化接入感知 |
| P2 | 无玩家自主对话（NPC 间自发群聊，配合 WorldEngine） |

#### 测试要点
crowd 成员创建/校验、普通 NPC system_prompt 渲染、感知段开关、场景时间刷新、既有单聊/群聊行为不回归。

---

### 3.5 行动组件 Action（F1 的发布侧 + 记忆钩子落地）

#### 职责
把决策转译为**世界状态变更 + 世界事件发布 + 记忆沉淀**——修复"行动是死环"（L-01/L-05）。

#### 能力范围
- 执行行动（既有 `ActionEngine`：改 current_activity/location + 写日志）；
- **发布行动事件**（经 WorldEventBus，visibility=location/global，带 game_time）；
- **记忆沉淀**（F5：重要度≥4 的行动写 observation 记忆）；
- 普通 NPC 行动执行（crowd 状态机收敛到本组件，统一"行动=事件"语义）；
- 反馈回路（行动结果 → 下一次感知）。

#### 输入 / 输出
- 输入：DecisionVO、characterRef、gameSecond；
- 输出：世界状态变更（角色/居民字段）、行动事件（事件总线）、行动日志、记忆条目。

#### 数据模型
| 表/字段 | 说明 |
|---|---|
| （复用）`actor_action_plan` / `actor_action_log` | 既有行动持久化 |
| （复用）`actor_event`（F1 扩展） | 行动事件落点 |
| （复用）`actor_character.current_activity/location`、`actor_ordinary_npc.state/last_action` | 世界状态 |
| （复用）`actor_memory`（kind=observation） | 记忆沉淀 |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `execute` | characterRef, DecisionVO, gameSecond | result | 执行行动：改状态 → 写日志 → publish 事件 → 写记忆（桥接 ActionEngine） |
| `executeCrowdBatch` | projectId, gameHour | batchResult | 普通 NPC 批量行动（收敛 CrowdScheduledJob/WorldSimulationJob 的人群推进） |
| `publishActionResult` | result, visibility | eventId | 行动事件发布（内部） |

#### 与其它组件通信
- 调用：WorldEventBus（publish）、Memory（沉淀）、既有 `ActionEngine`/`ActorActionLogRepository`/character、crowd repository；
- 被调用：Thinking（决策）、AgentRuntime（执行）、WorldEngine（批量行动）。

#### 现有代码与映射
- 现状：`ActionEngine`（执行 + SSE 广播）已实现 80%；
- 映射：**F1**（行动事件化，修 L-01/L-15）+ **F5**（记忆钩子，修 L-05）；统一真实/游戏时间（L-07）。

#### 分期落地
| 期 | 内容 |
|---|---|
| P0 | execute 增加 publish 行动事件 + 记忆沉淀（F1/F5 在行动侧的落地）；行动日志携带 game_time |
| P1 | 普通 NPC 行动统一进本组件（crowd 事件化）；行动对关系/偏好的影响（配合 Learning） |
| P2 | 行动效果建模（动作对世界/他人的可量化影响，配合动态关系） |

#### 测试要点
行动事件发布字段完整、记忆沉淀触发条件（重要度）、game_time 正确、crowd 批量幂等、SSE 广播不回归。

---

### 3.6 学习组件 Learning（P8 全量落地）

#### 职责
从经历中**改变**：记忆巩固/反思（个体）、关系/好感演化、行为偏好（习惯化）、世界/生态级学习（群体涌现/组织/地点兴衰）。

#### 能力范围
1. **反思记忆巩固**：周期（综合重要度阈值，对齐 Smallville）从近期记忆生成洞察+证据指针，写回记忆流（kind=reflection）；
2. **动态关系/好感演化**：互动（对话/行动/事件）增减 好感/信任/亲密度；关系状态变化发布事件（拓扑实时反映）；
3. **行为偏好**：同类行为频次 → 偏好/熟练度（习惯化，影响后续决策概率）；
4. **世界/生态级学习**：周期聚合统计（群体行为分布、组织成员变迁、地点人流兴衰）→ 写入世界状态/项目级记忆/生态快照，供感知与世界引擎消费。

#### 输入 / 输出
- 输入：近期事件/记忆、角色互动样本、生态统计窗口；
- 输出：反思洞察（记忆流）、关系好感更新（关系表 + 事件）、偏好记录、生态快照（项目级）。

#### 数据模型
| 表/字段 | 说明 |
|---|---|
| （扩展）`actor_character_relation` | 新增动态字段：`affinity`(INT -100~100) / `trust`(INT 0~100) / `relation_state`(stranger/acquaintance/friend/rival/…) / `updated_at` / `last_interaction_at` |
| （新增）`actor_agent_preference` | 偏好：agent_ref / behavior_key / count / preference_score / updated_at |
| （新增）`actor_world_ecosystem` | 生态快照：project_id / snapshot_type(群体分布/组织变迁/地点兴衰) / period_game_day / snapshot_json / created_at |
| （复用）`actor_memory`（kind=reflection / project 级） | 反思洞察与生态摘要记忆 |

#### 接口（概念签名）
| 方法 | 入参 | 返回 | 说明 |
|---|---|---|---|
| `reflect` | characterRef, gameSecond | insights | 反思：阈值触发 → 洞察 + 证据指针 → 记忆流（P1 起） |
| `updateRelation` | projectId, fromRef, toRef, delta, reason | relation | 好感/信任更新 + 状态机 + 发布事件（P1） |
| `updatePreference` | characterRef, behaviorKey, delta | preference | 行为偏好/熟练度（P1） |
| `snapshotEcosystem` | projectId, gameSecond | snapshot | 周期生态统计（群体分布/组织/地点兴衰）→ actor_world_ecosystem + 项目级记忆（P1~P2） |
| `scheduleConsolidation` | projectId, gameSecond | — | 由 WorldEngine 周期调用以上各项（P1 起） |

#### 与其它组件通信
- 调用：Memory（反思写入/检索）、Relation repository、`MemoryService`（项目级记忆）、WorldEventBus（发布变迁事件）；
- 被调用：AgentRuntime/WorldEngine（周期 consolidation）、Thinking（反思触发声明）、Perception（生态新知）。

#### 现有代码与映射
- 现状：关系静态（`actor_character_relation` 无动态字段）；记忆无 reflection 类；无偏好/生态；
- 映射：**F6**（动态关系，修 L-12）+ F7 的反思部分 + 世界/生态学习（对应 P8 用户选择"选项 1+2"）；对齐 Smallville 反思与涌现研究。

#### 分期落地
| 期 | 内容 |
|---|---|
| P1 | 反思（threshold 触发 + 洞察 + 证据指针）；关系动态字段 + 互动增减 + 事件发布（F6）；行为偏好初版 |
| P2 | 生态快照（群体/组织/地点兴衰）+ 生态注入感知与世界引擎；偏好影响决策概率 |

#### 测试要点
反思阈值与去重、关系增减方向/边界（±100）、偏好计数、生态统计聚合纯逻辑、项目级记忆写入。

---

## 4. AgentRuntime 与触发调度设计

### 4.1 决策循环
`AgentRuntime` 是六组件的**唯一编排入口**（facade）：对外提供统一触发方法，对内按 `brainProfile` 分派组件管线（§2.4 时序）。触发一次 = 跑一遍 ①~⑥（学习步骤可异步/降频）。

### 4.2 触发源与优先级
| 触发源 | 说明 | 优先级 |
|---|---|---|
| `dialog` | 对话消息/事件回应（现有 ChatService/GroupChatService 桥接） | 高 |
| `event` | 世界事件注入后的在场回应（现有 WorldEventService） | 高 |
| `engine_tick` | 世界推进引擎（F3）逐游戏小时/跨日触发 | 中 |
| `manual` | 页面手动触发行动/演化 | 中 |
| `engine_api` | 后期游戏引擎实时调用（REST/WS） | 高（预留） |
| `consolidation` | Learning 周期（反思/关系/生态） | 低（异步） |

### 4.3 成本与预算（BudgetGuard）
- 全局：`holzyn.actor.agent.budget-per-tick`（沿用世界模拟上限思想，默认 5）；
- 角色级：`actor_agent_profile.ai_budget_per_tick`（特殊>普通）；
- 预算耗尽 → 降级：特殊走程序化兜底（现有），普通走程序化状态机；
- **按脑力配置路由模型**（F14 预留）：`model_tier=premium/standard` → 对应的 API 配置分组（`ModelApiService` 增"角色层级默认 provider 分组"，`AiProviderRouter` 按 tier 解析）。

### 4.4 并发与幂等
- 沿用虚拟线程 + 会话级互斥 + `AtomicBoolean` 防重入（对齐 ChatService/WorldSimulationJob）；
- 世界推进引擎统一推进入口（收敛 CrowdScheduledJob + WorldSimulationJob），暂停语义统一（修 L-17/L-18）；
- 所有"按游戏小时推进"的组件接口幂等（基于 last_game_hour / last_schedule_at）。

---

## 5. 数据模型汇总（新表 / 新字段）

| 表 | 新增内容 | 归属组件 | 期 |
|---|---|---|---|
| `actor_agent_profile` | 脑力配置（§1.3 全字段） | brain | P0 |
| `actor_event` | event_type / location_id / location_name / character_ids / visibility / payload_json / game_time | 通信（F1） | P0 |
| `actor_memory` | character_ref / kind 扩展(observation·reflection·plan) / last_access_at / access_count / evidence_ids / ttl_hours / supersedes_id | Memory | P0~P2 |
| `actor_conversation_member` | member_type / member_name | Roleplay（F4） | P1 |
| `actor_character_relation` | affinity / trust / relation_state / updated_at / last_interaction_at | Learning（F6） | P1 |
| `actor_agent_presence` | 在场登记（§3.1） | Perception | P1（可选） |
| `actor_character_goal` | 目标（§3.3） | Thinking | P1 |
| `actor_action_plan` | plan_kind / goal_id | Thinking | P1 |
| `actor_agent_preference` | 偏好（§3.6） | Learning | P1 |
| `actor_world_ecosystem` | 生态快照（§3.6） | Learning | P2 |

---

## 6. 分期实施路由（与上一轮 F1~F17 对齐）

| 期 | 本文内容 | 对应上一轮 | 说明 |
|---|---|---|---|
| **P0** | ① 世界事件总线（通信主干）② Perception 感知（基础版+接入决策/对话）③ AgentRuntime 骨架 + brainProfile ④ 行动事件化 + 记忆沉淀 ⑤ 记忆流多类 + recency 检索 | F1 / F2 / F3 / F5（+L-02/L-04/L-05/L-07/L-15/L-18） | 先通"行动→事件→感知→决策"主环 |
| **P1** | 反思 + 动态关系 + 行为偏好 + 日计划/目标 + 普通 NPC 对话 + 位置归一 + 决策接 RAG + 场景时间刷新 | F4 / F6 / F7 / F8 / F9~F11（+L-03/L-06/L-08/L-09/L-12/L-13/L-14/L-16/L-17） | 个体智能感 + 社会动力学 + 一致性 |
| **P2** | 生态/世界级学习 + 事实权威性 + 引擎双向接口（REST+WS）+ 模型分层路由 + 异步队列预算 + 大规模人群重构 | F12~F17（+L-19/L-20） | 引擎对接 + 规模化 |

> 实施顺序建议：**先 P0 五个交付项内的「事件总线 → 感知 → 行动事件化/记忆沉淀 → AgentRuntime 骨架 → brainProfile」**，每步保持后端 `mvn test` 全绿 + 前端 `npm run build` + 对接文档更新。

---

## 7. 兼容与迁移

- 既有服务**保留**（`ActionEngine`/`MemoryService`/`ChatService`/`OrdinaryNpcService` 等），agent 域以**桥接/包装**方式收敛，不加开关时行为不变（新增能力全部默认关闭或默认值兼容）；
- 新增开关：`holzyn.actor.agent.enabled`（总开关，默认 false→P0 完成后再默认 true）、`holzyn.actor.perception.enabled`、`holzyn.actor.agent.events-enabled` 等，逐项灰度；
- `actor_memory.character_id` 与 `character_ref` 双轨：P0 新写用 ref，读兼容两者；P2 前迁移归一；
- 旧 `actor_crowd_runtime`/`actor_character_relation` 结构只增字段不删（`ddl-auto=update` 幂等）；
- 启动清理：仅 `actor_agent_profile` 缺省补行（对齐 OldCrowdTableCleaner 幂等思路），不删旧数据。

---

## 8. 测试与验证策略

- **纯逻辑静态单测**：PerceptionVO 组装/过滤、检索评分（recency 衰减/阈值）、关系增减边界、偏好计数、生态统计、反思触发阈值、计划生成/重排（对齐既有 `*Test` 风格，放 `domain.<域>.service` 测试包）；
- **集成测试（H2）**：事件总线发布→感知可见→行动执行→记忆沉淀全链路；
- **运行时冒烟**：临时项目 → 世界初始化 → 对话触发决策 → 行动事件在时间线/拓扑可见 → 他角色感知到（P0 验收点）；
- **回归**：既有 192 测试全绿 + 前端 build 通过；
- **性能/成本**：预算耗尽降级路径、万人级普通 NPC 批量推进（P2）。

---

## 9. 风险与开放问题

1. **成本**：六组件全开会显著增加 token——必须预算 + 分层 + 反思低频（对齐 [The Forgetting Problem](https://tianpan.co/blog/2026-04-12-the-forgetting-problem-when-agent-memory-becomes-a-liability)）；
2. **长程一致性**：记忆流/反思缓解但不根除漂移——建议引入"事实权威性"（P2）与剧情锁/导演控制；
3. **涌现失控**：群体/生态学习可能涌现出设计外行为——事件审计 + 暂停 + 回滚能力；
4. **双轨迁移**：memory 的 character_id→character_ref 迁移需谨慎，避免老数据不可读；
5. **引擎对接**（P2）协议未定——先以"决策接口 + 事件订阅"两个稳定面设计，协议细节后补。

---

> 本文为 V1.0 目标态设计；实施以 P0 优先，每轮按项目对接文档机制记录。理论研究依据见《HolzynActor_六组件框架_理论研究_v1.0.md》。
