# HolzynActor · 缺失功能清单 V1.0

> **文档类型**：缺失功能清单（详细展开 + 可执行建议；不含详细 Java 代码）
> **日期**：2026-08-19 | **版本**：V1.0
> **基准**：以《HolzynActor_NPC自决策自推进_闭环评估与设计方案_V1.0.md》（F1~F17）为基础逐条展开，并补充本轮联网调研与代码核实的新发现（标【新增】）
> **关联**：`docs/设计文档/HolzynActor_六组件框架_设计文档_v1.0.md`（六组件映射） / `docs/设计文档/HolzynActor_六组件框架_理论研究_v1.0.md`（理论）
> **口径**：**缺失功能 = 当前系统尚不存在、但对「最终游戏 NPC 智能系统（自决策/自推进）」必需或重要的能力**。每条含：现状 / 缺口描述 / 影响（玩家与系统双视角）/ 建议方案 / 优先级 / 对应六组件 / 建议分期 / 验证方式。
> **优先级定义**：P0 = 自治闭环断链（不补则谈不上"自决策+自推进"）；P1 = 一致性与个体智能感；P2 = 引擎对接与规模化、长期治理。

---

## 1. 缺失功能总览（汇总表）

| 编号 | 名称 | 优先级 | 对应六组件 | 建议分期 | 对应评估编号 |
|---|---|---|---|---|---|
| F1 | 世界事件总线（World Event Bus） | P0 | 通信主干 | P0 | L-01/L-15 |
| F2 | 感知上下文（Perception Context） | P0 | 感知 | P0 | L-04/L-06 |
| F3 | 世界推进引擎重构（World Engine） | P0 | 运行时/行动 | P0 | L-02/L-16/L-17/L-18 |
| F4 | 普通 NPC 参与通道 | P0 | 扮演 | P1 | L-03/L-09 |
| F5 | 行动/事件 → 记忆沉淀 | P0 | 记忆/学习 | P0 | L-05/L-15 |
| F6 | 动态关系/好感系统 | P1 | 学习 | P1 | L-12 |
| F7 | 目标/计划/反思层 | P1 | 思考/学习 | P1 | L-13 |
| F8 | 决策接入 RAG 知识 | P1 | 思考/感知 | P1 | L-14 |
| F9 | 统一推进入口 + 暂停语义修复 | P1 | 运行时 | P1 | L-17/L-18 |
| F10 | 事件按地点/角色路由 + 延迟知晓 | P1 | 感知 | P1 | L-06 |
| F11 | 统一位置模型 | P1 | 感知/行动 | P1 | L-08 |
| F12 | NPC 间自主对话 | P1 | 扮演 | P2 | L-10 |
| F13 | 引擎双向接口（REST + WebSocket） | P2 | 运行时/通信 | P2 | L-20 |
| F14 | 模型分层路由（特殊好/普通廉） | P2 | 运行时/思考 | P2 | L-19 关联 |
| F15 | 异步任务队列 + 预算管理 | P2 | 运行时 | P2 | L-19 |
| F16 | 多端/鉴权预留 | P2 | 运行时/通信 | P2 | L-20 |
| F17 | 大规模人群调度重构 | P2 | 行动/思考 | P2 | L-16 关联 |
| F18【新增】 | 项目级记忆（世界大事记）写入方 | P1 | 记忆/学习 | P1 | — |
| F19【新增】 | 决策链路结构化追踪/可观测性 | P1 | 全部（横切） | P1 | — |
| F20【新增】 | 单聊世界事件注入 AI 上下文 | P0 | 扮演/感知 | P0 | 本轮核实 |
| F21【新增】 | 导演控制/剧情锁（自治与剧情张力） | P2 | 运行时 | P2 | — |
| F22【新增】 | 行为护栏与规范漂移防护 | P2 | 学习/思考 | P2 | — |
| F23【新增】 | 事件数据治理（保留/归档/清理） | P2 | 通信/记忆 | P2 | — |
| F24【新增】 | 模拟回放/回滚/审计 | P2 | 运行时/通信 | P2 | — |
| F25【新增】 | NPC 声誉/关系跨会话持久展示 | P2 | 扮演/学习 | P2 | — |

---

## 2. P0 缺失功能（自治闭环断链——先补这些）

### F1 世界事件总线（World Event Bus）【P0】

- **现状**：`ActionEngine.execute` 只改自身 `current_activity/location` + 写 `actor_action_log` + SSE 广播给前端；普通 NPC 状态机只更新 `state/last_action` 并聚合进 `latestSummary` 字符串；均**不写 `actor_event`、不广播给其他角色、不触发他人记忆/关系变化**。
- **缺口描述**：系统缺少"世界发生了什么"的统一事实通道。行动、居民活动、事件、演化结果没有统一的事件模型（类型/地点/参与角色/可见性/游戏时间），也无法被其他角色/组件感知或沉淀。
- **影响**：
  - 玩家视角：NPC 之间互不知晓对方做了什么，"活世界"感缺失；时间线/拓扑看不到自主行为。
  - 系统视角：感知（F2）、记忆沉淀（F5）、动态关系（F6）、世界/生态学习（Learning）全部没有数据源；自治核心环"行动→世界→感知→决策"无法闭合。
- **建议方案**：新增 `WorldEventBus`（发布/查询/广播三能力）——统一事件模型（event_type=action/crowd/world/evolution/system；location_id/location_name；character_ids；visibility=global/location/private；payload_json；game_time）落 `actor_event`；ActionEngine/人群推进/世界事件/演化归档全部改经它发布；SSE（前端）+ 预留 WebSocket（引擎）实时广播。详见六组件设计文档 §2.2 与《闭环评估》§7.1。
- **优先级**：P0（无依赖、收益最大，为 F2/F3/F5 打底）。
- **对应六组件**：通信主干（组件间 + 世界）。
- **建议分期**：P0 第 1 步。
- **验证方式**：制造一个行动 → `actor_event` 出现 action 事件（含 game_time/visibility）→ 时间线多出节点 → 他角色感知查询可见。

### F2 感知上下文（Perception Context）【P0】

- **现状**：`ActionEngine.buildSituation` 只有"当前真实时间 + 自身 current_activity/location"；`buildPersonaSummary` 只有自身人设；对话场景只有创建时的 location/gameTimeText 快照。**没有任何地点、在场者、近期事件、关系、时辰信息**。
- **缺口描述**：系统缺少"以角色视角组装世界状态"的感知层——决策/对话/演化的输入看不到"世界发生了什么"。
- **影响**：
  - 玩家视角：NPC 行动与"当下世界"脱节（如同地点发生大事却照常闲聊）。
  - 系统视角：决策是"盲的"，无法基于世界/他人/关系做合理行动；这是与 Generative Agents"感知→记忆流→行动"最本质的差距。
- **建议方案**：`PerceptionService.buildPerception(characterRef, gameSecond, query?)` → 结构化 PerceptionVO（游戏时间/所在地/在场者/可见近期事件≤5/关系摘要/自身状态/记忆提示≤5）；接入 ActionEngine 决策输入（并统一为游戏时间）、ChatService 对话注入【当前所见】段（开关 `holzyn.actor.perception.enabled`）、WorldEvolution 逐拍注入。详见六组件设计文档 §3.1。
- **优先级**：P0。
- **对应六组件**：感知。
- **建议分期**：P0 第 2 步。
- **验证方式**：造一条同地点行动事件 → 该角色决策输入/对话【当前所见】中出现该事件 → 决策输出引用之。

### F3 世界推进引擎重构（World Engine）【P0】

- **现状**：`WorldSimulationJob.scanAndSimulate` 只推进 `findDistinctProjectIdByLastMessageAtAfter(now-30min)` 的"活跃项目"；单次补算封顶 24 游戏小时；人群跨多游戏小时只推进一次（取末小时）；`CrowdScheduledJob`（每 5 分钟所有 enabled 项目）不检查世界暂停。
- **缺口描述**：世界**无人互动时冻结**；没有"无人值守持续自推进"能力；两条推进线并发且暂停语义分裂；普通 NPC 作息粒度粗糙。
- **影响**：
  - 玩家视角：离开一会儿世界就停；离线一周回来只补 1 天；暂停后普通居民仍在动（语义矛盾）。
  - 系统视角：与用户确认的目标 P2"无人值守持续自推进"直接矛盾；也是六组件框架"AgentRuntime 持续驱动感知→思考→行动"的前提缺失。
- **建议方案**：`WorldEngine` 收敛推进入口——`actor_world_clock.world_running`（持续运行开关，默认开）+ `last_engine_run_at`（回补基准，替代活跃窗口）+ 逐游戏小时步进（人群逐时推进）+ 预算控制（BudgetGuard）+ 统一暂停语义（所有推进先查 paused）。详见六组件设计文档 §4。
- **优先级**：P0。
- **对应六组件**：运行时 + 行动。
- **建议分期**：P0 第 3 步。
- **验证方式**：无对话项目 running=1 → 观察 last_game_hour 持续推进、普通 NPC 每小时状态变化、跨日事件生成；paused 时全部推进线停止。

### F4 普通 NPC 参与通道【P0】

- **现状**：`ConversationService.create` 会话成员只查 `actor_character`（特殊 NPC）；普通 NPC 在 `actor_ordinary_npc` 独立表，**无法成为会话成员**；其活动只进 `latestSummary` 字符串注入对话。
- **缺口描述**：普通 NPC 无法被玩家/特殊 NPC 直接对话；其言行不产生事件（F1 补齐后仍需对话通道）。
- **影响**：
  - 玩家视角：想找"石鳞"（普通居民）聊天做不到，只能"听说"。
  - 系统视角：普通 NPC 永远是背景板，与"全员 AI（后期）"目标差距大；两套表/两套服务的割裂无法弥合。
- **建议方案**：方案 A（推荐）——`actor_conversation_member` 增 `member_type`（special/crowd）+ `member_name`；会话成员支持 `crowd:{id}` 引用；`ChatService.resolveCharacterPrompt` 对 crowd 成员用普通 NPC 档案渲染（姓名/种族/职业/归属/详情+世界观片段）；配合统一 characterRef。详见六组件设计文档 §3.4。
- **优先级**：P0（参与通道）+ 建议分期 P1（实现依赖 F1）。
- **对应六组件**：扮演。
- **建议分期**：P1。
- **验证方式**：新建会话选择普通 NPC → 可对话 → 记忆归属为 crowd:{id} → 对话后关系按名回填。

### F5 行动/事件 → 记忆沉淀【P0】

- **现状**：`MemoryService` 记忆来源只有对话抽取（`extractAfterRound`）与世界演化归档（写角色级）；行动、事件、人群活动**都不写记忆**。
- **缺口描述**：NPC 的"非对话经历"不可回忆——做了 100 次行动下次对话完全忘记。
- **影响**：
  - 玩家视角：NPC 没有"人生"的延续感；对话中无法引用自主经历。
  - 系统视角：记忆只覆盖对话，自治行为的记忆闭环缺失；`actor_memory` 需要 `character_ref`（支持普通 NPC）与多类（observation 等）。
- **建议方案**：`MemoryService` 增 `recordActionMemory`（行动重要度≥4 写 observation 记忆）+ `WorldEventBus` 发布钩子（事件带 character_ids → 为当事人写经历记忆，开关控制）；`actor_memory` 增 `character_ref` 列。详见六组件设计文档 §3.2/§3.5。
- **优先级**：P0。
- **对应六组件**：记忆 + 行动。
- **建议分期**：P0 第 4 步。
- **验证方式**：触发一次重要行动 → `actor_memory` 出现 observation（ref=special:{id}）→ 该角色对话记忆注入中出现该经历。

### F20【新增】单聊世界事件注入 AI 上下文【P0】

- **现状（本轮代码核实）**：`ChatService.buildMessages` 只纳入 `role=user/assistant` 消息，**`type=event`（role=system）的世界事件消息被排除在 AI 上下文之外**；而 `GroupChatService.buildCharacterMessages` **包含**事件消息（line 409/484）。即：单聊中 NPC 对世界事件的"回应"其实**看不到事件原文**（只有事件触发的行动评估与事后记忆抽取能间接看到）。
- **缺口描述**：单聊与群聊对世界事件的处理不对称；单聊"事件回应"生成时缺少事件内容，导致回复与事件无关或凭空发挥。
- **影响**：
  - 玩家视角：NPC 对刚发生的世界事件回复"答非所问"。
  - 系统视角：事件→回应链路在单聊是断的；与 F2 感知（事件注入）直接相关。
- **建议方案**：`ChatService.buildMessages` 增加对 `type=event` 消息的纳入（按"事件视为系统级情境"注入，或并入感知段【当前所见】）；与 GroupChatService 对齐。
- **优先级**：P0（小而关键）。
- **对应六组件**：扮演 + 感知。
- **建议分期**：P0（可在 F2 时一并修）。
- **验证方式**：单聊注入事件 → 打开 SSE 生成回应 → 检查 AI 请求消息序列含事件文本 → 回复引用事件。

---

## 3. P1 缺失功能（一致性 + 个体智能感）

### F6 动态关系/好感系统【P1】

- **现状**：`actor_character_relation` 只有关系类型（type）+ 名称兜底（id=0 幽灵）；无好感/信任/亲密度，无随互动演化。
- **缺口描述**：关系是静态快照，没有社会动力学。
- **影响**：玩家视角=拓扑/对话中关系一成不变；系统视角=Learning 组件（动态关系）无数据基础，群体涌现（Smallville 式关系形成）无法实现。
- **建议方案**：关系表增 `affinity`(-100~100)/`trust`(0~100)/`relation_state`/`updated_at`/`last_interaction_at`；互动（对话/行动/事件）经 Learning 增减并发布事件；拓扑实时反映。详见六组件设计文档 §3.6。
- **优先级**：P1 | **对应六组件**：学习 | **建议分期**：P1。
- **验证方式**：多次友好互动 → affinity 上升 → 关系状态升级 → 拓扑/角色卡展示变化。

### F7 目标/计划/反思层【P1】

- **现状**：`ActionEngine.generateDecision` 是单步反应式 JSON（type/action/target/reason/urgency/duration/params）；无持续目标、日计划、反思。
- **缺口描述**：NPC 决策"从零开始"，没有方向性与行为弧线（对齐 Smallville 层级规划/反思）。
- **影响**：玩家视角=行为缺乏连贯动机；系统视角=Thinking/Learning 组件的"规划/反思"无实现。
- **建议方案**：`actor_character_goal`（目标）+ 日计划/行动序列（存记忆流 kind=plan）+ 定期反思（洞察+证据指针写记忆流 kind=reflection）；决策注入目标/计划。详见六组件设计文档 §3.3。
- **优先级**：P1 | **对应六组件**：思考 + 学习 | **建议分期**：P1。
- **验证方式**：特殊 NPC 设目标 → 多日观察其行动围绕目标 → 计划被打断后重新规划 → 反思记忆出现。

### F8 决策接入 RAG 知识【P1】

- **现状**：`KnowledgeRetrievalService` 只在对话上下文（`ConversationContextService`）被调用；`ActionEngine`/`OrdinaryNpcService.scheduleWithAi`/`WorldEvolutionService` 决策 Prompt 不检索知识。
- **缺口描述**：自主行动的 NPC 可能违背世界规则/地理/历史。
- **影响**：玩家视角=NPC 做出不符合世界观的行动（OOC 的"行动版"）；系统视角=知识库只服务对话，未服务自治决策。
- **建议方案**：感知上下文（F2）内附带与当前情境相关的知识片段（复用检索服务，无 embedding 降级文本）；决策/调度 Prompt 注入。
- **优先级**：P1 | **对应六组件**：思考 + 感知 | **建议分期**：P1。
- **验证方式**：造一条"禁灵咒"规则知识 → 该地角色决策输入含该片段 → 决策不违反规则。

### F9 统一推进入口 + 暂停语义修复【P1】

- **现状**：`CrowdScheduledJob` 只查 `enabled` 不查 `paused`；与 `WorldSimulationJob`/`ActionScheduledJob`（都查 paused）语义分裂；两条推进线可能并发。
- **缺口描述**：暂停时普通居民仍行动；推进入口分散。
- **影响**：玩家视角=暂停世界后居民还在动（违背"时间冻结"）；系统视角=F3 WorldEngine 收敛的前置。
- **建议方案**：`CrowdScheduledJob` 补暂停检查（一行级）+ 推进收敛到 WorldEngine（F3）。
- **优先级**：P1（一行修复可立即做）| **对应六组件**：运行时 | **建议分期**：P1（可与 F3 同轮）。
- **验证方式**：暂停世界 → 5 分钟后 `actor_ordinary_npc.last_action` 不再变化。

### F10 事件按地点/角色路由 + 延迟知晓【P1】

- **现状**：`WorldSimulationJob` 生成世界事件后注入"最近更新的第一个会话"（`findByProjectIdAndUserIdOrderByUpdatedAtDesc().findFirst()`），与地点/角色/重要度无关。
- **缺口描述**：事件可能进入无关会话；非在场角色"瞬知"或不知。
- **影响**：玩家视角=事件进错对话；系统视角=信息传播无地理/社会逻辑（Smallville 的信息扩散无法体现）。
- **建议方案**：事件带 location_id/character_ids（F1）→ 感知（F2）按可见性路由；"延迟知晓"（P2 可选：非在场者经口碑/新闻延迟获知）。
- **优先级**：P1 | **对应六组件**：感知 | **建议分期**：P1。
- **验证方式**：城西事件 → 城西会话/在场角色感知；城东角色不感知（除非 global）。

### F11 统一位置模型【P1】

- **现状**：`actor_character.location`（自由串）、对话 location（快照）、`actor_world_location` 地点表、普通 NPC location（字典选取）四处互不校验/同步。
- **缺口描述**：角色可"同时在多处"；地点粒度不统一。
- **影响**：玩家视角=角色位置与对话场景矛盾；系统视角=感知/事件路由（F2/F10）依赖统一位置。
- **建议方案**：位置归一——角色/居民/对话引用 `actor_world_location.id`（+ 自由补充兜底）；`location_name` 快照保留。
- **优先级**：P1 | **对应六组件**：感知 + 行动 | **建议分期**：P1。
- **验证方式**：角色行动移动 → 其 location 归一到地点表 → 对话候选/感知在场匹配。

### F12 NPC 间自主对话【P1】

- **现状**：`WorldEventService.advance` 是占位（只返回"自主推进已就绪"）；群聊需用户消息触发；演化需人工开始。
- **缺口描述**：NPC 之间不会自发交谈/协调（Smallville 式"约饭/闲聊"）。
- **影响**：玩家视角=世界缺少自发社交感；系统视角=Roleplay 组件缺自主触发。
- **建议方案**：WorldEngine（F3）在剧情节点触发"无玩家群聊轮"（复用 `GroupChatService` 编排；参与者=同地点/相关角色）。
- **优先级**：P1 | **对应六组件**：扮演 + 运行时 | **建议分期**：P2。
- **验证方式**：无用户消息下，同地点角色触发一次自发群聊 → 消息落库 → 记忆抽取。

### F18【新增】项目级记忆（世界大事记）写入方【P1】

- **现状（本轮代码核实）**：`MemoryService.memoryContext` 读取"项目级记忆（characterId=null，世界大事记，所有角色可见）"，但**当前代码没有任何运行时写入方**——`saveMemory` 仅在对话抽取时以角色 ID 写入；演化归档也只写角色级（`WorldEvolutionService` line 1197 逐参与者写 characterId）；仅 `.holzyn` 导入可带来历史项目级记忆。即"世界大事记"是**只读侧存在、写侧缺失**。
- **缺口描述**：项目级"所有角色都知晓"的记忆无法产生；对话中【世界大事记】段长期为空。
- **影响**：玩家视角=所有角色对重大世界事件（除演化归档外）无从知晓；系统视角=Learning 的生态级知识（组织变迁/地点兴衰）没有"全项目共识"载体。
- **建议方案**：新增"项目级记忆写入方"——重大世界事件（source=world/evolution，重要度≥阈值）由 F1 事件总线/演化归档同时写一条 `characterId=null` 的项目级记忆；预算淘汰沿用既有。
- **优先级**：P1 | **对应六组件**：记忆 + 学习 | **建议分期**：P1。
- **验证方式**：产生一条重要世界事件 → `actor_memory` 出现 characterId=null 记录 → 任意角色对话注入出现【世界大事记】。

### F19【新增】决策链路结构化追踪/可观测性【P1】

- **现状**：各服务有 slf4j 日志（[对话]/[人群AI调度]/[世界模拟] 等）与控制台输出，但**无结构化 trace**（一次决策的 感知输入→检索记忆→Prompt→输出→决策→执行 全链路），排查"哪个环节失败"需翻散落日志（业界多智能体系统首要坑：调试混乱）。
- **缺口描述**：自治能力上线后（F1~F5/F7），决策链路变长，缺统一追踪与可视化。
- **影响**：开发/维护视角=难以定位行为异常与 AI 失败根因；玩家视角=异常行为无法回查解释。
- **建议方案**：决策链路结构化日志（trace_id 贯穿感知→思考→行动→学习；记录 Prompt 摘要/输入输出/用量/耗时/重试/降级/错误）+ 前端"调试面板"（每角色最近 N 次决策轨迹）复用 WorkflowConsole 风格。
- **优先级**：P1（在 P0 能力叠加前先立规范）| **对应六组件**：横切 | **建议分期**：P1。
- **验证方式**：一次决策产生一条含 trace_id 的完整链路记录；失败决策可查 Prompt 与错误原因。

---

## 4. P2 缺失功能（引擎对接 + 规模化 + 长期治理）

### F13 引擎双向接口（REST + WebSocket）【P2】

- **现状**：`CurrentUserProvider` 单用户、SSE 服务端单向；无引擎可调用的决策接口、无事件订阅推送。
- **缺口描述**：游戏引擎（后期控制后端面板）无法"取决策/送状态/收事件"。
- **影响**：与用户确认的 P1（双形态）/P5（双向）目标无实现载体。
- **建议方案**：REST 决策接口（如 `POST /api/engine/npc/{ref}/decide`、`POST /api/engine/world/{id}/tick`、`GET /api/engine/world/{id}/state`）+ WebSocket 事件订阅（`/ws/world/{projectId}` 推送 WorldEventBus 事件）+ OpenAPI 契约先行 + 引擎 API Key 鉴权（复用 `AesCipherService`/`HolzynCrypto`）。详见六组件设计文档 §2.2/§4。
- **优先级**：P2 | **对应六组件**：运行时 + 通信 | **建议分期**：P2。

### F14 模型分层路由（特殊好模型 / 普通廉价模型）【P2】

- **现状**：`AiProviderRouter` 支持显式 providerId，但所有业务调用传 `null`（取项目/用户默认）；`ModelApiService` 有 purpose（chat/embedding/both）但无"角色层级→默认 provider 分组"。
- **缺口描述**：无法实现"特殊 NPC 用好模型、普通 NPC 用廉价模型"（用户确认的后期目标）。
- **影响**：全员 AI 成本不可控；特殊/普通无模型差异。
- **建议方案**：`actor_agent_profile.model_tier`（premium/standard/auto）→ `ModelApiService` 增"层级默认 provider 分组" → `AiProviderRouter` 按 tier 解析（brainProfile 驱动）。
- **优先级**：P2 | **对应六组件**：运行时 + 思考 | **建议分期**：P2。

### F15 异步任务队列 + 预算管理【P2】

- **现状**：`maxAiCalls=5/tick` 是"用满即切程序化"，无全局排队/跨项目预算/优先级。
- **缺口描述**：项目多/角色多时高重要角色可能长期拿不到 AI 决策；后期"全员 AI"更无解。
- **影响**：成本失控或质量失衡。
- **建议方案**：决策/调度入队（优先级：主角>重要>普通）+ 预算跨项目分配（BudgetGuard）+ 失败重试/降级策略（沿用现有 MAX_RETRY/程序化兜底）。
- **优先级**：P2 | **对应六组件**：运行时 | **建议分期**：P2。

### F16 多端/鉴权预留【P2】

- **现状**：无登录单用户、H2 单实例。
- **缺口描述**：引擎/多客户端接入需要 API Key 鉴权与会话管理（不引入完整登录体系）。
- **建议方案**：`HOLOZYN_ACTOR_ENGINE_KEY` 环境变量 + 引擎调用鉴权过滤器 + 多客户端 SSE/WS 会话隔离。
- **优先级**：P2 | **对应六组件**：运行时 + 通信 | **建议分期**：P2。

### F17 大规模人群调度重构【P2】

- **现状**：普通 NPC 单批 30、上限 500、单批一次调用（token 16384）；千人级"全员 AI"无路径。
- **缺口描述**：规模扩张需要分层聚合（组级决策→个体微调）、模型路由（F14）、批处理节流。
- **影响**：千人活世界不可行。
- **建议方案**：分层聚合决策（归属/种族组→个体）、按脑力配置分级、异步队列（F15）；对齐 [Hierarchical NPC Budget Systems](https://ijetcsit.org/index.php/ijetcsit/article/view/743) 思路。
- **优先级**：P2 | **对应六组件**：行动 + 思考 | **建议分期**：P2。

### F21【新增】导演控制/剧情锁（自治与剧情张力平衡）【P2】

- **现状**：无"必须发生/禁止发生"的剧情约束；全自治可能偏离主线。
- **缺口描述**：世界自治与设计者叙事张力之间缺控制层。
- **影响**：作为"最终游戏 NPC 系统"，无法保证关键剧情节点不被自治行为破坏。
- **建议方案**：项目级"导演规则"（剧情锁：指定事件必须/禁止触发；角色行为约束集），在 Thinking/WorldEngine 决策前注入并作为硬约束；对齐业界 [AI Director](https://www.gocodeo.com/post/agentic-ai-in-gaming-self-learning-npcs-and-dynamic-storytelling) 与 HTN 导演思想。
- **优先级**：P2 | **对应六组件**：运行时 + 思考 | **建议分期**：P2。

### F22【新增】行为护栏与规范漂移防护【P2】

- **现状**：无护栏；多智能体模拟实证（[Emergence World](https://www.emergence.ai/blog/emergence-world-a-laboratory-for-evaluating-long-horizon-agent-autonomy)：规范漂移与跨污染——孤立"安全"的 agent 在混合环境中学会胁迫/偷窃）表明群体可能涌现设计外行为。
- **缺口描述**：自治/学习组件上线后，世界规则约束与审计缺失。
- **影响**：世界崩坏/剧情污染/内容风险。
- **建议方案**：世界规则注入（决策 Prompt 的硬性"不可违逆条款"）+ 行为审计（F19 trace 的违规检测）+ 学习范围护栏（关系/偏好不得突破世界观边界）。
- **优先级**：P2 | **对应六组件**：学习 + 思考 | **建议分期**：P2。

### F23【新增】事件数据治理（保留/归档/清理）【P2】

- **现状**：`actor_event` 无保留上限；无人值守逐时推进会产生大量事件行。
- **缺口描述**：H2 库膨胀风险（与记忆预算淘汰同理，事件无淘汰）。
- **影响**：库体积增长、查询变慢。
- **建议方案**：每项目事件保留上限（如 5000 条）+ 滚动归档/清理；与记忆预算淘汰同思路。
- **优先级**：P2 | **对应六组件**：通信 + 记忆 | **建议分期**：P2。

### F24【新增】模拟回放/回滚/审计【P2】

- **现状**：时间线是只读聚合，无回放/回滚。
- **缺口描述**：自治世界出问题时无法复盘/回退。
- **影响**：世界演化不可逆风险。
- **建议方案**：基于事件总线（F1）的事件回放视图 + 项目级快照/回滚点（轻量：按游戏日快照世界状态）+ 审计日志。
- **优先级**：P2 | **对应六组件**：运行时 + 通信 | **建议分期**：P2。

### F25【新增】NPC 声誉/关系跨会话持久展示【P2】

- **现状**：关系/好感（F6 后才有）无面向玩家的持久展示；对话中无"他对我印象如何"。
- **缺口描述**：动态关系/声誉的玩家可见性缺失（业界方向：[NPCs and AI: Balancing Authenticity and Spontaneity](https://lanceblairvo.com/npcs-and-ai-in-persistent-worlds)：NPC 记住三小时前你怎么对它、跨会话声誉）。
- **影响**：玩家与 NPC 的长期关系感弱。
- **建议方案**：角色卡/拓扑/对话头展示动态关系状态与好感（配合 F6）；对话系统注入"他记得你"的提示。
- **优先级**：P2 | **对应六组件**：扮演 + 学习 | **建议分期**：P2。

---

## 5. 六组件映射汇总

| 六组件 | 直接缺失项 | 说明 |
|---|---|---|
| 感知 Perception | F2、F10、F11、F20(联动) | 视角化世界视图、事件路由、位置归一 |
| 记忆 Memory | F5、F18、F23(联动) | 行动沉淀、项目级记忆写入、事件治理 |
| 思考 Thinking | F7、F8、F14、F21、F22 | 目标/计划/反思、RAG、模型分层、导演锁、护栏 |
| 扮演 Roleplay | F4、F12、F20、F25 | 普通 NPC 对话、自主对话、事件注入、声誉展示 |
| 行动 Action | F3、F5、F17 | 引擎、事件化、规模 |
| 学习 Learning | F6、F7、F18、F22、F25 | 关系、反思、生态、护栏、声誉 |
| 运行时/通信（横切） | F1、F3、F9、F13、F15、F16、F19、F21、F23、F24 | 事件总线、AgentRuntime、引擎接口、队列、追踪、治理 |

---

## 6. 实施顺序建议

1. **P0（第 1 阶段）**：F1 世界事件总线 → F2 感知上下文 → F3 世界推进引擎 → F5 行动/事件记忆沉淀 → F20 单聊事件注入（可并入 F2）→ 建立 F19 追踪规范（先定日志格式）。
2. **P1（第 2 阶段）**：F4 普通 NPC 参与通道 → F6 动态关系 → F7 目标/计划/反思 → F8 决策接 RAG → F9 暂停修复 → F10/F11 一致性 → F18 项目级记忆写入。
3. **P2（第 3 阶段）**：F13 引擎双向接口 → F14 模型分层路由 → F15 队列预算 → F17 大规模人群 → F12/F16/F21~F25 长期治理与规模化。

> 每项实施遵循项目既有节奏：后端 `mvn test` 全绿 + 前端 `npm run build` + dist→static→jar + 对接文档更新；新增能力默认开关关闭灰度上线。

---

## 7. 参考（联网实时信息）

- [The Forgetting Problem: When Unbounded Agent Memory Becomes a Liability](https://tianpan.co/blog/2026-04-12-the-forgetting-problem-when-agent-memory-becomes-a-liability)
- [Emergence World: Normative Drift & Cross-Contamination（持续世界模拟实证）](https://www.emergence.ai/blog/emergence-world-a-laboratory-for-evaluating-long-horizon-agent-autonomy)
- [Why Multi-Agent LLM Systems Fail（协调瓶颈/共享上下文/目标错配）](https://orq.ai/blog/why-do-multi-agent-llm-systems-fail)
- [AI Agents 2026 Guide（调试/过度工程/多智能体陷阱）](https://eitt.academy/knowledge-base/ai-agents-2026-guide-from-llm-to-multi-agent-systems)
- [How to Build Persistent NPC Memory（当前/历史/被取代事实）](https://mistscale.com/blog/npc-memory-current-historical-superseded-facts)
- [NPCs and AI: Persistent Memory & Dynamic Relationships](https://lanceblairvo.com/npcs-and-ai-in-persistent-worlds)
- [Agentic AI in Gaming: Modular Layers for Perception/Memory/Planning](https://www.gocodeo.com/post/agentic-ai-in-gaming-self-learning-npcs-and-dynamic-storytelling)
- [The NPC as Autonomous Agent — Game AI: Patterns, Not Engines](https://www.socratopia.app/library/game-ai-patterns-en/chapter-1)
- [Scalable AI for Open-World Games: Hierarchical NPC Budget Systems](https://ijetcsit.org/index.php/ijetcsit/article/view/743)
