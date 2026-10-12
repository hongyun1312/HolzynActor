# HolzynActor 全境众生 · 浮生 —— 理论参考与借鉴来源 · V1.0

> **文档类型**：借鉴与溯源说明（对外参考用）
> **版本**：V1.0
> **用途**：记录 HolzynActor 在设计实现中参考、借鉴过的**公开论文与工程资料**，并逐条写清「我们从它借鉴了哪个理念或理论」，以及它落在本项目的哪个能力域。
> **口径与边界**：
> 1. **只记录已交付能力**背后的来源。预研中、尚未开发的方向（如世界资产、世界小说、社会真实性补全等）所涉文献**不在本文档内**。
> 2. 借鉴不等于照搬：本项目是**工程实现**，多数来源只提供了机制或视角，具体数值、阈值、流程均为本项目自行设计与标定。
> 3. 若条目末标注 `⚠️未核实`，表示出处信息（作者 / 年份 / 编号）在检索中未能完全确认。
> 4. 检索方式：全部条目经联网检索逐条对照 arXiv 编号 / 作者 / 年份 / DOI；**不凭记忆书写编号**。

**一句话**：一个想「让世界和角色都更真实」的工程，绕不开已有研究；这份清单既是致谢，也是自我约束——**我们借了什么，我们心里有数**。

---

## 一、记忆与检索

### 1. Generative Agents / Smallville —— 记忆流 · 反思 · 分层计划
- **来源**：Generative Agents: Interactive Simulacra of Human Behavior — Joon Sung Park, Joseph C. O'Brien, Carrie J. Cai, Meredith Ringel Morris, Percy Liang, Michael S. Bernstein（Stanford / Google）· 2023 · https://arxiv.org/abs/2304.03442
- **借鉴**：提出「记忆流」（观察 / 反思 / 计划全量追加）、周期性反思抽象、日→时→分递归计划，以及 `retrieval = 加权(近因 0.995/小时衰减 + 相关度余弦 + 重要度 LLM 1~10 打分)` 的三维检索评分。本项目借鉴其「记忆流 + 反思 + 分层计划」三元结构与同款三维加权检索。
- **落点**：③ 角色认知与记忆

### 2. Zep / Graphiti —— 时序知识图谱与「事实失效」
- **来源**：Zep: A Temporal Knowledge Graph Architecture for Agent Memory — Preston Rasmussen, Pavlo Paliychuk, Travis Beauvais, Jack Ryan, Daniel Chalef（Zep AI）· 2025 · https://arxiv.org/abs/2501.13956
- **借鉴**：用双时间轴（有效时间 + 摄入时间）与分层子图组织记忆，事实发生变化时把旧边**失效（invalidate）而不删除**，因此可以回答「某时刻何为真」。本项目借鉴其「失效而非覆盖」的时序语义，做世界记忆中的实体状态版本化更新。
- **落点**：② 世界运行与时间演化

### 3. 艾宾浩斯遗忘曲线 —— 记忆动态模糊
- **来源**：Über das Gedächtnis（英译 Memory: A Contribution to Experimental Psychology）— Hermann Ebbinghaus · 1885（英译 1913）· https://thedecisionlab.com/reference-guide/psychology/forgetting-curve
- **借鉴**：无强化时记忆保持量随时间近似指数衰减（`R = e^(-t/S)`），最初掉得最快、之后趋缓（后续文献亦有幂律重构）。本项目借鉴该衰减律给角色记忆做**动态模糊**——久未触及的记忆随之降精度、降权重，而不是硬删除。
- **落点**：③ 角色认知与记忆

### 4. RAG —— 参数外挂可更新的知识库
- **来源**：Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks — Patrick Lewis, Ethan Perez, Aleksandra Piktus, Fabio Petroni, Vladimir Karpukhin 等 · 2020 · https://arxiv.org/abs/2005.11401
- **借鉴**：把模型参数记忆与**可更新、可检索的非参数记忆**（稠密向量索引）结合，在知识密集任务上显著提升表现。本项目借鉴该范式搭建世界设定与人物知识卡的可检索知识库，让角色的知识可以随时增补而非重训。
- **落点**：③ 角色认知与记忆

### 5. Reflexion / Self-Refine —— 语言化反思闭环
- **来源**：Reflexion: Language Agents with Verbal Reinforcement Learning — Noah Shinn 等（NeurIPS 2023）· https://arxiv.org/abs/2303.11366 ；Self-Refine: Iterative Refinement with Self-Feedback — Aman Madaan 等（NeurIPS 2023）· https://arxiv.org/abs/2303.17651
- **借鉴**：把环境反馈转成**文字反思**存进情节记忆供后续尝试使用；或以「给反馈—改写」迭代自纠。本项目借鉴这一「语言化反思 + 情节记忆」闭环，做角色的行为复盘、洞察沉淀与叙事漂移抑制。
- **落点**：③ 角色认知与记忆

### 6. Contextual Retrieval —— 入库前补写引导上下文
- **来源**：Introducing Contextual Retrieval — Anthropic · 2024（报告 Contextual Embeddings 使 top-20 检索失败率下降 35%，叠加 Contextual BM25 后下降 49%）· https://www.infoq.com/news/2024/09/anthropic-contextual-retrieval
- **借鉴**：切块入库前先为每个块生成一句「它在整篇文档中处于什么位置」的引导上下文，再做 embedding 与词法索引，可显著降低检索失败。本项目借鉴为知识条目补写**标题锚词 / 引导句**，提升角色回忆与对话引用时的命中率。
- **落点**：③ 角色认知与记忆

### 7. ProactAgent / Agentic RAG —— 按需主动检索
- **来源**：Ask Only When Needed: Proactive Retrieval from Memory and Skills for Experience-Driven Lifelong Agents — Yuxuan Cai 等 · 2026 · https://arxiv.org/abs/2604.20572 ；Agentic Retrieval-Augmented Generation: A Survey on Agentic RAG · 2025 · https://arxiv.org/abs/2501.09136
- **借鉴**：把「**何时检索、检索什么**」变成显式的策略决策，而不是每步固定检索。本项目借鉴该「按需主动检索」思路——角色先判断「此刻是否需要想起什么」，需要时才发起定向检索，并带冷却。
- **落点**：③ 角色认知与记忆

---

## 二、情绪、需求与决策

### 8. PAD 情绪模型 + OCC 评鉴模型 —— 情绪怎么表示、怎么产生
- **来源**：Pleasure-Arousal-Dominance: A General Framework for Describing and Measuring Individual Differences in Temperament — Albert Mehrabian（Current Psychology 14:261-292）· 1996 · https://link.springer.com/article/10.1007/BF02686918 ；The Cognitive Structure of Emotions — Andrew Ortony, Gerald L. Clore, Allan Collins（Cambridge University Press）· 1988
- **借鉴**：PAD 用愉悦 / 唤醒 / 支配三个近独立维度连续刻画情绪状态；OCC 按「事件 / 行为 / 对象 × 目标 / 标准 / 喜好」的评鉴结构区分情绪类别，并主张**情绪由评鉴产生而非相反**。本项目借鉴 PAD 做连续情绪向量，借鉴 OCC 做「事件 → 评鉴 → 情绪」的因果生成。
- **落点**：④ 角色状态与情绪

### 9. 马斯洛需求层次 —— 低层需求压制高层需求
- **来源**：A Theory of Human Motivation — Abraham Maslow（Psychological Review）· 1943 · https://en.wikipedia.org/wiki/Maslow%27s_hierarchy_of_needs
- **借鉴**：需求存在优势层级，低层需求未满足时高层需求被压制。本项目借鉴这一「需求优势」判断，让生理需求在危险区**优先级高于一切日程与社交**，直接强制行动。
- **落点**：④ 角色状态与情绪

### 10. 双过程理论 —— 快判与深思
- **来源**：Thinking, Fast and Slow — Daniel Kahneman · 2011 · https://thedecisionlab.com/reference-guide/philosophy/system-1-and-system-2-thinking ；A Survey of Slow Thinking-based Reasoning LLMs · 2025 · https://arxiv.org/abs/2505.02665
- **借鉴**：System 1 快、自动、直觉；System 2 慢、费力、分析，二者的切换决定判断质量与偏误。本项目借鉴该双层结构，做成**「快判（规则 / 效用打分，低开销）+ 深思（AI 慢推理，高成本）」**的决策链，按情境复杂度切换。
- **落点**：③ 角色认知与记忆 · ④ 角色状态与情绪

### 11. 效用系统与动作选择 —— 候选打分与优先级抢占
- **来源**：Behavioral Mathematics for Game AI — Dave Mark（Charles River Media）· 2009 · https://en.wikipedia.org/wiki/Utility_system ；Computational Mechanisms for Action Selection（博士论文）— Toby Tyrrell · 1993 · https://en.wikipedia.org/wiki/Action_selection
- **借鉴**：效用系统用响应曲线把环境输入映射为各候选动作的归一化得分并取最高；动作选择研究系统比较了多种选择机制。本项目借鉴「多动作并行打分 + 优先级排序 + 阈值抢占」构建**策略优先级链**。
- **落点**：③ 角色认知与记忆 · ④ 角色状态与情绪

### 12. MUSE 元认知框架 —— 能力感知驱动策略选择
- **来源**：Competence-Aware AI Agents with Metacognition for Unknown Situations and Environments (MUSE) — Rodolfo Valiente, Praveen K. Pilly · 2024 · https://arxiv.org/abs/2411.13537
- **借鉴**：显式实例化元认知循环——持续自评在任务上的胜任度，并以此驱动**迭代式策略选择**，在未知环境中显著提升自调节能力。本项目借鉴「能力自评 → 策略切换」的闭环，让角色在没把握时保守、胸有成竹时进取。
- **落点**：③ 角色认知与记忆 · ④ 角色状态与情绪

### 13. 自我效能 —— 能力自评卡的理论依据
- **来源**：Self-efficacy: Toward a Unifying Theory of Behavioral Change — Albert Bandura（Psychological Review 84(2):191-215）· 1977 · https://pubmed.ncbi.nlm.nih.gov/847061
- **借鉴**：人对自身能否达成目标的**能力信念**，决定他选择何种行为、投入多少努力、遇到障碍能坚持多久。本项目借鉴该构念做角色的「能力自评卡」，让行动选择由**主观自评**而非硬编码阈值决定——允许自信，也允许谦逊。
- **落点**：④ 角色状态与情绪

---

## 三、人格、发展与遗传

### 14. Erikson 心理社会发展阶段 + 年龄相关决策差异
- **来源**：Childhood and Society（心理社会八阶段）— Erik H. Erikson（W. W. Norton）· 1950 · https://en.wikipedia.org/wiki/Erikson%27s_stages_of_psychosocial_development ；Age-related differences in adaptive decision making — Irwin P. Levin 等（Judgment and Decision Making）· 2007 · https://www.cambridge.org/core/journals/judgment-and-decision-making/article/agerelated-differences-in-adaptive-decision-making-sensitivity-to-expected-value-in-risky-choice/8DCC1D33966B83166F1A1829C5970477 ；Older adults make less advantageous decisions than younger adults — G. Fein 等 · 2007 · https://pubmed.ncbi.nlm.nih.gov/17445297
- **借鉴**：Erikson 给出各年龄段的中心冲突与核心需求；决策研究显示不同年龄段对预期价值、风险与收益 / 损失的敏感度不同。本项目借鉴前者做**全生命周期阶段门控**，借鉴后者让不同年龄段的角色使用不同的决策权重。
- **落点**：⑤ 角色生命历程与人格

### 15. 孟德尔遗传 + 表观遗传修饰
- **来源**：Versuche über Pflanzen-Hybriden（豌豆杂交实验，1866 年发表）— Gregor Johann Mendel · 1866 · https://en.wikipedia.org/wiki/Mendelian_inheritance ；Epigenetic inheritance mediated by histone lysine methylation — C. Huang 等 · 2013 · https://pmc.ncbi.nlm.nih.gov/articles/PMC3539360
- **借鉴**：孟德尔定律给出显 / 隐性等位基因的分离与自由组合规则；表观遗传说明在 DNA 序列不变的前提下，基因表达也可被修饰并传递。本项目借鉴前者做婚育性状与谱系推演，借鉴后者让**环境与经历对后代产生非序列性的性状偏置**。
- **落点**：⑤ 角色生命历程与人格

---

## 四、社会、扩散与网络

### 16. AgentSociety —— 大规模社会模拟与社会实验平台
- **来源**：AgentSociety: Large-Scale Simulation of LLM-Driven Generative Agents Advances Understanding of Human Behaviors and Society — Jinghua Piao 等 · 2025 · https://arxiv.org/abs/2502.08691
- **借鉴**：以「需求—计划—行为」链驱动万级智能体、跑出海量交互，并把模拟器本身当成社会实验平台（极化、信息传播、政策冲击等）。本项目借鉴其**需求驱动的行动链**与「**模拟器即社会实验平台**」的定位。
- **落点**：⑥ 社会关系与群体涌现

### 17. 创新扩散理论 + 信息级联 —— 消息为什么会被「口耳相传」
- **来源**：Diffusion of Innovations — Everett M. Rogers · 1962 · https://educationaltechnology.net/diffusion-of-innovations-theory ；A Theory of Fads, Fashion, Custom, and Cultural Change in Informational Cascades — Sushil Bikhchandani, David Hirshleifer, Ivo Welch（Journal of Political Economy 100(5):992-1026）· 1992 · https://ideas.repec.org/a/ucp/jpolec/v100y1992i5p992-1026.html
- **借鉴**：扩散理论给出采纳者的分层与扩散决定因素（相对优势、兼容性、复杂度、可试性、可观察性）；信息级联说明个体在观察他人行动后会理性地**忽略自身信息而跟随**。本项目借鉴这条扩散曲线与级联机制，驱动信息在角色网络中按「距离 × 速度」传播并沿途失真。
- **落点**：② 世界运行与时间演化 · ⑥ 社会关系与群体涌现

### 18. 弱关系的力量 —— 关系强度与桥接结构
- **来源**：The Strength of Weak Ties — Mark S. Granovetter（American Journal of Sociology 78(6):1360-1380）· 1973 · https://www.cs.cmu.edu/~jure/pub/papers/granovetter73ties.pdf
- **借鉴**：以互动时间、情感强度、亲密程度、互惠性四维定义关系强度，并指出**弱关系承担跨越集群的桥接功能**，新信息常经由弱关系而非强关系传播。本项目借鉴其强度定义与桥接视角构建关系网络与拓扑可视化。
- **落点**：⑥ 社会关系与群体涌现

---

## 五、仿真方法与可复现

### 19. ODD 协议 —— 仿真模型如何被别人复现
- **来源**：A Standard Protocol for Describing Individual-Based and Agent-Based Models — Volker Grimm 等（Ecological Modelling 198:115-126）· 2006 · http://www.sciencedirect.com/science/article/pii/S0304380006002043 ；The ODD Protocol ... A Second Update — Grimm 等（JASSS 23(2):7）· 2020 · http://jasss.soc.surrey.ac.uk/23/2/7.html
- **借鉴**：以 Overview / Design concepts / Details 三块结构标准化描述个体基与智能体基模型，使模型**可理解、可复现**。本项目借鉴该协议撰写实验与模型说明，让世界跑出来的结果能被外部核查。
- **落点**：⑧ 外部接入与质量保障

### 20. 动态 LOD —— 按观察强度而不是角色总数分配算力
- **来源**：Dynamic Level of Detail for Large Scale Agent-Based Urban Simulations — Laurent Navarro, Fabien Flacher, Vincent Corruble（AAMAS 2011, pp. 701–708）· 2011 · https://www.ifaamas.org/Proceedings/aamas2011/papers/C5_B67.pdf
- **借鉴**：不预设离散等级，而是按仿真上下文为每个智能体**实时决定最合适的表示层级**（行为 LOD + 动态切换 + 空间聚合），换来可观算力收益。本项目借鉴「按观察强度 / 影响力给每个角色分配算力档位」，让世界可以更大而仍能无人值守运行。
- **落点**：⑤ 角色生命历程与人格 · ⑧ 外部接入与质量保障

### 21. LLM-as-Judge 及其偏见 —— 谁来评、怎么防止自己评自己
- **来源**：Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena — Lianmin Zheng 等（NeurIPS 2023）· https://arxiv.org/abs/2306.05685 ；Self-Preference Bias in LLM-as-a-Judge — Koki Wataoka, Tsubasa Takahashi, Ryokan Ri（NeurIPS 2024 Workshop）· https://arxiv.org/abs/2410.21819
- **借鉴**：强模型裁判与人类偏好一致率可超 80%，但存在**位置偏见、冗长偏见与自我偏好偏见**。本项目借鉴其评测范式评估角色行为质量，并用去标识、位置打散、独立通道裁判来抵消上述偏见。
- **落点**：⑧ 外部接入与质量保障

---

## 六、效度批评与自我设限

> 这一节不是「借鉴」，而是**接受批评**。以下研究指出：语言流畅并不等于行为效度，微观层的缺陷会一路传播到宏观结论。本项目把这些批评写进设计原则——**不把 LLM 输出当作人类数据的替代品**。

### 22. AI Agents Alone Are Not (Yet) Sufficient for Social Simulation
- **来源**：Yiming Li, Dacheng Tao（Nanyang Technological University）· 2026 · https://arxiv.org/abs/2603.00113
- **要点**：角色扮演的「像」不等于行为效度；集体结果往往由**智能体与环境的共同动力学**决定，而不只是智能体之间的互动。本项目因此把环境（时间 / 季节 / 信息 / 资源可得性）与个体放在同一层建模，而不是只让角色互相聊天。
- **落点**：② 世界运行与时间演化 · ⑥ 社会关系与群体涌现

### 23. Fluency Fallacy 与「微观到宏观效度缺口」
- **来源**：From the Fluency Fallacy to the Micro-to-Macro Validity Gap: Opportunities and Pitfalls of LLMs in Social Simulation — Patrick Taillandier 等 · 2025 · https://arxiv.org/html/2507.19364
- **要点**：把「生成得流利」误当成「模拟得有效」是常见陷阱；微观层的偏差会在宏观结果上放大。本项目因此坚持**程序化兜底、统一护栏与可回放链路**：AI 失败要降级、要留痕，而不是被掩盖。
- **落点**：⑧ 外部接入与质量保障

### 24. LLM 社会模拟的可操作验证
- **来源**：Towards Operational Validation of LLM-Agent Social Simulations: A Replicated Study of a Reddit-like Technology Forum — Tomašević 等（EPJ Data Science 15(1):72）· 2025/2026 · https://link.springer.com/article/10.1140/epjds/s13688-026-00674-x
- **要点**：给出可操作的验证框架（多窗口重复仿真对比经验数据，考察活跃度、网络结构、话题覆盖、风格收敛等维度）。本项目借鉴其「**多轮重复 + 跨窗口对照**」的验证姿态，坚持每轮交付都带可复现证据。
- **落点**：⑧ 外部接入与质量保障

---

## 七、外部项目与工程资料（影响声明）

> 本节只作**影响声明**，不涉及理论主张；这些资料影响了本项目的工程做法或呈现方式。

| 资料 | 类型 | 影响 |
|---|---|---|
| Generative Agents 的公开参考实现（Smallville） | 开源实现 | 影响本项目「记忆流 / 反思 / 计划」在工程上的组织方式与调试视角 |
| The Sims 的动机（Motive）系统 —— Will Wright / Maxis · 2000 | 游戏设计 | 影响「需求值随时间衰减、由交互回补」的数值手感 |
| NEEDSIM Life Simulation（Unity 插件） | 商业插件 | 参考「需求 → 可用交互」的可配置规则思路 ⚠️未核实（作者 / 年份未能确认） |
| Anthropic Contextual Retrieval 技术报告 · 2024 | 工程报告 | 影响知识条目的引导句 / 锚词写法（见 §1 第 6 条） |
| 主流引擎与平台公开文档（Unity 客户端接入、HarmonyOS / ArkTS 开发文档等） | 框架文档 | 影响外部接入层（引擎接管、原生端适配）的实现依据 |
| 同类开源项目的 README / 文档组织方式 | 文档排版 | 仅影响本项目的文档叙述结构与排版，不含代码或理论借用 |

---

## 附：来源 → 能力域 速查

| 能力域 | 主要来源 |
|---|---|
| ② 世界运行与时间演化 | Zep / Graphiti（事实失效）· Rogers 扩散 + 信息级联 · 效度批评三篇 |
| ③ 角色认知与记忆 | Generative Agents · 艾宾浩斯 · RAG · Reflexion / Self-Refine · Contextual Retrieval · ProactAgent · 双过程 · 效用系统 · MUSE |
| ④ 角色状态与情绪 | PAD + OCC · 马斯洛 · 自我效能 · 双过程 · 效用系统 · MUSE |
| ⑤ 角色生命历程与人格 | Erikson + 年龄决策差异 · 孟德尔 + 表观遗传 · 动态 LOD |
| ⑥ 社会关系与群体涌现 | AgentSociety · 弱关系 · Rogers 扩散 + 信息级联 |
| ⑧ 外部接入与质量保障 | ODD 协议 · 动态 LOD · LLM-as-Judge 及偏见 · 效度批评三篇 |

---

> **最后一句**：这份清单会随着项目继续开发而增长。我们更愿意把「借了什么」写在明处——**站在别人的研究上，才有资格谈真实。**
