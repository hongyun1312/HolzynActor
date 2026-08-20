# HolzynActor · 技术设计文档 V1.1

> **代号**：HolzynActor（NPC 角色 AI 驱动模块）
> **版本**：V1.1（P0~P1-4 已落地 + exe 化重构方向已确认；V1.0 为 greenfield 设计完成态）
> **日期**：2026-08-12（V1.1；V1.0 为 2026-08-11）
> **运行时**：独立 Web 服务（后端 Java/Spring Boot + 前端 Vue 3），后续对接 UE5 生态层
> **部署**：CT110 容器，独立子域 `actor.holzyn.com`，前端由 HolzynWeb 控制台跳转进入
> **资源约束**：单人开发 + AI 辅助；AI 调用走云端 API（OpenAI 兼容协议，多供应商可配）
> **文档深度**：架构 + 工作流级 + 落地实现级（数据库表 / API 端点 / 页面清单 / JSON Schema 均到实现级）
> **关联文档**：`Holzyn全境_重构技术设计文档_V3.0.md`（总架构，八模块）、`shared/` 五契约 + 八 Schema（含 fauna 2 个规划）、`web/` HolzynWeb 主对接文档、生态层 M-F 系列设计、`actor/docs/HolzynActor_exe化重构说明_V1.0.md`（桌面化重构方案）

---

## 文档说明（与 V2.0 总架构的关系）

Holzyn 全境在 V3.0 中确立了**八模块**架构（natural / civil / orchestrator / web / desktop / shared / fauna / actor）。其中**第 7 模块 HolzynFauna（生态层）**先行定义（见 `Holzyn全境_生物生态层_HolzynFauna_设计文档_V1.0.md`）；本文档定义的 **HolzynActor** 为**第八个独立模块**，与 natural、civil 平级，并依赖第 7 模块 fauna 的产物契约：

- **定位**：为生态层（M-F 系列）生成的动物/角色世界提供 **NPC 角色 AI 驱动能力**——从世界观设定出发，程序化生成"符合身份的角色卡与知识库"，驱动角色进行**符合身份的对话**与**符合身份的行动**。
- **两级 NPC 体系**：**特殊型 NPC**（对剧情有作用的核心角色，走完整 AI 对话 + 行动驱动）与**普通型 NPC**（路人/群像，程序化驱动或整个 AI 集体操控）。
- **独立服务**：独立后端（Java/Spring Boot）+ 独立前端，部署于 CT110，独立子域 `actor.holzyn.com`；HolzynWeb 控制台作为**跳转入口**，两者通过 **Casdoor OIDC SSO** 打通身份。
- **UE5 对接（预留）**：前期以 Web 预览为主，不做 UE5 对接；设计上为后续对接生态层 M-F 与 orchestrator 预留**产物数据契约**（角色卡 JSON、行动决策 JSON、manifest.json）。
- **与 web 的关系**：数据上**复用 MySQL holzyn 库**（新增 `actor_` 前缀表，便于跨模块查询）；前端布局/风格**严格对齐 HolzynWeb**（抽取共享前端基建共用）。

> **重要**：HolzynActor 不改变 V3.0 八模块中既有七模块的任何契约与落地状态，仅作为**第 8 模块**叠加（第 7 模块 HolzynFauna 生态层详见其独立设计文档）。shared 契约（R<T> 响应、camelCase、遥测字段）继续作为本模块的规范源头。

---

## 一、项目定位与设计哲学

### 1.1 一句话定位

> **HolzynActor 是一个"世界观 → 角色 → 对话 → 行动"的 NPC AI 驱动模块：输入世界观与角色设定，产出可对话、可行动、有记忆的智能角色，并以 Web 控制台形式预览，为 UE5 生态层提供角色行为数据契约。**

### 1.2 核心目标

- **设定即角色**：通过结构化"世界观 + 角色档案（背景/经历/社会关系）"输入，自动生成**结构化角色卡**（Persona）与**对话系统 Prompt**，使 NPC 的对话与回答严格符合身份。
- **行动即角色**：在对话之外，驱动 NPC 依据身份与情境生成**行动决策**（结构化 JSON），形成决策日志、行动计划与时间线，呈现"角色真的在生活/做事"。
- **两级 NPC**：特殊型走完整 AI（对话 + 行动）；普通型走**批量生成 + 集体行动调度**（程序化状态机 / 低成本的集体 AI 决策），控制成本与性能。
- **对话体验对标一线模型对话框**：SSE 流式打字机输出、单聊/群聊/世界事件三种形态，参考豆包、DeepSeek 的交互范式。
- **知识库与记忆分阶段演进**：P0 设定注入 → P1 向量 RAG → P2 跨会话长期记忆，逐级逼近"有记忆的真 NPC"。
- **UE5 对接预留**：角色卡 / 行动决策 / 对话摘要均以可被 UE5 生态层消费的结构化契约产出，对接路径清晰。
- **统一身份**：Casdoor OIDC SSO，与 HolzynWeb 同一组织，跳转免登录、权限统一。

### 1.3 设计哲学

| 原则 | 含义 |
|---|---|
| **独立模块、独立部署** | 与 natural/civil 平级，独立后端 + 独立前端，CT110 独立容器与子域，不影响既有八模块 |
| **契约先行** | 复用 shared 统一契约（R<T> / camelCase / 遥测字段）；本模块自产出契约（角色卡/行动决策 JSON Schema）同样先行定义 |
| **OpenAI 兼容 + 多供应商** | Provider 抽象层 + 模型路由，先接一家（如 DeepSeek），管理后台可配置切换，不锁死供应商 |
| **结构化产物即契约** | 角色卡、行动决策均为结构化 JSON（带 Schema），既能驱动 Web 展示，又能被 UE5 消费 |
| **两级成本控制** | 特殊型（贵、少、完整 AI）与普通型（便宜、多、程序化/集体操控）分层，成本与性能可预期 |
| **分阶段渐进** | 对话（P1）→ 群聊/行动（P2）→ 普通型/知识库 RAG（P3）→ 长期记忆/UE5 对接（P4），每阶段可独立验收 |
| **前端共享基建** | 抽取布局壳/主题/公共组件为共享前端基建，HolzynWeb 与本模块共同引用，风格严格一致 |
| **统一 IAM** | Casdoor OIDC SSO，同一组织 holzyn，跨子域跳转免登录 |

### 1.4 明确排除清单（防范围蔓延）

- ❌ 前期（P0~P2）不做 UE5 对接/UE5 运行时逻辑（仅预留数据契约）
- ❌ 不做语音合成/语音识别、不做多模态（头像生成可预留，P4 后可选）
- ❌ 不做自研大模型、不做模型微调（微调列为远期课题）
- ❌ 不做真实世界 NPC 联网数据接入（世界观/角色全部由用户设定，程序化生成）
- ❌ 不做多人实时同屏（多用户共享同一角色状态为远期课题）
- ❌ 普通型 NPC 不做"每人一个完整 AI 实例"（成本不可控），采用批量 + 集体调度

---

## 二、系统总架构（八模块）

### 2.1 架构总览

HolzynActor 作为第八模块，插入 V3.0 架构图（第 7 模块 HolzynFauna 生态层 M-F1~F5 位于自然/文明层），与 HolzynWeb 通过 **SSO 跳转 + HTTP** 通信，与 MySQL holzyn 库共享存储。

```
                    ┌──────────────────────────────────────────┐
                    │  Casdoor（统一 IAM，OIDC / Bearer）        │
                    │  组织 holzyn：应用 holzyn-web / holzyn-actor│
                    └──────────────┬───────────────────────────┘
                                   │ 鉴权 / SSO（web 与 actor 共用）
      ┌──────────────┬─────────────┴───────────┬──────────────────┐
      ▼              ▼                         ▼                  ▼
┌────────────┐ ┌────────────┐ ┌─────────────────────┐ ┌─────────────────┐
│ HolzynWeb  │ │ HolzynActor│ │ HolzynOrchestrator  │ │ HolzynShared    │
│ 官网+控制台 │ │ NPC AI 驱动 │ │ UE5 编辑器 C++ 插件  │ │ 契约+Schema     │
│ (CT109)    │ │ (CT110)    │ │ UI/调度/导入/流式    │ │ 5契约+6Schema   │
└─────┬──────┘ └─────┬──────┘ └──────────┬──────────┘ └─────────────────┘
      │ SSO跳转/HTTP  │ 共享前端基建       │ HTTP 调度
      ▼              ▼                   ▼
┌───────────────────────────────────────────────────────────────────────┐
│  MySQL holzyn 库（共用）                                                │
│  业务表(web: 33 张) + actor_ 前缀表（本模块新增） + Redis(实时状态待接入) │
└───────────────────────────────────────────────────────────────────────┘
┌───────────────────────────────────────────────────────────────────────┐
│  HolzynNatural 自然层 / HolzynCivil 文明层（Python，设计完成）          │
│  生态层 M-F1~F5（生态区划/物种/动物模型/群聚放置/UE5打包）              │
└──────────────────────────────┬────────────────────────────────────────┘
                               ▼ 产物契约（角色卡/行动决策 JSON → UE5 消费）
┌───────────────────────────────────────────────────────────────────────┐
│  UE5 World Partition 世界（orchestrator 装配，HolzynActor 后期对接）    │
│  NPC Actor + 行为树 + DataLayer + PCG Spawner                            │
└───────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责与状态总览（新增一行）

| 模块 | 技术栈 | 职责 | 状态（2026-08-12） |
|---|---|---|---|
| **shared** | 契约文档 + JSON Schema | 定义 HTTP / 产物 / 坐标 / 鉴权 / 遥测契约 | ✅ P0 完成 |
| **web** | Spring Boot 4.1 + Vue 3.5 + Element Plus | 官网 + 门户 + 控制台 + 管理后台 + 用户中心 | ✅ CT109 上线 |
| **desktop** | Tauri 2.x + Rust + Vue 3 | 本地执行端 | ✅ P1.5 骨架 |
| **natural** | Python 3.13 + FastAPI + terrain-diffusion | 自然层生成 | 📐 设计完成 |
| **civil** | Python 3.13 + FastAPI + FLUX/TRELLIS | 文明层生成 | 📐 设计完成 |
| **orchestrator** | UE5 C++ 编辑器插件 | 编排/导入/流式 | 📐 设计完成 |
| **actor** | Java 21 + Spring Boot 4.1 + Vue 3.5 + Element Plus | **NPC AI 驱动（角色卡/对话/行动/普通型 NPC/知识库记忆）** | ✅ P0~P1-4 完成 + exe 化重构方向确认 |

### 2.3 通信方式

- **SSO 跳转**：HolzynWeb 控制台 → `actor.holzyn.com`，走标准 OIDC 流程（Casdoor 同一组织已有会话则免登录）。
- **同步控制**：HTTP REST（JSON，统一 `R<T>` 响应），前端 <-> actor 后端。
- **流式对话**：`SSE`（Server-Sent Events）实现打字机流式输出（参考豆包/DeepSeek 对话框）。
- **数据共享**：与 HolzynWeb 共用 MySQL `holzyn` 库（`actor_` 前缀表），跨模块查询（如用户、项目关联）不跨库。
- **遥测（预留）**：P3+ 可将对话/行动调用量上报 web 遥测通道（复用 `/telemetry/**` 契约）。

### 2.4 全局契约（对齐 shared）

- 统一响应：`R<T> = {code, message, data, error:{field, detail}}`；分页 `PageResult={list,total,page,size,totalPages}`。
- 字段命名：JSON 统一 camelCase（projectId/characterId/conversationId）。
- 鉴权：Casdoor OIDC 会话 Cookie（`actor.holzyn.com` 域）；`/api/**` 需登录、`/api/admin/**` 需 `ROLE_ADMIN`、`/ws/**`、`/telemetry/**` 按需放行。

---

## 三、模块总览（HolzynActor 功能矩阵）

### 3.0 模块划分（A- 前缀，与 M-N / M-C 平级）

| 模块 | 职责 | 产物 |
|---|---|---|
| **A-C1 项目与世界观管理** | 项目（作品）CRUD + 世界观设定输入（结构化字段 + 自由长文本，版本化） | world_setting 记录 |
| **A-C2 角色卡生成引擎** | 世界观 + 角色档案 → AI 扩写生成**结构化角色卡 JSON** + 渲染**对话系统 Prompt**；支持查看/编辑/重新生成/版本 | character_card.json + system_prompt |
| **A-C3 角色库管理** | 特殊型/普通型角色 CRUD、社会关系图（relation）、角色形象（头像上传/占位） | character 记录 + relation 图 |
| **A-C4 对话引擎** | 单聊/群聊/世界事件注入；上下文管理；SSE 流式输出；知识库与记忆接入 | 会话/消息记录 |
| **A-C5 行动引擎** | 依据人设 + 情境生成**行动决策 JSON**；决策日志、行动计划、时间线可视化；UE5 契约预留 | action_decision.json + 日志 |
| **A-C6 普通型 NPC** | 参数化批量生成路人；集体行动调度（程序化状态机 / 集体 AI 决策）；web 人群预览 | crowd 记录 + 群体行动日志 |
| **A-C7 知识库与记忆（分阶段）** | P0 设定注入 → P1 向量 RAG → P2 跨会话长期记忆 | knowledge_doc / memory 记录 |
| **A-C8 模型与供应商管理** | OpenAI 兼容多供应商配置、模型路由、用量统计（Admin） | model_provider 记录 |
| **A-C9 UE5 对接契约（预留）** | 角色卡/行动决策/对话摘要以 UE5 可消费契约导出；manifest.json 对齐统一产物契约 | actor_manifest.json（P4） |

### 3.1 页面功能矩阵（前端）

| 页面 | 路由 | 功能 | 阶段 |
|---|---|---|---|
| 登录/SSO | `/login` | Casdoor OIDC 跳转 + 本地会话 | P0 |
| 主页（项目列表） | `/` | 展示已创建项目卡片，点击进入对话页 | P1 |
| 设置页（项目创建/编辑） | `/project/new`、`/project/:id/settings` | 输入世界观 + 角色信息 → 一键生成角色卡 | P1 |
| 对话页 | `/project/:id/chat` | 左侧角色列表 / 中间对话流（SSE）/ 右侧角色信息 | P1（单聊）P2（群聊+事件） |
| 行动时间线页 | `/project/:id/actions` | 决策日志 / 行动计划 / 时间线 | P2 |
| 普通型人群页 | `/project/:id/crowd` | 批量生成表单 + 人群列表 + 群体行动动态 | P3 |
| 管理后台 | `/admin/*` | 模型供应商 / Prompt 模板 / 用量统计 / 全局配置 | P2 |

---

## 四、后端架构

### 4.1 技术栈

- **语言/框架**：Java 21 + Spring Boot 4.1.0 + Spring Security 7.1（OIDC Client，SSO 复用 HolzynWeb 已有模式）
- **Web**：Spring MVC + 异步 `SseEmitter`（SSE 流式对话）
- **持久层**：Spring Data JPA（`ddl-auto=update` 与 SQL 脚本双轨，对齐 HolzynWeb）+ MySQL 8.4（复用 holzyn 库）
- **AI 接入**：自研 OpenAI 兼容 HTTP 客户端（WebClient），Provider 抽象层 + 多供应商配置
- **缓存（可选，P3+）**：Redis 7.0（对话上下文热缓存、用量计数）
- **构建**：Maven；**部署**：单 JAR 合包（前端 dist 并入 static，对齐 CT109 模式）

### 4.2 包结构

```
actor/backend/src/main/java/com/holzyn/actor/
├── HolzynActorApplication.java
├── config/               # SecurityConfig(OIDC)、SseConfig、WebClientConfig、JacksonConfig(camelCase)
├── common/               # R<T> / PageResult / ErrorResponse / GlobalExceptionHandler / CurrentUserProvider
├── controller/
│   ├── common/           # HealthController、SpaForwardController
│   ├── project/          # ProjectController（项目+世界观）
│   ├── character/        # CharacterController、CharacterCardController、RelationController
│   ├── conversation/     # ConversationController、MessageController、SseController
│   ├── action/           # ActionController（决策/日志/时间线）
│   ├── crowd/            # CrowdController（普通型）
│   ├── knowledge/        # KnowledgeDocController（P1）
│   ├── admin/            # ModelProviderController、PromptTemplateController、UsageController
│   └── auth/             # AuthController（/api/auth/me）
├── service/
│   ├── project/          # ProjectService、WorldSettingService
│   ├── character/        # CharacterCardService（生成引擎，调 AI）
│   ├── conversation/     # ConversationService、SseService（流式编排）
│   ├── action/           # ActionEngine（行动决策生成+调度）
│   ├── crowd/            # CrowdService（批量生成+集体调度）
│   ├── knowledge/        # KnowledgeService、MemoryService（P2）
│   ├── ai/               # AiProviderRouter、DeepSeekProvider、OpenAiProvider（多供应商）
│   └── admin/            # ModelProviderService、PromptTemplateService
├── model/                # entity / dto / vo / converter（对齐 HolzynWeb 分层）
├── repository/           # JPA Repository
└── ws/                   # （预留）对话/行动实时推送通道
```

### 4.3 分层与关键设计

- **controller → service → repository** 三层，`model(entity/dto/vo/converter)` 数据分层，包名 `com.holzyn.actor`。
- **OIDC SSO**：`SecurityConfig` 标准 OIDC 客户端；`CustomOidcUserService` 登录同步到 `sys_user`（复用 HolzynWeb 用户表，跨模块共享用户体系）；`CurrentUserProvider` 会话优先 + 兜底（演示模式）。
- **R<T> 响应**：全局统一包装，与 shared 契约一致。
- **SSE 流式**：`POST /api/conversations/{id}/messages` 触发 AI 调用，`GET /api/conversations/{id}/stream` 以 `SseEmitter` 推送 token 流；前端 EventSource 消费。
- **AI Provider 抽象**：`AiProviderRouter` 按 `model_provider` 配置路由到具体 Provider（默认 OpenAI 兼容 Chat Completions 协议），支持流式/非流式、超时、重试、降级。

---

## 五、数据模型（复用 holzyn 库，`actor_` 前缀表）

> 采用 JPA `ddl-auto=update` 自动建表 + `actor/docs/sql/` 显式脚本三份双轨（对齐 HolzynWeb 的 V1.0/V1.1/V1.2 管理方式）：`V1.0__holzyn_actor_all_tables.sql`（17 张全量表）、`V1.1__actor_model_provider_add_user.sql`（provider 用户级化）、`V1.2__actor_character_add_detail.sql`（角色 detail 列）。所有表 `actor_` 前缀，主键自增，软删除字段 `deleted`（对齐 product/region 表约定）。

### 5.1 表清单

| 分组 | 表 | 说明 |
|---|---|---|
| 项目/世界观 | `actor_project` | 项目（作品），含世界观概要，userId 归属 |
| | `actor_world_setting` | 世界观设定（结构化字段 + 长文本），版本化 |
| 角色 | `actor_character` | 角色主表（type: special/common；status 等；detail 用户输入详细信息） |
| | `actor_character_card` | 结构化角色卡 JSON + 渲染后的 system_prompt，版本化 |
| | `actor_character_relation` | 角色社会关系图（from → to，关系类型） |
| 对话 | `actor_conversation` | 会话（单聊/群聊，mode: single/group） |
| | `actor_conversation_member` | 群聊成员表 |
| | `actor_message` | 消息（role: user/assistant/system；type: text/action/event；SSE 状态） |
| 行动 | `actor_action_plan` | 行动决策 JSON（动作/目标/对象/理由/优先级/状态） |
| | `actor_action_log` | 行动执行日志（时间线数据源） |
| 普通型 | `actor_crowd` | 普通型人群组（批量生成的参数与统计） |
| | `actor_crowd_member` | 普通型成员（参数化个体，非 AI 实例） |
| 知识/记忆 | `actor_knowledge_doc` | 知识库文档（P0 文本，P1 加向量字段） |
| | `actor_memory` | 长期记忆（P2，AI 摘要写入） |
| 平台 | `actor_model_provider` | 用户级 AI 模型 API 配置（baseUrl/apiKey 加密/模型/流式/优先级/默认/备注，user_id 归属） |
| | `actor_prompt_template` | Prompt 模板（角色卡生成模板 / 对话系统模板 / 群聊编排模板） |
| | `actor_usage_log` | AI 调用用量日志（模型/角色/token 数/耗时） |


> **增量变更（V1.1/V1.2，已执行）**：
> - **V1.1**（`actor_model_provider`）：新增 `user_id`（归属用户，默认 1=演示用户）、`is_default`（用户级默认标记，同用户互斥）、`remark`（备注）；`api_key_cipher` 由 VARCHAR(255) 放宽为 **TEXT**（AES-GCM 密文可能超长）；新增 `idx_provider_user` 索引。
> - **V1.2**（`actor_character`）：新增 `detail` LONGTEXT（用户自行输入的角色详细信息，角色卡生成的知识源）。

### 5.2 核心表结构（关键字段）

```
actor_project
  id BIGINT PK AI, user_id BIGINT, name VARCHAR(100), code VARCHAR(50),
  cover_url VARCHAR(255), summary TEXT, status TINYINT(0草稿/1已生成角色卡/2进行中),
  deleted TINYINT, created_at DATETIME, updated_at DATETIME

actor_world_setting
  id BIGINT PK AI, project_id BIGINT FK, version INT,
  name VARCHAR(100),                -- 世界观名称
  genre VARCHAR(50),                -- 题材（奇幻/科幻/都市/历史...）
  era VARCHAR(50),                  -- 时代背景
  geography TEXT,                   -- 地理/地图设定
  factions TEXT,                    -- 势力/阵营
  magic_system TEXT,                -- 规则体系（魔法/科技/规则）
  culture TEXT,                     -- 文化/风俗
  history TEXT,                     -- 历史背景
  free_text LONGTEXT,               -- 完整世界观自由文本（知识库注入源）
  status TINYINT, created_at, updated_at

actor_character
  id BIGINT PK AI, project_id BIGINT FK, type VARCHAR(10) (special/common),
  name VARCHAR(50), title VARCHAR(50), avatar_url VARCHAR(255),
  is_protagonist TINYINT, importance TINYINT,   -- 重要度（决定 AI 投入）
  status TINYINT, deleted TINYINT, created_at, updated_at

actor_character_card
  id BIGINT PK AI, character_id BIGINT FK, version INT,
  persona_json JSON,          -- 结构化角色卡（见 §九 Schema）
  system_prompt TEXT,         -- 渲染后的对话系统 Prompt
  source VARCHAR(20),         -- generated / manual / edited
  created_at, updated_at

actor_character_relation
  id BIGINT PK AI, project_id BIGINT, from_character_id BIGINT,
  to_character_id BIGINT, relation_type VARCHAR(50),  -- 亲属/师徒/敌对/...
  description VARCHAR(255), created_at

actor_conversation
  id BIGINT PK AI, project_id BIGINT, user_id BIGINT,
  mode VARCHAR(10) (single/group), title VARCHAR(100),
  world_event_enabled TINYINT,    -- 是否启用世界事件注入
  last_message_at DATETIME, created_at, updated_at

actor_conversation_member
  id BIGINT PK AI, conversation_id BIGINT, character_id BIGINT,
  join_time DATETIME

actor_message
  id BIGINT PK AI, conversation_id BIGINT, character_id BIGINT NULL,
  role VARCHAR(10) (user/assistant/system), type VARCHAR(10) (text/action/event),
  content LONGTEXT,               -- 正文（assistant 为最终落库文本）
  raw_stream LONGTEXT NULL,       -- SSE 流式原始增量（调试用，可清理）
  status VARCHAR(10) (streaming/done/failed),
  token_in INT, token_out INT, created_at

actor_action_plan
  id BIGINT PK AI, character_id BIGINT, conversation_id BIGINT NULL,
  action_json JSON,               -- 行动决策 JSON（见 §十一 Schema）
  trigger_type VARCHAR(20) (after_dialog/scheduled/event/manual),
  status VARCHAR(20) (planned/executing/done/cancelled),
  planned_time DATETIME, executed_at DATETIME, created_at, updated_at

actor_action_log
  id BIGINT PK AI, character_id BIGINT, plan_id BIGINT NULL,
  summary VARCHAR(255), detail TEXT, log_time DATETIME, created_at

actor_crowd
  id BIGINT PK AI, project_id BIGINT, name VARCHAR(100),
  config_json JSON,               -- 批量生成参数（数量/职业分布/密度）
  member_count INT, status TINYINT, created_at, updated_at

actor_crowd_member
  id BIGINT PK AI, crowd_id BIGINT, name VARCHAR(50),
  profile_json JSON,              -- 参数化个体档案（非 AI 实例）
  state VARCHAR(20), last_action VARCHAR(255), updated_at

actor_knowledge_doc
  id BIGINT PK AI, project_id BIGINT, character_id BIGINT NULL,
  title VARCHAR(100), content LONGTEXT,
  embedding JSON NULL,            -- P1 向量（或改存向量库）
  status TINYINT, created_at, updated_at

actor_memory
  id BIGINT PK AI, character_id BIGINT, kind VARCHAR(20) (summary/fact),
  content TEXT, importance TINYINT, created_at, updated_at

actor_model_provider
  id BIGINT PK AI, name VARCHAR(50), base_url VARCHAR(255),
  api_key_cipher VARCHAR(255),    -- 加密存储，禁止明文入库
  model VARCHAR(100),             -- 默认模型名
  supports_stream TINYINT, priority INT, enabled TINYINT, created_at, updated_at

actor_prompt_template
  id BIGINT PK AI, code VARCHAR(50),   -- character_card_gen / dialog_system / group_orchestrator / world_event
  name VARCHAR(100), template TEXT,    -- 占位符 {{world_setting}} {{character_json}} ...
  version INT, enabled TINYINT, created_at, updated_at

actor_usage_log
  id BIGINT PK AI, user_id BIGINT, character_id BIGINT NULL, provider_id BIGINT,
  model VARCHAR(100), scene VARCHAR(20) (card_gen/dialog/action/crowd),
  token_in INT, token_out INT, duration_ms INT, cost DECIMAL(10,4) NULL,
  created_at
```

> 用户体系（`sys_user`）、任务/项目（`task`/`user_project`）等复用 HolzynWeb 既有表；跨模块查询（如按 userId 拉取项目）不跨库，均为同一 holzyn 库。

---

## 六、HTTP API 设计（对齐 shared 契约）

> 统一 `R<T>` 响应；`/api/**` 需登录（OIDC 会话）、`/api/admin/**` 需 `ROLE_ADMIN`；`/api/auth/me` 暴露用户信息供前端判断权限与跳转。

### 6.1 端点总表

| 分组 | 方法 | 端点 | 用途 |
|---|---|---|---|
| 健康 | GET | `/api/health` | 健康检查 |
| 认证 | GET | `/api/auth/me` | 当前用户（SSO 后身份） |
| 模型 API | GET | `/api/model-apis` | 用户级模型 API 列表（Key 脱敏） |
| | POST | `/api/model-apis` | 新增 API 配置（apiKey 必填，AES-256-GCM 加密入库） |
| | PUT | `/api/model-apis/{id}` | 编辑（apiKey 空=保持原 Key） |
| | DELETE | `/api/model-apis/{id}` | 删除 API 配置（归属校验） |
| | PUT | `/api/model-apis/{id}/default` | 设为用户默认（同用户互斥） |
| | GET | `/api/model-apis/default` | 当前默认 API |
| | POST | `/api/model-apis/test` | 未保存前连通性测试（明文 Key 不入库） |
| | POST | `/api/model-apis/{id}/test` | 已保存配置连通性测试（解密 Key） |
| 项目 | GET/POST | `/api/projects` | 项目列表/创建 |
| | GET/PUT/DELETE | `/api/projects/{id}` | 项目详情/编辑/删除 |
| | GET/POST | `/api/projects/{id}/world-setting` | 世界观设定读取/保存（版本化） |
| | POST | `/api/projects/{id}/generate-cards` | **一键为该项目全部角色生成角色卡（AI）** |
| 角色 | GET/POST | `/api/projects/{id}/characters` | 角色列表/新增 |
| | GET/PUT/DELETE | `/api/characters/{id}` | 角色详情/编辑/删除 |
| | POST | `/api/characters/{id}/generate-card` | **单角色生成/重新生成角色卡（AI）** |
| | GET | `/api/characters/{id}/card` | 角色卡最新版本（persona_json + system_prompt） |
| | GET | `/api/characters/{id}/card/versions` | 角色卡版本历史 |
| | PUT | `/api/characters/{id}/card` | 手动编辑角色卡 |
| | GET/POST | `/api/characters/{id}/relations` | 社会关系图读取/维护 |
| 对话 | POST | `/api/projects/{id}/conversations` | 创建会话（mode: single/group） |
| | GET | `/api/conversations/{id}` | 会话详情（含成员/标题） |
| | GET | `/api/projects/{id}/conversations` | 会话列表 |
| | GET | `/api/conversations/{id}/messages` | 历史消息（分页） |
| | POST | `/api/conversations/{id}/messages` | **发送消息（触发 AI 对话，返回 R<T>）** |
| | GET | `/api/conversations/{id}/stream` | **SSE 流式对话通道（EventSource 消费）** |
| | DELETE | `/api/conversations/{id}` | 删除会话（级联删消息/成员） |
| | POST | `/api/conversations/{id}/world-event` | **手动注入世界事件** |
| 行动 | GET | `/api/characters/{id}/actions` | 行动决策列表 |
| | POST | `/api/characters/{id}/actions/trigger` | **手动触发一次行动决策（AI）** |
| | GET | `/api/characters/{id}/actions/timeline` | 行动时间线（决策+日志聚合） |
| | GET | `/api/characters/{id}/action-logs` | 行动日志 |
| 普通型 | GET/POST | `/api/projects/{id}/crowds` | 人群组列表/创建（批量生成） |
| | GET | `/api/crowds/{id}` | 人群组详情（成员/统计） |
| | POST | `/api/crowds/{id}/schedule` | **触发集体行动调度（AI/程序化）** |
| 知识库 | GET/POST/PUT | `/api/projects/{id}/knowledge-docs` | 知识文档 CRUD（P1 后入向量） |
| 管理 | GET/POST/PUT | `/api/admin/model-providers` | 模型供应商配置 |
| | GET/POST/PUT | `/api/admin/prompt-templates` | Prompt 模板管理 |
| | GET | `/api/admin/usage` | AI 用量统计 |
| 通知(占位) | GET | `/api/notifications` | 通知列表（P4 前空数据占位，消除前端铃铛 404） |
| | GET | `/api/notifications/unread-count` | 未读数（P4 前恒 0） |
| | PUT | `/api/notifications/{id}/read`、`/api/notifications/read-all` | 标记已读（占位） |
| 遥测(预留) | POST | `/telemetry/task\|progress\|result` | 对话/行动任务上报（P3+ 接入） |

### 6.2 SSE 流式对话流程（核心链路）

```
前端                     actor 后端                       AI Provider
 │  POST /conversations/{id}/messages    │                       │
 │ ────────────────────────────────────► │  1.读会话/成员/上下文 │
 │                                      │  2.组装对话 prompt     │
 │                                      │  +知识库检索(P1)       │
 │                                      │  3.异步调 Provider     │
 │                                      │ ───────────────────►  │
 │  GET /conversations/{id}/stream (SSE) │ ◄── token 流 ───────  │
 │ ◄──── SseEmitter 推送 token ────────  │                       │
 │ ◄──── event: done ──────────────────  │  4.落库 assistant 消息│
 │ ◄──── 若产生行动 → event: action ───  │  5.行动引擎评估        │
 │                                      │                       │
 │  （前端 EventSource 消费，打字机渲染）  │                       │
```

- 消息先落库（status=streaming）→ SSE 推送增量 → 完成后更新 status=done 并回填 content。
- 行动触发：对话完成后，行动引擎按规则（高重要度角色/群聊事件）异步生成行动决策，SSE 以 `action` 事件推送前端刷新时间线。
- 失败降级：Provider 超时/报错 → SSE `error` 事件 + 消息 status=failed，前端可重试。

---

## 七、前端架构

### 7.1 共享前端基建（关键决策）

**抽取一套共享前端基建**（与 HolzynWeb 共用），保证"布局与风格严格相同"，且为 natural/civil 等后续模块复用：

| 共享内容 | 说明 |
|---|---|
| 布局壳 | `ConsoleLayout` / `AdminLayout` / 顶栏 / 侧栏 / 底部（从 HolzynWeb 抽象为共享组件） |
| 主题 | `styles/variables.css`（配色/间距/圆角/字号变量）+ `styles/global.css` |
| 公共组件 | `AvatarMenu` / `NotificationBell` / 通用表格 / 分页 / 空状态 / Markdown 渲染 |
| API 基建 | axios 实例（R<T> 解包 / 401 跳登录）、`api/index.js` 封装规范 |
| 路由骨架 | 路由守卫（未登录跳 `/login`）、控制台/管理后台路由模式 |

**落地方式**：初期以 **monorepo 共享目录**（`shared-web/` 包，HolzynWeb 与本模块通过本地依赖/复制同步引用）实现；稳定后抽为独立 npm 包 `@holzyn/web-ui`。HolzynWeb 侧逐步迁移到共享基建（先迁移布局壳与主题，再迁移公共组件，避免一次性大改动）。

### 7.2 页面结构与路由

```
actor/frontend/src/
├── main.js / App.vue / router/index.js / store/index.js（useAuthStore，复用共享）
├── api/                      # 本模块 API 封装（见 §六）
├── layouts/                  # 复用共享 ConsoleLayout/AdminLayout
├── views/
│   ├── LoginView.vue         # SSO 跳转 + 演示模式直进（对齐 HolzynWeb LoginView）
│   ├── HomeView.vue          # 项目卡片列表（主页）
│   ├── project/
│   │   ├── ProjectSettings.vue   # 设置页：世界观 + 角色信息输入 + 一键生成
│   │   ├── ChatView.vue          # 对话页：左角色 / 中对话 / 右角色信息
│   │   ├── ActionTimeline.vue    # 行动时间线页
│   │   └── CrowdView.vue         # 普通型人群页
│   └── admin/
│       ├── ModelProviderAdmin.vue  # 模型供应商管理
│       ├── PromptTemplateAdmin.vue # Prompt 模板管理
│       └── UsageAdmin.vue          # 用量统计
└── data/                     # 静态枚举（题材/关系类型/行动类型）
```

### 7.3 关键页面设计

**① 设置页（P1，核心入口）** `ProjectSettings.vue`
- 世界观区：结构化字段（题材/时代/地理/势力/规则/文化/历史）+ 大文本自由输入。
- 角色区：动态添加角色（姓名/头衔/类型/一句话简介/详细背景/经历/社会关系，可粘贴长文本）。
- **一键生成**按钮 → 调 `/generate-cards`，逐角色流式/分批生成角色卡，实时显示每个角色的生成状态（生成中/成功/失败可重试）。
- 生成后进入角色卡预览：结构化卡片（可展开字段）+ 渲染后的 system_prompt（可复制/手动编辑/重新生成）。

**② 主页（P1）** `HomeView.vue`
- 项目卡片网格：封面、名称、角色数、状态（草稿/已生成/进行中）、最近更新时间。
- 新项目入口 → 设置页。

**③ 对话页（P1 单聊 / P2 群聊）** `ChatView.vue`
- **左侧**：角色列表（头像/姓名/重要度标记/在线状态），可切换"单聊/群聊/世界事件"模式；群聊模式可勾选多个角色。
- **中间**：对话流（参考豆包/DeepSeek 对话框）——气泡消息、SSE 打字机效果、流式游标、消息旁操作（复制/重试）、世界事件以特殊样式卡片插入、行动以"行动卡"样式插入（点击跳时间线）。
- **右侧**：当前角色信息面板——头像 + 角色卡摘要（身份/性格/背景/经历/社会关系/说话风格/禁忌），顶部可"查看完整角色卡 / 生成行动 / 管理关系"。
- 底部：输入框（Enter 发送，Shift+Enter 换行）+ 快捷操作（注入世界事件 / 触发行动）。

**④ 行动时间线页（P2）** `ActionTimeline.vue`
- 时间线视图（纵向）：行动决策卡（动作/目标/对象/理由/优先级/状态）+ 行动日志节点。
- 筛选：按角色 / 按时间 / 按状态；可手动触发新行动决策。

**⑤ 普通型人群页（P3）** `CrowdView.vue`
- 批量生成表单（数量/职业分布/活动区域/密度）→ 生成人群组。
- 人群概览（总数/职业分布图表）+ 成员列表（参数化档案）。
- 群体行动动态流（集体调度的日志），可一键触发"集体行动调度"。

---

## 八、AI 接入层（OpenAI 兼容 + 多供应商）

### 8.1 Provider 抽象

```
AiProviderRouter（按 model_provider 优先级路由）
   ├── OpenAiCompatibleProvider（默认，OpenAI Chat Completions 协议）
   │     ├── chatCompletion(messages, params, stream) → 流式/非流式
   │     ├── 协议：POST {base_url}/chat/completions
   │     │     body: { model, messages, stream:true, temperature, max_tokens }
   │     │     SSE 解析：data: {choices:[{delta:{content}}]}
   │     └── 能力：超时 / 指数退避重试 / 降级到备选 Provider
   └── （预留）其他协议适配器（如 Anthropic、Gemini），后续按需
```

### 8.2 关键设计

- **配置驱动**：供应商信息存 `actor_model_provider`（Admin 可配），`api_key` 加密存储（AES，密钥环境变量注入），禁止明文。
- **模型路由**：按场景（card_gen/dialog/action/crowd）可配置不同模型——如对话用 DeepSeek-Chat、角色卡生成用更强模型、普通型用低成本模型。
- **流式统一**：Provider 返回 `Flux<String>` token 流，`SseService` 转发到前端 SSE；非流式场景（行动决策/角色卡生成）用普通请求 + JSON 结构化输出。
- **结构化输出**：角色卡生成、行动决策生成强制 `response_format: {type:"json_object"}`（OpenAI 兼容支持）或"输出 JSON 包裹"提示词兜底，并做 **JSON Schema 校验 + 失败重试**。
- **用量与成本**：每次调用写 `actor_usage_log`，Admin 页统计（token/耗时/成本），为"普通型低成本模型"策略提供数据支撑。
- **降级链**：主 Provider 失败 → 备选 Provider → 返回友好错误；对话可降级为非流式一次性返回。

---

## 九、角色卡与 Prompt 工程

### 9.1 角色卡 JSON Schema（A-C2 核心产物）

```jsonc
{
  "name": "character_card",
  "type": "object",
  "required": ["identity", "personality", "speechStyle", "knowledge"],
  "properties": {
    "identity": {                 // 身份
      "type": "object",
      "required": ["name", "title", "species", "occupation", "affiliation"],
      "properties": {
        "name": {"type": "string"}, "title": {"type": "string"},
        "species": {"type": "string"}, "occupation": {"type": "string"},
        "affiliation": {"type": "string"}, "age": {"type": "integer"}
      }
    },
    "personality": {              // 性格
      "type": "object",
      "properties": {
        "traits": {"type": "array", "items": {"type": "string"}},
        "values": {"type": "array", "items": {"type": "string"}},
        "quirks": {"type": "array", "items": {"type": "string"}}
      }
    },
    "background": {"type": "object",  // 背景与经历
      "properties": {
        "history": {"type": "string"},
        "keyEvents": {"type": "array", "items": {"type": "string"}},
        "wounds": {"type": "array", "items": {"type": "string"}},   // 心结/创伤
        "goals": {"type": "array", "items": {"type": "string"}}
      }},
    "relations": {"type": "array",    // 社会关系
      "items": {
        "type": "object",
        "properties": {
          "with": {"type": "string"}, "type": {"type": "string"},
          "attitude": {"type": "string"}, "notes": {"type": "string"}
        }
      }},
    "speechStyle": {"type": "object", // 说话风格
      "properties": {
        "tone": {"type": "string"}, "vocabulary": {"type": "string"},
        "catchphrases": {"type": "array", "items": {"type": "string"}},
        "taboos": {"type": "array", "items": {"type": "string"}}
      }},
    "knowledge": {"type": "object",   // 知识边界（会什么/不知道什么）
      "properties": {
        "knows": {"type": "array", "items": {"type": "string"}},
        "notKnows": {"type": "array", "items": {"type": "string"}}
      }},
    "behaviorPatterns": {"type": "array", "items": {"type": "string"}}  // 行为模式（行动驱动用）
  }
}
```

### 9.2 角色卡生成流程

```
用户填写（世界观 + 角色简介/背景/经历/社会关系）
        │
        ▼
CharacterCardService 组装生成模板（actor_prompt_template.code=character_card_gen）
        │  输入：{{world_setting}} + {{character_input}} + 输出Schema说明
        ▼
AI 调用（json_object 结构化输出）→ JSON Schema 校验 → 失败重试(≤2) 
        │
        ▼
写入 actor_character_card（version 自增）＋ 渲染对话 system_prompt（dialog_system 模板）
        │
        ▼
前端角色卡预览（可查看/编辑/重新生成）→ 版本历史保留
```

- **可编辑**：用户可直接修改 persona_json 任一字段，或编辑渲染后的 system_prompt（手动模式 source=manual）。
- **重新生成**：改世界观/角色信息后一键重新生成（新版本，旧版本保留可回溯）。
- **批量**：`/generate-cards` 逐角色串行/分批调用，前端展示每个角色生成进度。

### 9.3 对话系统 Prompt 渲染（dialog_system 模板）

```
你是【{{character.name}}】，【{{world_setting.name}}】世界的【{{title}}】。
── 身份 ──
{{identity 摘要}}
── 性格 ──
{{personality 摘要}}
── 背景与经历 ──
{{background 摘要}}（含 goals / wounds）
── 社会关系 ──
{{relations 摘要}}
── 说话风格 ──
{{speechStyle 摘要}}；禁止说：{{taboos}}
── 知识边界 ──
知道：{{knows}}；不知道/绝不假装知道：{{notKnows}}
── 行为准则 ──
{{behaviorPatterns 摘要}}
── 世界设定（注入）──
{{world_setting.free_text 截断注入 / P1 改为知识库检索 top-k}}
── 角色须知 ──
1. 始终保持角色身份与世界观一致性，禁止跳出角色。
2. 不知道的事明确表示不知道，不得编造。
3. 回复使用角色说话风格，长度贴合角色身份与情境。
```

### 9.4 群聊 / 世界事件 Prompt 编排（P2）

- **群聊**：`group_orchestrator` 模板——系统提示词含全体成员角色卡摘要 + 发言人规则（AI 决定下一发言人或按指定），按轮次将"其他角色上一条发言"作为上下文输入当前角色，实现各角色按人设回应。
- **世界事件**：`world_event` 模板——以叙述者身份生成事件描述（时间/地点/发生了什么/对在场角色的影响），随后注入相关角色 prompt，触发符合身份的回应与行动。

---

## 十、对话引擎（A-C4）

### 10.1 上下文管理

- **滑动窗口 + 摘要**：对话过长时，将早期轮次压缩为摘要（AI 生成 scene 摘要，`scene:summarize`），保留最近 N 轮完整消息 + 全局摘要，控制 token 成本。
- **人设稳定注入**：system_prompt（角色卡渲染）始终在窗口内，不被滑动裁掉。
- **知识库注入**（P1）：窗口顶部注入检索到的 top-k 知识片段（`{{knowledge_context}}`）。

### 10.2 三种对话形态

| 形态 | 说明 | 编排要点 |
|---|---|---|
| 单聊 | 玩家 ↔ 单角色 | 直接注入该角色 system_prompt |
| 群聊 | 玩家 + 多角色同场 | 全体成员摘要入 system；每轮按发言人规则调用对应角色，前文含其他角色发言 |
| 世界事件 | 叙述者事件注入 | 先生成事件（world_event 模板）→ 事件卡片入消息流 → 触发在场角色回应/行动 |

### 10.3 SSE 流式与落库

- 触发：`POST /messages`（写 user 消息 + 建 assistant 占位 status=streaming）→ `GET /stream` SSE 推送。
- 完成后：回填 content、status=done、记录 token 用量；前端保存后刷新即可。
- 失败：status=failed + SSE `error` 事件，前端支持重试。

### 10.4 与行动引擎联动

- 对话完成后，**高重要度角色**或**世界事件后**触发行动评估（见 §十一），行动决策以消息流内"行动卡"呈现，并写入时间线。

---

## 十一、行动引擎（A-C5）

### 11.1 行动决策 JSON Schema（核心产物，预留 UE5 契约）

```jsonc
{
  "name": "action_decision",
  "type": "object",
  "required": ["type", "action", "target", "reason", "urgency"],
  "properties": {
    "type": {"enum": ["move", "interact", "speak", "trade", "fight", "flee",
                       "help", "schedule", "rest", "custom"]},
    "action": {"type": "string", "description": "动作描述，如：前往市场购买药材"},
    "target": {"type": "string", "description": "目标对象/地点，如：城东市场"},
    "params": {"type": "object", "description": "动作参数（如 move: {to:{x,y}}）"},
    "reason": {"type": "string", "description": "符合身份的决策理由"},
    "urgency": {"type": "integer", "minimum": 1, "maximum": 5},
    "duration": {"type": "integer", "description": "预计耗时(分钟)"}
  }
}
```

### 11.2 行动生成与调度

```
触发源
 ├─ after_dialog：对话/事件结束后异步评估（高重要度角色/事件后必评）
 ├─ scheduled：定时触发（如"角色每日日程"），P2 支持 cron 配置
 ├─ event：世界事件自动触发
 └─ manual：前端手动触发
        │
        ▼
ActionEngine 组装行动 prompt（action_gen 模板：角色卡 + 当前情境/时间/事件 + 输出Schema）
        │
        ▼
AI 调用（json_object 结构化输出）→ Schema 校验 → 落 actor_action_plan（status=planned）
        │
        ▼
行动执行模拟（P2 web 预览阶段）：
   ├─ 更新角色状态（location/currentActivity，存 character 扩展字段或内存）
   ├─ 生成 action_log（时间线节点）
   └─ 前端 SSE action 事件刷新时间线
```

### 11.3 UE5 数据契约预留

- `action_decision.json`（§11.1 Schema）即为后续 UE5 行为树/寻路消费的最小契约：`type` → UE5 行为树节点、`params.move.to` → 目标点、`urgency` → 决策优先级。
- 角色卡 `behaviorPatterns` → UE5 行为偏好参数（活动时段/社交倾向/冒险性）。
- P4 对接时以 `actor_manifest.json` 汇总角色卡 + 行动记录 + 对话摘要，对齐统一产物契约（可并入 M-F5 生态层打包）。

---

## 十二、普通型 NPC（A-C6）

### 12.1 定位与成本策略

普通型 NPC（路人/群像）**不创建独立 AI 实例**，采用两级成本策略：

| 策略 | 说明 | 成本 |
|---|---|---|
| **程序化驱动** | 参数化个体（`actor_crowd_member.profile_json`：职业/作息/活动圈）+ 状态机（走/停/对话/休息），按时间推进 | 零 AI 成本 |
| **集体 AI 操控** | 一次调用让"整个 AI"为一批角色批量决策（如"这一小时集市上的人群都在做什么"），批量产出群体行动日志 | 单次调用覆盖百人 |

### 12.2 批量生成

- `CrowdService`：按参数（数量/职业分布/活动区域/密度）程序化生成 `actor_crowd_member`，含姓名池、职业池、活动圈、作息表。
- 与生态层联动（预留）：crowd 成员可映射生态层 `fauna_placement.json` 中的近景实模/远景 proxy 点位（P4）。

### 12.3 集体行动调度

- 定时/手动触发 `POST /crowds/{id}/schedule`：
  - **程序化路径**：按作息表 + 状态机推进每个成员状态，产出群体行动日志。
  - **集体 AI 路径**：`crowd_orchestrator` 模板——输入人群配置 + 当前时段 + 事件，一次生成"群体快照"（各区块人群在做什么），落日志。
- web 展示：人群动态流 + 群体统计（职业分布/活跃度）。

---

## 十三、知识库与记忆（A-C7，分阶段渐进）

### 13.1 阶段规划

| 阶段 | 能力 | 技术 | 落地 |
|---|---|---|---|
| **P0（本期）** | 设定注入 | 世界观自由文本 + 角色卡直接拼入 system_prompt（截断/按需截取） | `actor_knowledge_doc` 存文本，注入时拼接 |
| **P1** | 向量 RAG | 文档 embedding 入库（pgvector / Milvus 二选一，评估后定），对话前检索 top-k 片段注入 | 扩展 `actor_knowledge_doc.embedding` 或迁移向量库 |
| **P2** | 跨会话长期记忆 | 对话/事件后 AI 生成摘要/事实，按重要度写入 `actor_memory`，后续对话注入 | `scene:summarize` + `memory:extract` 两步 |

### 13.2 设计要点

- **知识来源**：世界观设定、角色卡、对话摘要、事件记录、用户补充文档（Admin/用户页可上传）。
- **记忆写入**：每会话结束或每 N 轮后，触发 `memory:extract`（json_object 输出：kind/summary/content/importance），按重要度保留（低重要度滚动淘汰）。
- **注入优先级**：角色卡（恒在）> 长期记忆（按重要度）> RAG 检索片段（P1）> 近期对话。

---

## 十四、Casdoor SSO 集成

### 14.1 与 HolzynWeb 的统一身份

- **同一组织**：Casdoor 组织 `holzyn`，**复用 web 的 `holzyn-web` 客户端**（client-id=`holzyn-web`），在其 redirect-uris 追加 actor 3 条回调（本地 `http://localhost:8081/login/oauth2/code/casdoor` / 内网 CT110 / 公网 `https://actor.holzyn.com:14443/login/oauth2/code/casdoor`）。
- **SSO 流程**：用户在 HolzynWeb 控制台点击"NPC 控制台" → 跳转 `https://actor.holzyn.com/oauth2/authorization/casdoor` → Casdoor 已有会话则**免登录**直接回调 → 本地建会话 Cookie（域 `actor.holzyn.com`）→ 进主页。
- **用户同步**：`CustomOidcUserService` 登录时按 `sub` 同步/复用 HolzynWeb 的 `sys_user`（跨模块共享同一 userId），保证"同一用户跨模块项目/权限一致"。
- **权限模型**：`/api/**` 需登录、`/api/admin/**` 需 `ROLE_ADMIN`（Casdoor `is_admin` 映射），与 HolzynWeb 对齐。
- **开关**：`holzyn.actor.casdoor.enabled`（默认 `true` 真实 OIDC；演示模式默认关闭，本地无 SSO 环境显式置 false 时 `CurrentUserProvider` 兜底本地单用户 userId=1 / isAdmin=true）。

### 14.2 跳转链路

```
HolzynWeb（CT109，holzyn.com）                  Casdoor                  actor（CT110）
 控制台点击"NPC 控制台"
   ──https://actor.holzyn.com/oauth2/authorization/casdoor──►
                                                    已有会话? ──► 是：直接回调 ──► 本地会话 ──► 主页
                                                              └ 否：登录页
```

---

### 14.3 OIDC 落地状态（2026-08-12）

- **真实 OIDC 已启用**：`holzyn.actor.casdoor.enabled` 默认 `true`（演示模式默认关闭）；`SecurityConfig` / `CurrentUserProvider` 默认值一致化，任意启动方式均进入真实 SSO。
- **复用 web 客户端**：client-id=`holzyn-web`、issuer `https://casdoor.mbfsr.com:14443`（公网统一端口 14443，运营商封锁 443）。
- **回调白名单**：已在 Casdoor `holzyn-web` 应用 redirect-uris 追加 actor 3 条（本地 `http://localhost:8081/login/oauth2/code/casdoor`、内网 CT110、公网 `https://actor.holzyn.com:14443/login/oauth2/code/casdoor`）；**新增域名/端口时需同步追加**，否则真实 SSO 回调会被拒绝。
- **时钟偏移兜底**：`holzyn.actor.casdoor.clock-skew-seconds=600`（CT108 系统时钟比本地快约 5 分钟，id_token `iat` 位于未来被默认 60s 校验拒绝；放大容忍后保留 iss/aud 严格校验；CT108 NTP 校准后可收紧）。
- **登录回跳**：`holzyn.actor.frontend-url`（默认 `http://localhost:5174`，登录/登出成功回跳前端 SPA；生产改公网 `https://actor.holzyn.com`）。
- **注册代理**：`POST /api/register` 经 Casdoor 管理 API 创建用户（`HOLOZYN_ACTOR_CASDOOR_API_URL` / `_ADMIN_USER` / `_ADMIN_PASSWORD` 环境变量注入，明文不入库）。
- **跨模块账号互通**：web 已注册账号（如 admin）在 actor 可直接登录（同组织同客户端，consent 已授权）；新注册用户首次登录需在 Casdoor 授权页点同意（标准流程）。

---

## 十五、部署架构（CT110）

### 15.1 拓扑

```
用户浏览器 ──https://actor.holzyn.com:14443──> Nginx（X-Forwarded-*）
                                              └──> holzyn-actor 单 JAR（Tomcat，Spring Boot）
                                                      ├── MySQL（mysql.mbfsr.com:23306 / holzyn 库，actor_ 表）
                                                      └── Casdoor（https://casdoor.mbfsr.com，OIDC，组织 holzyn）
跳转来源：HolzynWeb 控制台（CT109）──SSO 跳转──> actor.holzyn.com
```

### 15.2 合包与域名

- **合包策略**：前端 `vite build` 产物复制到 `backend/src/main/resources/static/`，`mvn package` 打成单 JAR（`holzyn-actor-demo.jar`），无需 Nginx 前端，对齐 CT109。
- **SPA 回退**：`SpaForwardController` 将 `/login`、`/project/**`、`/admin/**` forward 到 index.html，刷新不 404。
- **子域名**：`actor.holzyn.com`（Nginx server block，公网 14443 / 内网双链路，对齐 CT109）。
- **本地桌面模式（V1.1 新增，详见 §十六）**：全部功能完成后打包 **Tauri 2 桌面 exe**（sidecar 内嵌 Java 后端 + 本地 H2 库），与 CT110 公网部署并存；本地模式无 SSO、数据在 `%APPDATA%\HolzynActor\data`，绿色便携版分发。
- **CORS**：`holzyn.actor.cors.allowed-origins`（默认 `*`，生产收紧为 `https://holzyn.com`、`https://actor.holzyn.com`）。
- **HTTPS 跳转头**：nginx 传 `X-Forwarded-Proto/Host/Port` + `server.forward-headers-strategy: framework`。

### 15.3 环境变量（关键）

| 变量 | 用途 |
|---|---|
| `HOLOZYN_DB_USER` / `HOLOZYN_DB_PASSWORD` | MySQL 连接（复用 holzyn 库；默认 root/hongyun_126 本地开发） |
| `HOLOZYN_ACTOR_CASDOOR_ENABLED` | OIDC 开关 |
| `HOLOZYN_ACTOR_CASDOOR_CLIENT_ID` / `HOLOZYN_ACTOR_CASDOOR_CLIENT_SECRET` | Casdoor 应用凭据（复用 web 的 holzyn-web） |
| `HOLOZYN_ACTOR_API_KEY_SECRET` | AI 供应商 api_key 加密密钥 |
| `HOLOZYN_ACTOR_UPLOAD_DIR` | 头像/图片上传根目录 |
| `HOLOZYN_ACTOR_DEFAULT_PROVIDER` | 默认 AI 供应商（首接 DeepSeek） |
| `HOLOZYN_ACTOR_CASDOOR_API_URL` / `_ADMIN_USER` / `_ADMIN_PASSWORD` | 注册代理（Casdoor 管理 API + 管理员凭据，明文不入库） |
| `HOLOZYN_ACTOR_CASDOOR_CLOCK_SKEW_SECONDS` | id_token 时间戳容忍（默认 600s，CT108 时钟偏移兜底） |
| `HOLOZYN_ACTOR_FRONTEND_URL` | 登录/登出成功回跳前端地址（开发 5174 / 生产公网 actor.holzyn.com） |

---

## 十六、exe 化（桌面）重构设计（V1.1 新增）

> **方向确认**：2026-08-12 定稿。HolzynActor 全部路线图功能（P0~P4）完成后，在现有 Web 服务外围包一层 **Tauri 2 桌面壳**，将外部依赖（远程 MySQL / Casdoor）替换为本地依赖，产出 **Windows 绿色便携版 exe**；源码**无需大规模重构**（业务代码复用率 ≈95%+）。详细方案见 `actor/docs/HolzynActor_exe化重构说明_V1.0.md`。

### 16.1 重构决策（7 项）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 代码来源 | 改造现有 HolzynActor |
| 2 | 运行形态 | Tauri 2 桌面壳 + sidecar 内嵌 Java 后端 |
| 3 | 数据存储 | 本地嵌入式库（H2 MySQL 兼容模式） |
| 4 | 账号体系 | 去掉登录（本地单用户，复用「演示模式」开关） |
| 5 | 分发形态 | 绿色便携版先行 |
| 6 | 本地化时机 | 开发期（P2~P4）即兼顾，数据源/认证做成可配置 |
| 7 | 跨平台 | 移动端后续复用后端 REST API，客户端另行开发 |

### 16.2 目标架构

```
Tauri 2 桌面壳（系统 WebView 渲染现有 Vue 构建产物）
   │  sidecar 自动拉起 / 退出时回收
   ▼
Java 后端（jpackage 打含精简 JRE 的 exe）
   └──▶ 本地 H2（MySQL 兼容模式，%APPDATA%\HolzynActor\data）
无登录：CurrentUserProvider 固定本地单用户
AI 调用：用户自配 OpenAI 兼容 API Key（沿用 AES-256-GCM 加密逻辑）
```

### 16.3 复用率与工作量

- 前端 Vue ≈100%、后端业务 ≈95% 直接复用；仅基础设施适配（数据源/认证/打包），约全代码量 10%。
- **关键红利**：现有「演示模式」开关（`holzyn.actor.casdoor.enabled=false`）即为"无 SSO 本地单用户"身份层，**去掉登录几乎零成本**。

### 16.4 关键改造点

- **前端**：去 SSO 跳转、路由守卫本地直进、API 指向 `http://127.0.0.1:8081`、CORS 放开本地 WebView 来源。
- **后端**：数据源 profile 化（`application-local.yml` H2 / 默认 MySQL）、认证开关沿用 `enabled`、`spring-boot:repackage` + `jpackage`（`jlink` 裁剪 JRE）打 exe、作为 Tauri sidecar、端口检测 + 随机端口兜底、数据目录 `%APPDATA%\HolzynActor\data`。
- **数据库**：将 V1.0/V1.1/V1.2 三份脚本合并为 H2 兼容脚本（`LONGTEXT→CLOB`、`JSON→TEXT`、`ON UPDATE` 语法调整）。
- **模型 API**：零改动（用户自配 OpenAI 兼容 Key，加密/脱敏/默认/测试逻辑沿用）。

### 16.5 分阶段计划

| 阶段 | 内容 | 时机 |
|---|---|---|
| **A 开发期本地化兼容** | 数据源 profile 化、认证开关对齐、H2 脚本、业务层不写死 MySQL | 随 P2~P4 同步落地 |
| **B 功能完成后 exe 化** | Tauri 2 工程、sidecar 打包、前端去 SSO、数据目录、便携打包 | HolzynActor 全部功能完成后 |
| **C 分发** | GitHub Release 发 zip 便携版；可选安装器/自动更新/代码签名 | 阶段 B 后 |

---

### 16.6 桌面端通用骨架（V1.1 补充）

> 桌面 exe 采用**自定义标题栏 + 左侧主导航 + 多标签内容区 + 底部状态栏**的统一骨架（替代系统原生窗口，深色风格统一）。

```
┌─────────────────────────────────────────────────────────────┐
│ 🔵 Holzyn Actor │ 🔍 全局搜索项目/角色/对话 │ ⚙️ 👤 用户 ─ □ × │
├────────────┬────────────────────────────────────────────────┤
│ 📁 项目总览  │  标签栏：项目总览 × 对话 × 时间线 × +        │
│ ➕ 新建项目  ├───────────────────────────────────────────────┤
│ 💬 对话     │                                               │
│ 🕒 行动时间线│              主 内 容 区                     │
│ 👥 普通人群  │                                               │
│ 📚 知识库   │                                               │
│ ⚙️ 模型配置 │                                               │
├────────────┴───────────────────────────────────────────────┤
│ 当前项目：xxx │ API：正常 │ Token：1,234 │ 后台任务：0      │
└─────────────────────────────────────────────────────────────┘
```

- **自定义标题栏**：替代系统原生标题栏；左侧品牌 Logo +「Holzyn Actor」，中间全局搜索框（跨项目搜索角色/对话记录/文档），右侧全局设置、用户菜单、窗口控制（最小化/最大化/关闭）。
- **左侧主导航**：固定宽度垂直导航（图标+文字），按功能优先级排序（项目总览/对话/行动时间线/普通人群/知识库/模型配置）；底部显示版本与运行状态（本地运行/API 连接正常），支持一键收起为纯图标模式。
- **多标签内容区**：桌面端核心优化，支持同时打开多个页面标签（项目设置、多个对话窗口、时间线），标签可拖拽排序、单独关闭、快捷键切换，提升多任务效率。
- **底部状态栏**：常驻显示当前选中项目、API 连接状态、累计 Token 消耗、后台任务进度（角色卡生成/向量化）。

### 16.7 桌面端页面布局（7 页，与 Web 页面映射适配）

> 以下 7 页在现有 Web 页面上做**桌面化适配**（非推翻重写），标注了对应 Web 页面与落地阶段。

**① 项目总览（首页）**
- 对应 Web：`HomeView.vue`（项目卡片 + 首页内 Tab）。
- 布局：上区项目卡片网格（首个为「新建项目」入口，其后为已有项目卡：封面/名称/角色数/状态标签/更新时间）；下区左侧「最近访问」列表（时间倒序）+ 右侧「快速操作」（一键生成角色卡/打开对话工作台/导出项目数据）。
- 桌面优化：双击卡片进对话页、右键操作菜单（重命名/导出/复制/删除）、拖拽排序、本地缓存项目缩略图、导入外部项目包。

**② 项目设置页**
- 对应 Web：`ProjectSettings.vue`（Tabs：基础/世界观/角色）。
- 布局：左侧二级导航（基础信息/世界观设定/角色管理/角色卡预览/生成记录）锚定定位；右侧表单主区（基础信息 + 世界观结构化字段 + 自由长文本 Markdown + 角色列表）；底部固定操作栏（保存草稿/一键生成全部角色卡/导出角色卡包）。
- 桌面优化：所有输入自动本地草稿保存防丢失；角色编辑用右侧抽屉（背景/经历/社会关系）；角色卡预览支持手动编辑、版本切换、对比历史版本；生成进度右下角弹窗通知。

**③ 对话工作台**
- 对应 Web：`ChatView.vue`（三栏已具备）。
- 布局：三栏——左「角色&会话」栏（搜索角色、特殊型/普通型分组、群聊多选、对话模式切换 单聊/群聊/事件注入、新建/切换/删除会话）；中「消息流」主区（Markdown 渲染、SSE 流式打字机、世界事件横幅卡片、行动决策卡片、输入框 Enter 发送/Shift+Enter 换行/快捷指令）；右「角色信息」面板（人设摘要/查看完整角色卡/手动触发行动）。
- 桌面优化：消息右键复制/重生成/导出、分屏多对话窗口、对话记录导出 Markdown/TXT、全局快捷键唤出输入框。

**④ 行动时间线页**
- 对应 Web：`ActionTimeline.vue`（占位，P2 落地）。
- 布局：左筛选栏（角色/时间范围/状态/触发类型 + 手动触发行动按钮）；中纵向时间轴（行动决策/世界事件/执行日志三类节点分色，时间倒序）；底部行动详情面板（类型/目标/参数/决策理由/优先级/状态/耗时，支持重新生成/标记状态/导出单条）。
- 桌面优化：时间轴缩放、批量导出 JSON/图片、后台静默更新行动状态并弹窗提示。

**⑤ 普通型人群页**
- 对应 Web：`CrowdView.vue`（占位，P3 落地）。
- 布局：三栏——左人群组列表（新建人群组：数量/职业分布/活动区域/密度）；中人群组详情（统计信息/职业分布饼图/成员抽样列表/编辑/重新生成/导出）；右群体行动动态流（程序化状态机/AI 集体决策双模式切换、手动触发集体调度）。
- 桌面优化：万级人群本地高性能渲染、人群数据导出 JSON、后台静默状态机更新。

**⑥ 知识库管理页**
- 对应 Web：`KnowledgePage.vue`（占位，P3 向量 RAG 落地）。
- 布局：左文档分类栏（全部/世界观设定/角色资料/事件记录/自定义分类）；右文档列表（工具栏：上传文档/新建文本/搜索；表格：标题/字数/向量化状态/操作）；底部预览抽屉（内容预览/编辑/重新向量化/删除）。
- 桌面优化：拖拽 TXT/Markdown 上传、批量导入、本地向量存储离线可用。

**⑦ 模型与配置中心**
- 对应 Web：`ModelApiSettings.vue` + 首页内 Tab（模型供应商/Prompt 模板/用量占位）。
- 布局：左配置分类（模型供应商/Prompt 模板/AI 用量统计/全局设置/数据管理）；模型供应商模块（多供应商 CRUD、API Key 本地加密存储、测试连接、按场景分配默认模型：对话/角色卡生成）。
- 桌面优化：API Key 存入系统密钥链、一键导入/导出配置文件、离线模式自动禁用 AI 功能并提示。

### 16.8 桌面端专属补充（V1.1）

1. **系统托盘**：最小化到系统托盘，后台静默运行行动调度/角色卡生成任务，完成后弹窗通知。
2. **全局快捷键**：支持自定义快捷键（快速新建对话、唤出主窗口、触发行动等）。
3. **本地文件关联**：支持双击 `.holzyn` 项目包直接打开软件并加载项目（导入/导出项目包）。

---

## 十七、与生态层 M-F / UE5 对接（预留契约，P4）

> 本期**不做 UE5 对接**，仅定义后续可被生态层/orchestrator 消费的产物契约，避免返工。

### 17.1 角色 → 生态层关联（预留）

| 本模块产物 | 生态层 M-F 关联 | 说明 |
|---|---|---|
| 特殊型角色卡 | M-F2 物种清单 / M-F4 群聚放置 | 特殊型 NPC 可绑定某栖息地分区（M-F1 ecozones）与群聚点位 |
| 行动决策 JSON | M-F4 / M-F5 放置 | `action_decision.params.move.to` 可映射世界坐标/群聚点 |
| 普通型人群 | M-F4 群聚放置 | crowd 成员映射近景实模 + 远景 LOD proxy 点位 |

### 17.2 UE5 原生导入路径（预留）

| 本模块产物 | UE5 原生路径 | 结论 |
|---|---|---|
| 角色卡/行为偏好 | NPC 行为树参数 + 属性 DataAsset | ✅ 预留 |
| 行动决策 | Behavior Tree Task / 寻路目标点 | ✅ 预留 |
| 对话记录 | NPC Dialogue 数据 / 任务链触发 | ✅ 预留 |
| 普通型人群 | PCG Spawner 批量实例 + LOD proxy | ✅ 预留 |

### 17.3 actor_manifest.json（P4，对齐统一产物契约）

```jsonc
{
  "manifestVersion": "1.0",
  "projectId": "...",
  "products": [
    {"type": "character_card", "path": "actor/out/<project_id>/characters/<id>.json", "ue5": "NPC_DataAsset"},
    {"type": "action_log",      "path": "actor/out/<project_id>/actions/timeline.json", "ue5": "BehaviorTree"},
    {"type": "crowd",           "path": "actor/out/<project_id>/crowds/<id>.json", "ue5": "PCG_Spawner"}
  ]
}
```

---

## 十八、分阶段路线图

### 18.1 里程碑

| 优先级 | 项目 | 说明 | 状态（2026-08-12） |
|---|---|---|---|
| **P0** | 骨架 + SSO | Spring Boot 骨架、shared 契约实现（R<T>/camelCase）、Casdoor SSO、CT110 部署、共享前端基建抽取 | ✅ 完成（真实 OIDC 启用、演示模式默认关闭） |
| **P1** | 设置页 + 角色卡 + 单聊 | 项目/世界观 CRUD、角色卡生成引擎（结构化+可编辑+版本）、对话页单聊（SSE 流式）、主页 | ✅ 完成（P1-1 模型 API / P1-2 项目世界观 / P1-3 角色卡 / P1-4 单聊 SSE + 3 项修复 + 角色 detail 增强） |
| **P2** | 群聊 + 世界事件 + 行动引擎 | 群聊/世界事件编排、行动决策 JSON + 时间线页、管理后台（模型供应商/Prompt 模板/用量）、行动定时触发 | ⬜ 待办（下一步） |
| **P3** | 普通型 NPC + 知识库 RAG | 人群批量生成 + 集体行动调度 + 人群页；embedding 向量检索接入（pgvector/Milvus 评估） | ⬜ 待办 |
| **P4** | 长期记忆 + UE5 对接 | 跨会话记忆、actor_manifest.json 导出、与生态层 M-F / orchestrator 联调 | ⬜ 待办 |
| **EXE** | 桌面 exe 化 | Tauri 2 桌面壳 + sidecar + 本地 H2 + 无登录 + 便携分发（见 §十六） | ⬜ 待 P0~P4 完成后执行 |

### 18.2 依赖链

```
P0（骨架+SSO）→ P1（角色卡+单聊）→ P2（群聊/行动）→ P3（普通型/RAG）→ P4（记忆/UE5）→ EXE（桌面 exe 化，见 §十六）
并行：共享前端基建抽取（P0 起）、管理后台（P2）、遥测接入（P3+）、本地化兼容（P2~P4，见 §16.5 阶段 A）
```

---

## 十九、风险与降级方案

| 风险 | 缓解 | 降级方案 |
|---|---|---|
| AI 供应商不可用/限流 | 多供应商路由 + 指数退避重试 | 切换备选 Provider；对话降级为非流式一次性返回 |
| 角色卡结构化输出不稳定 | json_object + Schema 校验 + 重试(≤2) | 降级为"AI 生成文本 + 本地解析"，解析失败提示手动编辑 |
| 长上下文 token 成本高 | 滑动窗口 + 摘要 + 知识库检索裁剪 | 缩短保留轮数；降低对话历史深度 |
| 群聊多角色 token 爆炸 | 仅注入角色卡摘要 + 最近轮次 | 群聊人数上限（建议 ≤5）；必要时降级为顺序单聊 |
| 普通型成本失控 | 程序化驱动为主 + 集体 AI 批量决策 | 关闭集体 AI，纯程序化状态机 |
| 跨域 SSO 会话问题 | 与 HolzynWeb 同构的 OIDC 配置 | 演示模式（enabled=false）直进兜底 |
| 用户数据跨模块一致性 | 复用 sys_user，同一 userId | 定期对账（P3+ 任务） |

**降级原则**：所有降级路径保证功能正确性等价，仅质量/性能下降；前端显示当前模式与降级提示，用户知情。

---

## 二十、测试与验收

### 20.1 后端

- 契约测试：R<T> 包装、camelCase、分页结构；共享 Schema 校验脚本（对齐 shared P1）。
- 角色卡生成：结构化输出 Schema 校验通过率、重试成功率、版本管理。
- 对话链路：SSE 流式端到端（建会话 → 发消息 → 收流 → 落库）；单聊/群聊/世界事件三种形态。
- 行动引擎：行动决策 JSON 校验、时间线聚合正确性、触发源覆盖（after_dialog/scheduled/event/manual）。
- 普通型：批量生成数量/分布正确、集体调度日志、性能（万人级状态推进耗时）。

### 20.2 前端

- 页面清单验收：主页/设置页/对话页/行动时间线/人群页/管理后台，SSO 登录跳转链路。
- 流式体验：SSE 打字机渲染、失败重试、断线重连。
- 风格一致性：与 HolzynWeb 共享布局壳/主题渲染对比（视觉一致性检查）。

### 20.3 部署验收（CT110）

- `mvn package` 单 JAR 构建、`npm run build` 通过；`actor.holzyn.com` 公网/内网访问、SPA 回退、SSO 免登录跳转全链路验证。

---

## 附录 A：API 端点汇总

| 分组 | 端点 | 用途 |
|---|---|---|
| 健康/认证 | `GET /api/health`、`GET /api/auth/me` | 健康检查、当前用户 |
| 模型 API | `/api/model-apis*`、`/test` | 用户级模型 API 配置（CRUD/默认/连通性测试，Key 加密入库） |
| 项目/世界观 | `/api/projects*`、`/api/projects/{id}/world-setting*`、`/generate-cards` | 项目 CRUD、世界观、一键生成 |
| 角色/角色卡 | `/api/projects/{id}/characters*`、`/api/characters/{id}*`、`/card*`、`/relations*` | 角色 CRUD、角色卡生成/版本/编辑、关系图 |
| 对话 | `/api/conversations*`、`/messages*`、`/stream`、`/world-event`（含 `DELETE /conversations/{id}`） | 会话 CRUD（含删除）、消息、SSE 流式、事件注入 |
| 行动 | `/api/characters/{id}/actions*`、`/timeline`、`/action-logs` | 行动决策、时间线、日志 |
| 普通型 | `/api/projects/{id}/crowds*`、`/api/crowds/{id}*`、`/schedule` | 人群生成、集体调度 |
| 知识库 | `/api/projects/{id}/knowledge-docs*` | 知识文档 CRUD |
| 管理 | `/api/admin/model-providers*`、`/prompt-templates*`、`/usage` | 供应商/模板/用量 |
| 通知(占位) | `/api/notifications*` | 通知列表/未读数/已读（P4 前空数据占位） |
| 遥测(预留) | `/telemetry/task\|progress\|result` | 上报（P3+） |

## 附录 B：数据库表汇总（holzyn 库新增 actor_ 前缀）

| 表 | 说明 |
|---|---|
| actor_project / actor_world_setting | 项目、世界观（版本化） |
| actor_character / actor_character_card / actor_character_relation | 角色（含 detail 详细信息）、角色卡（版本化）、关系图 |
| actor_conversation / actor_conversation_member / actor_message | 会话、群聊成员、消息 |
| actor_action_plan / actor_action_log | 行动决策、行动日志 |
| actor_crowd / actor_crowd_member | 普通型人群组、成员 |
| actor_knowledge_doc / actor_memory | 知识文档（P1 向量）、长期记忆（P2） |
| actor_model_provider / actor_prompt_template / actor_usage_log | 用户级模型 API 配置（user_id 归属、api_key 加密）、Prompt 模板、用量日志 |

## 附录 C：术语对照

| 术语 | 含义 |
|---|---|
| Persona / 角色卡 | 结构化角色档案 JSON（身份/性格/背景/关系/说话风格/知识边界） |
| system_prompt | 由角色卡渲染出的对话系统提示词 |
| SSE | Server-Sent Events，服务端单向事件流（本模块流式对话通道） |
| Provider | AI 模型供应商适配层（OpenAI 兼容协议） |
| 特殊型 NPC | 对剧情有作用的核心角色，走完整 AI 对话 + 行动驱动 |
| 普通型 NPC | 路人/群像，程序化驱动或集体 AI 操控 |
| 行动决策 JSON | 结构化行动产物（type/action/target/params/reason/urgency） |
| RAG | 检索增强生成（P1 知识库向量检索） |
| 长期记忆 | 跨会话记忆（P2，AI 摘要写入） |
| SSO | 单点登录（Casdoor OIDC，HolzynWeb 与本模块互通） |

---

## 文档版本与变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.1 | 2026-08-12 | 落地状态对齐（P0~P1-4 完成：真实 OIDC 启用、模型 API 用户级配置、项目/世界观/角色/角色卡/单聊 SSE、3 项修复、角色 detail + 对话右栏）；新增《exe 化（桌面）重构设计》章节（Tauri 2 桌面壳 + sidecar + 本地 H2 + 本地单用户 + 便携分发）；数据库（V1.1/V1.2 增量）、API（模型 API/通知/DELETE 会话）、认证（真实 OIDC 落地）、部署（双模式）章节按实际实现更新；附录 A/B 同步；**桌面端 UI 设计**（§16.6 通用骨架 / §16.7 7 页面布局 / §16.8 专属补充） |
| V1.0 | 2026-08-11 | 首版：定义 HolzynActor 第八模块（依赖第 7 模块 HolzynFauna 生态层产物契约）；独立服务（Java/Spring Boot）+ 独立前端 + CT110 部署 + `actor.holzyn.com` 子域 + HolzynWeb 控制台跳转；两级 NPC 体系（特殊型/普通型）；OpenAI 兼容多供应商 Provider 抽象；结构化角色卡 + 渲染 Prompt（可编辑/版本化）；单聊/群聊/世界事件对话（SSE 流式）；行动决策 JSON + 日志时间线；分阶段知识库（注入→RAG→长期记忆）；Casdoor SSO 统一身份；复用 holzyn 库（actor_ 前缀表）；共享前端基建；UE5 对接契约预留（actor_manifest.json） |

---

> **下一步**：P0~P1-4 已完成（真实 OIDC + 模型 API 用户级配置 + 项目/世界观/角色/角色卡 + 单聊 SSE + 3 项修复 + 角色 detail 增强）。按路线图推进 **P2**（群聊/世界事件/行动引擎/管理后台），随后 P3/P4；全部功能完成后进入 **EXE 桌面化重构**（见 §十六 与 `actor/docs/HolzynActor_exe化重构说明_V1.0.md`）。开发读取顺序：root `docs/主对接文档.md` → `actor/docs/主对接文档.md` → 本文档 → 最新次对接文档。


