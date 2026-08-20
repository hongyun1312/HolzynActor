# HolzynActor · 不自洽与潜在矛盾点清单 V1.0

> **文档类型**：逻辑自洽性评估（详细展开 + 可执行建议；不含详细 Java 代码）
> **日期**：2026-08-19 | **版本**：V1.0
> **基准**：以《HolzynActor_NPC自决策自推进_闭环评估与设计方案_V1.0.md》中 S-1~S-6 与 L 清单中的"一致性类"条目为基础展开，并补充本轮**代码核实的新发现**（标【新核实】）
> **关联**：`docs/设计文档/HolzynActor_缺失功能清单_v1.0.md` / `docs/设计文档/HolzynActor_六组件框架_设计文档_v1.0.md`
> **口径**：**不自洽/潜在矛盾 = 系统里"已有但互相矛盾/语义错误/行为与宣称不符"的地方**（区别于"缺失功能清单"= 系统还没有的能力）。每条含：现象 / 根因（含代码证据）/ 影响 / 修复建议 / 严重度 / 验证方式。
> **严重度定义**：**高** = 影响语义或数据正确性（用户可感知的错误行为）；**中** = 影响一致性或体验（跨模块口径不一）；**低** = 打磨/边界/可解释性。

---

## 1. 汇总表

| 编号 | 名称 | 严重度 | 相关缺失功能 | 对应六组件 |
|---|---|---|---|---|
| C-1 | 暂停语义分裂（普通 NPC 不受暂停约束） | 高 | F9 | 运行时/行动 |
| C-2 | 时间口径分裂（真实 / 游戏 / 场景快照） | 高 | F2/F11 | 感知/行动 |
| C-3【新核实】 | 单聊世界事件不进 AI 上下文（群聊却进） | 高 | F20 | 扮演/感知 |
| C-4【新核实】 | 项目级记忆"世界大事记"只读侧存在、写侧缺失 | 高 | F18 | 记忆/学习 |
| C-5 | 两条推进线并发（人群定时 + 世界模拟，无互斥） | 高 | F3/F9 | 运行时/行动 |
| C-6 | "活跃窗口=冻结" 与成本控制目标矛盾 | 高 | F3 | 运行时 |
| C-7 | 位置/场景四处不一致（角色/对话/地点表/居民） | 中 | F11 | 感知/行动 |
| C-8 | 世界事件注入目标粗糙（与地点/角色无关） | 中 | F10 | 感知 |
| C-9 | 人群跨多游戏小时只推进一次（粒度粗糙） | 中 | F3 | 行动 |
| C-10 | 新角色默认重要度=1 → 永远走程序化 | 中 | — | 思考/运行时 |
| C-11 | embedding 未配置时向量化率/知识可用性误导 | 中 | — | 记忆/感知 |
| C-12 | 特殊/普通 NPC 双轨割裂（表/服务/成员类型） | 中 | F4 | 扮演/行动 |
| C-13 | 关系 id=0 幽灵节点语义（名称兜底歧义） | 中 | F6 | 学习 |
| C-14 | 用量场景标签与实际场景不一致（事件生成记 dialog） | 中 | — | 用量（横切） |
| C-15 | 对话场景时间快照随创建固定，不随世界时钟 | 中 | F2/F11 | 扮演/感知 |
| C-16 | 记忆注入与对话历史/场景潜在重复 | 中 | F7 反思 | 记忆 |
| C-17 | 世界事件消息存 role=system，与"情境"语义混用 | 低 | F1 | 扮演/通信 |
| C-18 | 时间线"真实时间 vs 世界时间"双轨展示口径 | 低 | F1(game_time) | 通信 |
| C-19 | 程序化状态机无状态性（行为单一化、不感知事件） | 低 | F6/F7 | 行动/思考 |
| C-20 | SSE 断连/重建与取消策略未统一 | 低 | F19 | 运行时 |
| C-21 | 幂等边界（调度/事件/记忆重复风险） | 低 | F1/F3 | 通信/运行时 |
| C-22 | 角色/关系/场景在页面间显示口径不一（名称快照 vs 实时） | 低 | F6/F25 | 扮演/学习 |

---

## 2. 高严重度（影响语义或数据正确性）

### C-1 暂停语义分裂：普通 NPC 不受世界暂停约束【高】

- **现象**：用户暂停世界后，特殊 NPC 的行动计划（`ActionScheduledJob`）与世界模拟（`WorldSimulationJob`）都停止，但**普通居民仍在"行动"**。
- **根因（代码证据）**：`CrowdScheduledJob.advanceEnabledProjects` 只查 `actor_crowd_runtime.enabled=1`，**从不检查 `actor_world_clock.paused`**；而 `ActionScheduledJob.isWorldPaused` 与 `WorldSimulationJob.advanceProject` 都检查了 paused。
- **影响**：与"世界暂停=时间冻结"的语义直接矛盾；暂停期间居民 last_action 持续变化，时间线出现"冻结中的世界里居民还在动"的怪象。
- **修复建议**：`CrowdScheduledJob` 补暂停检查（一行级，最优先）；长期由 F3 WorldEngine 统一推进入口后自然消除。
- **验证方式**：暂停世界 → 等 5 分钟 → `actor_ordinary_npc.last_action` 不再变化（修复后）。

### C-2 时间口径分裂：真实时间 / 游戏时间 / 场景快照 三套并存【高】

- **现象**：同一时刻，系统里同时存在"真实时间"与"游戏时间"两种表述，且可能矛盾——深夜时 NPC 做着"白天该做的事"。
- **根因（代码证据）**：
  1. `ActionEngine.buildSituation` 用 `LocalDateTime.now()`（真实时间）——对话/事件触发的行动决策不知道游戏时间；
  2. `WorldSimulationJob` 用游戏时间（`WorldClockService.formatGameTime`）——同一套行动引擎两种时间来源；
  3. `actor_conversation.gameTimeText` 是**创建时快照**，不随世界时钟推进——对话越久，场景时间越"卡住"；
  4. 行动日志 `logTime` 用真实时间，`actor_event` 无 game_time（F1 前）。
- **影响**：NPC 行为、场景描述、时间线三者时间概念不一致；"夜晚"判断依赖来源不同结果不同。
- **修复建议**：决策/行动一律用游戏时间（感知组件 F2 统一输出 gameTime）；对话场景时间改为可随时钟刷新（或至少显示"世界时钟当前时刻"）；事件落 `game_time`（F1）。
- **验证方式**：造一条真实深夜触发的行动 → 决策情境显示游戏时间（而非真实时间）；长时间对话后场景时间与时钟一致。

### C-3【新核实】单聊世界事件不进 AI 上下文，群聊却进【高】

- **现象**：单聊中注入世界事件后触发 NPC"回应"，但回应内容与事件无关（答非所问）。
- **根因（代码证据）**：
  - `ChatService.buildMessages` 的消息循环只纳入 `role=user/assistant`（`if ("user".equals(m.getRole()) || "assistant".equals(m.getRole()))`），`type=event`（role=system）的世界事件消息**被排除**；
  - `GroupChatService.buildCharacterMessages` **包含**事件消息（`else if ("event".equals(m.getType()) ...)`，line 409/484）。
  - 即：单聊与群聊对"世界事件是否进入 AI 视野"处理不对称；单聊的事件回应生成时看不到事件原文（仅事后记忆抽取与行动评估间接接触）。
- **影响**：单聊里 NPC 对世界事件的反应完全脱节；事件→回应链路在单聊是断的。
- **修复建议**：`ChatService.buildMessages` 纳入 `type=event` 消息（作为情境段注入，或并入感知【当前所见】F2）；与 GroupChatService 对齐。
- **验证方式**：单聊注入事件 → SSE 生成回应 → 检查 AI 请求消息序列含事件文本 → 回复引用事件。

### C-4【新核实】项目级记忆"世界大事记"只读侧存在、写侧缺失【高】

- **现象**：对话注入的【世界大事记】段长期为空（除 .holzyn 导入的历史数据外）。
- **根因（代码证据）**：
  - `MemoryService.memoryContext` 读取 `findByProjectIdAndCharacterIdIsNullAndDeleted...`（项目级记忆，所有角色可见）——**读侧存在**；
  - 但 `MemoryService.saveMemory` 仅在 `extractAfterRound` 中被调用，且传的是**解析后的角色 ID（非 null）**；`WorldEvolutionService` 归档也只逐参与者写 `characterId=p.getCharacterId()`（line 1197）；`HolzynImportService` 仅在导入时写。
  - 即：**正常运行时没有任何代码写 characterId=null 的项目级记忆**。
- **影响**：宣称的"世界大事记（所有角色都知晓）"能力未真正实现；重大世界事件没有全项目共识载体。
- **修复建议**：F18——重大世界事件（source=world/evolution，重要度≥阈值）经 F1 事件总线/演化归档同时写一条项目级记忆。
- **验证方式**：产生一条重大世界事件 → `actor_memory` 出现 characterId=null 记录 → 任意角色对话注入出现【世界大事记】。

### C-5 两条推进线并发：人群定时 + 世界模拟无互斥【高】

- **现象**：同一项目可能被 `CrowdScheduledJob`（每 5 分钟，所有 enabled 项目）与 `WorldSimulationJob`（活跃项目）**同时推进**，双写 `state/last_action`。
- **根因（代码证据）**：两处都调用 `OrdinaryNpcService.scheduleProgrammatic*`；`CrowdScheduledJob` 无 `running` 标志，`WorldSimulationJob` 有内存 `running` 但只防自身重入，**两个任务之间无互斥**。
- **影响**：结果幂等（按小时重算）时影响小，但暂停语义（C-1）、预算（每 tick AI 上限）与时间线重复节点会受影响；未来叠加 AI 调度后会出现"同一小时两次决策"。
- **修复建议**：推进入口收敛到 F3 WorldEngine（单入口 + 项目级互斥锁）。
- **验证方式**：开启人群定时 + 触发世界模拟 → 观察同一 gameHour 是否出现两条调度记录/重复事件。

### C-6 "活跃窗口=冻结" 与成本控制目标矛盾【高】

- **现象**：没有对话的项目世界完全不推进；离线一周回来只补 1 天。
- **根因（代码证据）**：`WorldSimulationJob.scanAndSimulate` 只推进 `findDistinctProjectIdByLastMessageAtAfter(now-30min)`；`maxCatchUpHours=24` 单次封顶。
- **影响**：成本控制（不想为无人观看的世界烧 token）被实现成"无人互动=世界冻结"，与用户确认的"无人值守持续自推进"目标（P2）直接冲突；且"活跃"判定依赖会话消息时间，语义与"世界是否该转"无关。
- **修复建议**：改为"持续运行开关 + 预算控制"（F3）：`world_running=1` 就推进，成本用 `budget_per_tick`/频率节流达成，而不是用"冻结"达成。
- **验证方式**：无对话项目 running=1 → 世界持续推进；预算调 0 → 全部程序化推进（不冻结）。

---

## 3. 中严重度（影响一致性或体验）

### C-7 位置/场景四处不一致【中】

- **现象**：角色行动说"前往城东市场"，对话场景却是"城西酒馆"，居民在"灵材阁"，三处互不校验。
- **根因**：`actor_character.location`（自由串）、`actor_conversation.location`（快照）、`actor_world_location`（地点表）、`actor_ordinary_npc.location`（字典选取）四处独立，无统一模型。
- **影响**：感知（F2）/事件路由（F10）依赖位置一致性，四处分叉会导致"角色同时在多处"。
- **修复建议**：F11 位置归一（统一引用地点表 + 自由补充兜底 + 名称快照）。
- **验证方式**：角色移动后对话候选/感知在场与其 location 一致。

### C-8 世界事件注入目标粗糙【中】

- **现象**：世界模拟生成的事件只注入"最近更新的第一个会话"。
- **根因**：`WorldSimulationJob.simulate` 用 `conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId).stream().findFirst()`。
- **影响**：事件可能进错会话（角色不在场/地点不对）；非该会话角色不知晓；信息传播无地理/社会逻辑。
- **修复建议**：F10 事件带 location_id/character_ids（F1）→ 感知（F2）按可见性路由到在场/相关会话。
- **验证方式**：城西事件只进城西/在场角色的会话。

### C-9 人群跨多游戏小时只推进一次【中】

- **现象**：一次补算 24 游戏小时后，居民只有"第 24 小时的样子"，中间 23 小时的作息变化全部跳过。
- **根因**：`WorldSimulationJob.simulate` 对人群只调一次 `scheduleProgrammaticByGameHour(projectId, gameHourOfDay)`（取推进末小时）。
- **影响**：深夜→次日深夜，居民"瞬间跳变"；时间粒度粗糙。
- **修复建议**：F3 按游戏小时逐步推进，人群逐时推进。
- **验证方式**：跨 24h 推进 → `last_action` 出现中间时段状态（清晨行走/午休等）。

### C-10 新角色默认重要度=1 → 永远走程序化【中】

- **现象**：手动添加角色若用户不填重要度，`importance` 默认 1 < 3，该角色永远不走 AI 行动决策（对话/事件触发、世界模拟日边界均按 importance≥3 才 AI）。
- **根因**：`ActorCharacter.prePersist` 默认 importance=1；`ActionEngine.evaluateMembers`/`WorldSimulationJob` 都以 `importance>=3` 作为 AI 成本门槛。
- **影响**：新角色"看似正常"但行为全是程序化兜底，与文件解析角色（AI 判重要度）体验不一致；用户无感知。
- **修复建议**：手动创建角色时按是否主角给默认重要度（主角≥4/重要≥3），或在角色页显式提示"重要度影响 AI 投入"。
- **验证方式**：新建普通角色 → 查 importance 默认值 → 对话后是否有 after_dialog 行动评估日志。

### C-11 embedding 未配置时向量化率/知识可用性误导【中】

- **现象**：未配置 embedding 时，`vectorizeAll` 内部降级空数组不抛错，知识页"向量化率"显示 0，但 RAG 实际走文本降级可用——用户误以为知识不可用。
- **根因**：`KnowledgeService.vectorizeAll` 静默降级；`KnowledgeDocVO` 的向量化率口径未区分"未配置/失败/空"。
- **影响**：信息误导；排查困难。
- **修复建议**：把"未配置 embedding"与"向量化失败/进行中"分开提示；知识页给出"文本检索可用、向量化未配置"的说明。
- **验证方式**：无 embedding 配置 → 知识页提示明确 → 检索仍能返回（文本降级）。

### C-12 特殊/普通 NPC 双轨割裂【中】

- **现象**：特殊 NPC（actor_character）与普通 NPC（actor_ordinary_npc）两套表、两套服务、两套"行动"（ActionEngine vs 状态机）、会话成员只支持特殊 NPC。
- **根因**：历史演进（人群重构）形成双轨；`ConversationService`/`ChatService` 全部按 `characterId→actor_character` 建模。
- **影响**：普通 NPC 无法对话（L-03/L-09）、无法被特殊 NPC 感知其行动（F1 前）、脑力配置（六组件框架）无法统一。
- **修复建议**：F4（member 类型化）+ 统一 characterRef（special:{id}/crowd:{id}）+ F1（居民活动事件化）。
- **验证方式**：普通 NPC 可进会话；其行动在事件流/时间线可见。

### C-13 关系 id=0 幽灵节点语义歧义【中】

- **现象**：`actor_character_relation` 对"名称匹配不到角色"的关系以 id=0 + from_name/to_name 兜底存储（幽灵节点）；多个普通 NPC 或同名角色都落到 id=0。
- **根因**：关系表以 characterId 为主键语义，普通 NPC 不在 actor_character 表，只能名称兜底。
- **影响**：同一 name 的多个普通 NPC（不同项目/同项目重名）无法区分；关系归属/删除/统计以名称匹配有歧义。
- **修复建议**：关系端点支持 characterRef（crowd:{id}）作为正式端点 + 名称快照兜底展示；重名时优先 ref 匹配。
- **验证方式**：两个同名普通 NPC → 关系行能区分到具体 id。

### C-14 用量场景标签与实际场景不一致【中】

- **现象**：世界事件生成（`WorldEventService.generateByAi`）与时间线 AI 事件（`TimelineService.aiGenerateEvent`）的用量记录 scene 都是 `"dialog"`，与对话无关。
- **根因**：这两处沿用了对话的 scene 常量，未新增独立场景（如 `world_event`）。
- **影响**：AI 用量页"对话"类目混入事件生成消耗，统计失真。
- **修复建议**：新增 scene=`world_event` 并补中文标签（`UsageService.sceneName`）。
- **验证方式**：注入 AI 事件 → 用量明细场景=世界事件生成。

### C-15 对话场景时间快照随创建固定【中】

- **现象**：对话创建时把"当前世界时间"存为 `gameTimeText` 快照，之后对话再长，场景时间也不变。
- **根因**：`ConversationSceneDTO`/会话创建流程只写一次快照。
- **影响**：长对话中"世界已过去数日，场景还停在创建那天"；与 C-2 同源。
- **修复建议**：场景时间展示改为"创建时快照 + 世界时钟当前时刻"双信息，或提供"刷新场景时间"操作（配合 F2 感知）。
- **验证方式**：世界推进多日后对话头部场景时间显示当前世界时刻。

### C-16 记忆注入与对话历史/场景潜在重复【中】

- **现象**：对话窗口内刚说过的事实，既在历史里又可能被记忆 top-K 注入；项目级记忆与摘要也可能重复。
- **根因**：`ConversationContextService.buildContext` 注入记忆（top-K）与 `ChatService` 的历史窗口（HISTORY_WINDOW）**没有去重**；超窗摘要（conversationSummaryContext）与事实记忆内容也可能重叠。
- **影响**：上下文冗余、token 浪费、AI 可能复述。
- **修复建议**：注入时排除"历史窗口内已出现的记忆"（按文本重叠/时间窗过滤）；反思（F7）生成的洞察与底层事实间建立"已抽象"去重。
- **验证方式**：长对话中检查 AI 请求的 system 段与历史段无重复事实。

---

## 4. 低严重度（打磨/边界/可解释性）

### C-17 世界事件消息存 role=system，与"情境"语义混用【低】

- **现象**：`actor_message` 里 `type=event` 的消息 role=system，与对话组装的其他 system 消息（角色卡/感知/记忆）同列，语义上"事件"是情境而非系统指令。
- **根因**：沿用消息表单一结构，用 type 区分。
- **影响**：可读性/排查稍差；C-3 的直接修复需依赖 type 而非 role。
- **修复建议**：保持存储兼容，组装侧明确按 `type=event` 注入为"情境段"（F20）。
- **验证方式**：事件消息在历史/时间线正确归类。

### C-18 时间线"真实时间 vs 世界时间"双轨展示口径【低】

- **现象**：`TimelineService.aggregate` 节点用真实时间排序，另附 `gameTime`（由真实时间映射）；暂停/速率变化后，"世界时间"映射可能非单调（高倍率下多个真实分钟对应同一游戏时刻）。
- **根因**：时间线以 createdAt/logTime（真实）为主键，gameTime 是派生。
- **影响**：暂停期间/高倍率下时间线"游戏时间"并列/重复显示，观感困惑。
- **修复建议**：F1 事件落 `game_time` 后，时间线统一以 game_time 为主排序/分组（配合 L-07 时间线侧）。
- **验证方式**：暂停后产生的事件在时间线上 game_time 冻结、真实时间仍在变——展示口径一致。

### C-19 程序化状态机无状态性（行为单一化、不感知事件）【低】

- **现象**：`computeState` 是纯时段规则（0-5 rest/6-7 walk/…），无个体记忆、无计划、不感知事件——同一小时反复重算得到相同状态，长期运行居民行为单一化。
- **根因**：为省成本采用无状态规则；`last_action` 只存最新一次。
- **影响**：演示期可接受；与"活世界/个体感"目标有差距。
- **修复建议**：P1 起给普通 NPC 增加轻量"今日计划"（F7 精简版）+ 事件感知（F2/F10），脑力配置 light 档支持。
- **验证方式**：同日多次推进，居民行为有当日计划变化（而非完全重复）。

### C-20 SSE 断连/重建与取消策略未统一【低】

- **现象**：各 SSE 端点（对话/工作流/事件）断连处理各自实现，前端取消/重连策略未统一。
- **根因**：`ChatService` 有 alive/互斥，`WorldWorkflowController` 用 ExecutorService+SseEmitter，`ActionSseHub` 又一套。
- **影响**：用户刷新/切换页面时偶发"已中断"提示与残留 streaming 消息。
- **修复建议**：统一 SSE 生命周期工具（连接/取消/超时/重建），配合 F19 追踪。
- **验证方式**：对话中断刷新 → 残留 streaming 消息正确标记 failed、无卡死。

### C-21 幂等边界：调度/事件/记忆重复风险【低】

- **现象**：世界事件发布无去重键；`scheduleProgrammatic` 无 per-hour 幂等键；记忆抽取对"同轮对话"可能被懒检查+逐轮双触发。
- **根因**：无事件去重（event 无 hash/来源键）、调度只靠 last_schedule_at 近似、记忆靠文本去重兜底。
- **影响**：极端情况下事件/记忆重复（文本去重已兜住大部分记忆重复）。
- **修复建议**：F1 事件发布带 `source_key`（如 planId/hour）做幂等；调度记录 per-hour 状态。
- **验证方式**：重复触发同计划/同小时 → 事件/调度不重复。

### C-22 角色/关系/场景在页面间显示口径不一【低】

- **现象**：角色列表/拓扑/对话头部/记忆对"名称、头衔、位置"的展示来源不一（实时查询 vs 快照 vs 名称兜底），改名后历史快照与实时不一致。
- **根因**：多处各自查询/快照，无统一展示口径。
- **影响**：改名/改头衔后，旧对话/旧拓扑显示旧名（部分符合"历史快照"预期，部分造成困惑）。
- **修复建议**：明确"实体实时名 vs 历史快照名"的展示规则（配合 F25 声誉展示统一）。
- **验证方式**：角色改名后各页面展示口径一致且历史记录保留原名。

---

## 5. 修复优先级矩阵

| 优先级 | 条目 | 建议实施时点 |
|---|---|---|
| **P0（立即/随 P0 缺失功能）** | C-1（暂停检查一行修复）、C-2（统一游戏时间）、C-3（单聊事件注入）、C-6（冻结 vs 成本） | 随 F3/F2/F20 |
| **P1（一致性批次）** | C-4（项目级记忆写入）、C-5（推进收敛）、C-7（位置归一）、C-8（事件路由）、C-9（人群逐时）、C-10（重要度默认）、C-11（embedding 提示）、C-12（双轨）、C-13（ref 关系）、C-14（场景标签）、C-15（场景时间刷新）、C-16（记忆去重） | 随 F4/F6/F7/F9/F10/F11/F18 |
| **P2（治理/打磨）** | C-17~C-22 | 随 F1(game_time)/F19/F25 |

> 修复项与《缺失功能清单》的 F 编号互相对应（如 C-1→F9、C-2→F2/F11、C-3→F20、C-4→F18、C-5/C-6→F3、C-7→F11、C-8→F10、C-9→F3、C-12→F4、C-13→F6、C-15→F2、C-16→F7、C-18→F1 等）。

---

## 6. 与六组件框架的关联

- **高严重度项**多数是"**世界状态/时间/事件的一致性**"问题——正是六组件框架中**感知（统一世界视角）与运行时（WorldEngine 单入口）**要解决的；
- **C-3/C-17** 属**扮演组件**的消息组装口径（单聊/群聊不对称）；
- **C-4/C-16** 属**记忆组件**（写侧缺失、注入去重）；
- **C-13** 属**学习组件**（动态关系的身份基础）；
- 六组件设计文档落地后，上述不自洽点大多随 P0/P1 实现一并消除或显著缓解；**建议在实现每个组件时把对应 C 条目作为"验收回归项"**（如实现感知组件时验证 C-2/C-7/C-8）。

---

## 7. 参考（联网实时信息）

- [Why Multi-Agent LLM Systems Fail（共享上下文/协调/格式一致性）](https://orq.ai/blog/why-do-multi-agent-llm-systems-fail)
- [The Forgetting Problem（记忆管理失衡的量化证据）](https://tianpan.co/blog/2026-04-12-the-forgetting-problem-when-agent-memory-becomes-a-liability)
- [The NPC as Autonomous Agent — Game AI: Patterns, Not Engines（感知/决策/行动三环 bug 高发点）](https://www.socratopia.app/library/game-ai-patterns-en/chapter-1)
- [Emergence World（持续世界模拟的规范漂移实证）](https://www.emergence.ai/blog/emergence-world-a-laboratory-for-evaluating-long-horizon-agent-autonomy)
- [Role-Playing Agents Driven by Large Language Models（长对话人格漂移/一致性挑战）](https://arxiv.org/html/2601.10122v1)
