# HolzynActor · 模块化重构技术设计文档 V1.0

> **文档类型**：开发技术设计文档
> **日期**：2026-08-17
> **适用范围**：`HolzynActor`（NPC 角色 AI 驱动模块主项目，Web / exe 桌面程序双形态）
> **源项目**：`actor`（保持原样，仅作历史参考）
> **关联**：`HolzynActor_功能文档_V1.0.md`、`HolzynActor_数据库结构说明文档_V1.0.md`、`HolzynActor_远程数据接收通道设计_V1.0.md`、`docs/sql/*`

---

## 一、项目背景与目标

HolzynActor 是 Holzyn 全境世界生成项目中的「NPC 角色 AI 驱动」模块：以世界观设定为起点，自动生成有身份、有记忆、会对话、会行动的智能角色，并驱动整个世界持续运行与演化。

### 1.1 本次重构目标（5 项需求）

| # | 需求 | 实现摘要 |
|---|---|---|
| 1 | 功能模块化重构（功能解耦） | 后端「包级功能域模块化」；前端「功能域（features）+ 共享层（shared）」全量重组 |
| 2 | 数据存储：本地库（主）/ 远程库（预留） | 本地 H2 嵌入式（MySQL 兼容模式）为默认主库；远程 MySQL 通过 `remote` profile 预留；SQL 优化脚本入 `docs/sql` |
| 3 | 移除登录/鉴权，首次设置本地个人账户 | 删除 Casdoor OIDC / 注册 / 登录全部代码；新增本地单用户账户（昵称/头像/签名 + NPC 个性化档案），首次启动向导引导 |
| 4 | 悬浮岛式侧边栏 | 左侧圆角卡片、离边留白、柔和阴影；多级导航（功能域分组 + 设置父级展开收起 + 子级缩进 + 当前页高亮） |
| 5 | 隐藏非重点页面，聚焦重构 | 仅保留：项目仪表盘 / 世界详情 / NPC 角色 / 对话（单聊）/ 设置（4 子页）+ 画廊与新增项目入口；隐藏 4 页入口（普通人群/世界演化/时间线/知识库），路由保留 |

### 1.2 双形态定位

- **Web 形态**：本地运行（`localhost:5174` 前端 + `localhost:8080` 后端），本地 H2 数据，无登录；
- **exe 桌面形态**：前置架构已就绪（数据目录规划、数据源抽象、无登录身份层、上传/数据目录可配置）；Tauri 2 + Java sidecar 打包为后续阶段（见 §七）。

---

## 二、总体架构

```
┌────────────────────────────────────────────────────────────────┐
│                      前端（Vue 3 + Element Plus）               │
│  src/features/<功能域>/views + src/shared/{api,router,store,..} │
│  功能域：gallery/project/world/character/chat/settings/account   │
│          + 隐藏域：crowd/evolve/timeline/knowledge               │
└───────────────┬────────────────────────────────────────────────┘
                │ /api（Vite 代理 localhost:8080）
┌───────────────▼────────────────────────────────────────────────┐
│               后端（Spring Boot 4.1 / Java 21）                 │
│  com.holzyn.actor.domain.<功能域>.{controller,service,          │
│      repository,entity,dto,vo} + common + ai + config            │
└───────────────┬────────────────────────────────────────────────┘
                │ Spring Data JPA
        ┌───────▼────────┐        ┌─────────────────────┐
        │  本地 H2（主）   │        │  远程 MySQL（预留）    │
        │  MODE=MySQL     │  ←───  │  application-remote │
        │  ./data/*.mv.db │ 预留   │  .yml + 环境变量注入   │
        └────────────────┘        └─────────────────────┘
```

- 登录/鉴权（Casdoor OIDC、Spring Security、注册）已整体移除；
- 身份层为「本地单用户」：`CurrentUserProvider` 恒返回用户 id=1，业务归属统一；
- 远程 MySQL 表结构基于线上库实测优化（见 `docs/sql/V1.0__holzyn_actor_mysql_all_tables.sql`）。

---

## 三、后端：包级功能域模块化

### 3.1 包结构

```
com.holzyn.actor
├── HolzynActorApplication.java
├── common/                  # 跨域公共：R / BizException / PageResult / ErrorResponse /
│                            #   JsonUtil / AesCipherService / HolzynCrypto /
│                            #   GlobalExceptionHandler / HealthController / NotificationController / SpaForwardController
├── config/                  # 应用配置（CORS 等）
├── ai/                      # AI 共享基础设施：AiProviderRouter / OpenAiCompatibleProvider /
│                            #   ProviderConfig / AiChatRequest / AiChatResult / AiUsage / AiCallException
└── domain/                  # ★ 功能域（每域自包含 controller/service/repository/entity/dto/vo）
    ├── account/             # 本地账户 + 本地单用户身份（CurrentUserProvider）
    ├── project/             # 项目 CRUD / 世界观设定 / .holzyn 导入导出 / 文件导入建项目
    ├── world/               # 世界详情 / 世界时钟 / 时间线事件 / 场景 / 世界演化
    ├── character/           # NPC 角色 / 角色卡 / 社会关系 / Prompt 渲染
    ├── conversation/        # 单聊 / 群聊（后端保留）/ 消息 SSE / 世界事件注入 / 群聊配置
    ├── action/              # 行动决策 / 行动执行日志 / 行动引擎 / SSE Hub
    ├── crowd/               # 普通型人群（页面隐藏，功能保留）
    ├── knowledge/           # 知识库文档 / RAG 检索 / embedding
    ├── memory/              # 长期记忆（角色级/项目级）
    ├── settings/            # AI 模型 API 配置 / Prompt 模板
    └── usage/               # AI 调用用量日志 / 用量统计
```

### 3.2 功能域边界与依赖规则

- **单向依赖**：`controller → service → repository`；`service` 可依赖同域或他域 `repository/service`（跨域访问仅允许读模型，禁止反向写依赖形成环）；
- **实体归属**：每个 `ActorXxx` 实体唯一归属一个功能域，跨域引用通过该域的 `repository`；
- **AI 基础设施**：`ai` 为共享服务域，被各业务域依赖；`ai` 依赖 `settings`（读供应商配置）与 `usage`（记用量）；
- **common**：不依赖任何 domain，纯工具/模型。

### 3.3 各功能域实体与职责速览

| 功能域 | 实体（表） | 核心服务 |
|---|---|---|
| account | actor_local_account、sys_user | LocalAccountService（含 NPC 档案渲染） |
| project | actor_project、actor_world_setting | ProjectService / ProjectExportService / HolzynImportService / ProjectImportService |
| world | actor_world_clock / actor_event / actor_scene / actor_evolution / actor_evolution_participant / actor_evolution_turn / actor_world_setting | WorldClockService / TimelineService / SceneService / WorldEvolutionService / WorldSimulationJob |
| character | actor_character / actor_character_card / actor_character_relation | CharacterService / CharacterCardService / PromptService |
| conversation | actor_conversation / actor_conversation_member / actor_message / actor_group_chat_config | ConversationService / ChatService / GroupChatService / GroupChatConfigService / WorldEventService / ConversationContextService |
| action | actor_action_plan / actor_action_log | ActionEngine / ActionScheduledJob / ActionSseHub |
| crowd | actor_crowd / actor_crowd_member | CrowdService / CrowdEnvironmentService / CrowdScheduleService / CrowdScheduledJob |
| knowledge | actor_knowledge_doc | KnowledgeService / KnowledgeRetrievalService / EmbeddingService |
| memory | actor_memory | MemoryService / MemoryExtractParse |
| settings | actor_model_provider / actor_prompt_template | ModelApiService / PromptTemplateService |
| usage | actor_usage_log | UsageService / UsageLogService |

> 注：`actor_world_setting`（世界观设定）实体由 project 域使用、表归属 world 域，为「跨域读」关系，符合单向依赖约束。

---

## 四、数据源设计（本地 H2 主 / 远程 MySQL 预留）

### 4.1 双数据源机制

- **默认（本地主）**：`application.yml` 指向本地 H2 文件库
  `jdbc:h2:file:${HOLOZYN_ACTOR_DATA_DIR:./data}/holzyn-actor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`
  - `MODE=MySQL`：兼容 `LONGTEXT` / `JSON` / `DATETIME` / `AUTO_INCREMENT` 等 MySQL 语法，JPA 业务层零改动；
  - `ddl-auto: update`：首次启动自动建表，绿色便携无需手工建库；
  - 方言 `H2Dialect`（remote profile 覆盖为 `MySQLDialect`）。
- **远程（预留）**：`application-remote.yml` 配置 MySQL 数据源，连接信息经环境变量注入：
  ```
  java -jar holzyn-actor.jar --spring.profiles.active=remote
  ```
  - 连接变量：`HOLOZYN_REMOTE_DB_URL` / `HOLOZYN_REMOTE_DB_USER` / `HOLOZYN_REMOTE_DB_PASSWORD`；
  - 远程库表结构执行 `docs/sql/V1.0__holzyn_actor_mysql_all_tables.sql`。
- **存储抽象**：业务层统一走 Spring Data JPA Repository，不出现数据库方言相关 SQL；换库/迁移仅改数据源配置。

### 4.2 H2 兼容处理

| 项 | 处理 |
|---|---|
| `LONGTEXT` 实体列 | 统一改为 `@Lob`（H2→CLOB / MySQL→LONGTEXT 自动映射） |
| `JSON` 实体列 | 保留 `columnDefinition="JSON"`（H2 与 MySQL 均支持 JSON 类型） |
| `TEXT` 实体列 | 保留 `columnDefinition="TEXT"`（H2 MySQL 模式支持） |
| `ON UPDATE CURRENT_TIMESTAMP` | 仅在 SQL 脚本中出现；实体由 `@PrePersist/@PreUpdate` 维护 |
| 唯一约束/索引 | 建表脚本双版本维护（MySQL / H2） |

### 4.3 数据目录规划（exe 前置）

- 默认数据目录 `<工作目录>/data`（H2 文件库 + 上传文件）；
- 通过 `HOLOZYN_ACTOR_DATA_DIR` 环境变量可重定向；exe 化后约定 `%APPDATA%\HolzynActor\data`（程序目录只读时数据与程序分离）；
- 上传文件根目录 `holzyn.actor.upload-dir` 默认 `./uploads`，同样可配置。

---

## 五、本地账户与 NPC 个性化档案（需求 3）

### 5.1 本地单用户身份

- 移除：`AuthController` / `RegisterController` / `RegisterService` / `UserSyncService` / `SecurityConfig` / `CustomOidcUserService` / `OidcDecoderConfig` / `AuthSessionVO` / `RegisterDTO` / `AdminController`（占位），以及 pom 中 security + oauth2-client 依赖；
- 新增：`CurrentUserProvider`（本地单用户版）恒返回 `id=1` 用户；`resolveUserId()` 忽略入参恒 1；
- 前端：删除 `LoginView` / `RegisterView` 与 `/login`、`/register` 路由；auth store 替换为 account store。

### 5.2 本地账户模型（actor_local_account）

| 字段 | 说明 | 用途 |
|---|---|---|
| nickname | 昵称（显示名） | 顶栏/侧边栏/用户菜单展示 |
| avatar_url | 头像 | 展示 |
| signature | 个性签名 | 展示 |
| identity / occupation / hobbies / taboos | 结构化档案（身份/职业/喜好/禁忌） | 注入 NPC 上下文 |
| profile_text | 自由长文本「个人档案」 | 注入 NPC 上下文 |
| onboarded | 首次向导完成标记 | 首次启动引导 |

- 所有字段**选填**；接口：`GET/PUT /api/local-account`、`POST /api/local-account/onboarded`、`GET /api/local-account/me`（替代原 `/api/auth/me`）。

### 5.3 NPC 个性化注入链路

```
LocalAccountService.renderNpcProfile()
  → 「【你对用户的了解】身份/职业/喜好/禁忌/个人档案…」
  → ChatService.buildMessages() / GroupChatService.buildCharacterMessages()
  → 追加一条 system 消息（角色卡 system_prompt 之后）
```

- 档案为空时不注入，不影响原有行为；单聊与群聊（后端保留）均已接入；
- 前端首次向导（`/onboarding`）与全局账户设置页（`/account`）编辑同一份数据。

---

## 六、前端：功能域（features）+ 共享层（shared）

### 6.1 目录结构

```
frontend/src
├── main.js / App.vue
├── features/                        # ★ 功能域（页面/视图按域归属）
│   ├── account/views/               # 首次向导 FirstRunSetup + 账户设置 AccountSettingsView
│   ├── gallery/views/               # 项目画廊 GalleryView + 新增项目 NewProjectView
│   ├── project/                     # ProjectLayout（悬浮岛侧边栏骨架）+ views/DashboardView
│   ├── world/views/                 # WorldDetailView / WorldEditView
│   ├── character/views/             # CharacterView（NPC 角色）
│   ├── chat/views/                  # ChatView（单聊）
│   ├── settings/views/              # SettingsView + general/apis/prompts/usage
│   ├── crowd/views/                 # CrowdView（隐藏页，路由保留）
│   ├── evolve/views/                # EvolveView（隐藏页，路由保留）
│   ├── timeline/views/              # TimelineView / ActionTimeline（隐藏页，路由保留）
│   └── knowledge/views/             # KnowledgeView（隐藏页，路由保留）
└── shared/                          # ★ 共享层
    ├── api/                         # http.js + 按域拆分的 API 模块 + index.js 聚合
    │   ├── account.js / project.js / world.js / character.js / conversation.js
    │   ├── crowd.js / knowledge.js / memory.js / settings.js / misc.js
    ├── router/index.js              # 路由（本地账户守卫 + 首次向导重定向）
    ├── store/index.js               # useAccountStore（本地账户）
    ├── styles/                      # variables.css / global.css
    └── components/                  # console/（AvatarMenu/NotificationBell/ConsoleTopbar）、PagePlaceholder
```

### 6.2 关键机制

- **`@` 别名**：`vite.config.js` 配置 `@ → src`，所有内部导入统一 `@/shared/...`、`@/features/...`，目录调整不影响引用；
- **API 模块化**：共享层按功能域拆分为独立模块，`shared/api/index.js` 聚合 re-export，兼容原 `from '@/shared/api'` 写法；
- **路由**：移除登录/注册；`/onboarding` 首次向导、`/account` 账户设置；全局守卫基于 account store（未完成向导 → 重定向 `/onboarding`）；隐藏页路由保留（`meta.hidden`）；
- **页面保留/隐藏**：见功能文档 §三。

### 6.3 悬浮岛式侧边栏（需求 4）

- 视觉：左侧 `248px` 白色圆角卡片（`border-radius:18px`），四周留白（`margin:16px 0 16px 16px`），柔和阴影（`0 8px 28px rgba(...)`）悬浮于浅色背景之上；
- 结构（多级）：
  - 一级功能域分组：总览·项目仪表盘 / 世界观·世界详情 / 角色·NPC 角色 / 互动·对话（单聊）；
  - 「设置」为可展开/收起父级，子级（通用设置 / API 配置 / Prompt 模板 / AI 用量）缩进显示；
  - 当前页高亮：品牌蓝渐变背景 + 左侧竖条指示 + 子级圆点着色。

---

## 七、exe 前置架构（不建 Tauri 工程，本轮）

| 前置项 | 落地情况 |
|---|---|
| 数据源抽象 | ✅ profile 双配置（本地 H2 / 远程 MySQL），业务层 JPA 抽象 |
| 无登录身份层 | ✅ 本地单用户 CurrentUserProvider（原「演示模式」逻辑固化为默认） |
| 数据目录可配置 | ✅ `HOLOZYN_ACTOR_DATA_DIR` / `upload-dir`；exe 约定 `%APPDATA%\HolzynActor\data` |
| H2 兼容 | ✅ 实体注解适配 + H2 建表脚本（`docs/sql/H2__...`） |
| 后续 exe 方案 | Tauri 2 桌面壳 + Java sidecar（jpackage + 精简 JRE）+ 系统 WebView 渲染 Vue 构建产物；端口占用检测/随机端口兜底；桌面专属能力（托盘/快捷键/.holzyn 文件关联）为阶段 B 工作 |

---

## 八、远程数据接收通道（需求 2「预留」，仅设计）

> 完整设计见 `HolzynActor_远程数据接收通道设计_V1.0.md`。本轮只出设计、不写代码。

- 目标：发行后用户在本机使用（本地 H2 为主），自愿上报「注册世界时的设备信息、世界观概要」到开发者的远程库，用于二次开发（版本分布、世界观趋势等）；
- 形态：配置项 `holzyn.actor.remote.*`（默认关闭）+ 远程上报表 + 设置页「远程同步」开关（后续阶段）；
- 隔离原则：上报仅元数据与概要（设备指纹、项目世界观名称/题材、统计量），**绝不**上传 API Key、对话明文、个人敏感档案。

---

## 九、运行与构建

### 9.1 后端（默认本地 H2）

```bash
cd backend
# 方式 A：Maven 直接运行（需 JDK 21）
run-mvn.bat          # 内部已定位 JDK21 与 Maven
# 方式 B：打包后运行
mvn -Dmaven.test.skip=true package
java -jar target/holzyn-actor-1.0.0-SNAPSHOT.jar
# 远程 MySQL（预留）：java -jar ... --spring.profiles.active=remote
```

### 9.2 前端

```bash
cd frontend
npm install
npm run dev          # http://localhost:5174（代理 /api → 8080）
npm run build        # 产物 dist/
```

### 9.3 首次启动流程

1. 启动后端 → H2 自动建库建表（含 actor_local_account）；
2. 启动前端 → 路由守卫检测未完成首次向导 → 跳转 `/onboarding`；
3. 填写（或跳过）本地账户 → 进入项目画廊 → 新建/导入项目 → 配置 AI API（设置→API 配置）→ 开始对话。

---

## 十、主题系统与对话页 DSH 化（V1.1 补充）

### 10.1 双主题分层体系（设计令牌）

- **令牌来源**：对齐 DeepSeek Harness 设计系统（`dsw-*` 令牌：分层背景/边框/文本/阴影/滚动条 + DeepSeek 品牌蓝）；
- **结构**：`shared/styles/variables.css` —— `:root`（浅色默认）+ `[data-theme="dark"]`（深色）；Element Plus 变量同步覆盖；历史视图依赖的旧变量（`--brand-*`、`--bg-white`、`--text-*`、`--border-*`、`--radius-*` 等）全部保留为别名，历史代码零改动兼容；
- **分层语义**：`--bg-base`（页面底）→ `--bg-layer-1`（卡片）→ `--bg-layer-2`（次级面）→ `--bg-layer-3`（输入/内嵌）；文本四级 `--text-primary/regular/secondary/tertiary/placeholder`；边框 `--border-l1/l2/l3`；阴影 `--shadow-lv1/lv2/lv3`。

### 10.2 可换肤主色与题材联动

- **主色机制**：`--accent` 单一主色变量，衍生色（`--accent-strong/soft/softer/border/contrast/text`）由 CSS `color-mix()` 自动生成；JS 只需改 `--accent` 即全局换肤；
- **useTheme**（`shared/theme/index.js`）：深浅模式（system/light/dark + 跟随系统监听）、预设色板 10 色 + 自由取色、`GENRE_THEMES` 题材映射（恋爱→粉、科幻→蓝青、奇幻→紫、都市→青绿、历史→琥珀、悬疑→暗红、武侠→竹绿、仙侠→青绿、末日→灰等 15 类）、项目级主色覆盖（`overrides[projectId]`）、localStorage 持久化（`holzyn-actor.theme.v1`）；
- **生效优先级**：项目覆盖 > 世界题材联动 > 用户默认主色；进入项目时 ProjectLayout 调 `setProjectContext(projectId, genre)`（题材来自世界观 `world-setting.genre`），离开时 `clearProjectContext()`；
- **入口**：设置 → 通用设置「主题与外观」卡片 + 项目空间顶栏深浅快速切换按钮。

### 10.3 对话页 DSH 化

- **布局**：保留三栏，中间对话流对齐 DSH——`--chat-content-width: 748px` 居中列、`--composer-max-width: 780px`、自定义滚动条；
- **消息**：用户右气泡（`--accent` 底 + `--accent-contrast` 文字、22px 圆角、限宽 82%/525px）；AI 左气泡（`--bg-layer-3` 底 + Markdown 渲染，`shared/markdown/index.js` = markdown-it + highlight.js 按需注册 16 语言，ChatView 包 1058KB→214KB）；
- **生成状态**：TurnStatus「Deep diving...」流光（`background-clip:text` + 1.8s shimmer）+ 15s 后耗时计时；
- **Hero 空态**：无会话/无消息时大标题 + 角色快捷入口 + 新建按钮；
- **输入框**：DSH 形态（22px 圆角卡片、聚焦描边、textarea 自动增高至 336px、Enter 发送/Shift+Enter 换行、工具按钮 + 发送箭头 SVG）；
- **令牌化**：功能域视图硬编码色批量替换为 tokens（脚本），深浅主题全局协调。

---

## 十一、变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.0 | 2026-08-17 | 首版：模块化重构技术设计（后端功能域 + 前端 features/shared + H2 主/MySQL 预留 + 本地账户 + 悬浮岛侧边栏 + exe 前置） |
| V1.1 | 2026-08-17 | 补充：docs 清理、双主题分层体系（DSH 令牌）、可换肤主色 + 世界题材联动、对话页 DSH 化（气泡/Markdown/流光状态/Hero 空态/DSH 输入框） |
| V1.2 | 2026-08-17 | 补充：对话创建场景化（角色/对话所在地/世界时间 + 规则/AI 标题 + 【当前对话场景】注入 NPC 回答 + 创建后可编辑场景）与端口统一 8080（详见次对接文档 §9） |
| V1.3 | 2026-08-17 | 补充：首页全局设置（顶栏「开发工具」→「设置」+ 画廊设置入口 + 全局 /settings 四子页复用同一外壳/子页，API/Prompt 用户级默认、AI 用量全部项目总用量 + 按项目聚合）（详见次对接文档 §10） |
| V1.4 | 2026-08-17 | 补充：新增项目「上传文件解析」的 AI API 缺失引导（用户级 chat 用途预检 + 缺失预警一键跳转 /settings/apis + 解析失败弹窗引导 + 新增页头部设置入口）（详见次对接文档 §11） |
| V1.5 | 2026-08-17 | 修复：首页「设置」无反应（后端 static 同步最新前端 + SpaForwardController 转发 /settings、/account、/onboarding + 顶栏设置改 router-link）；测试包迁移至 domain.<域>.service 对齐模块化（109 测试全绿）（详见次对接文档 §12） |
| V1.6 | 2026-08-17 | 修复：首页「设置」改弹窗（内嵌用户级 ModelApiSettings，零路由依赖）+ 路由守卫无条件放行 /settings、/account、/onboarding（详见次对接文档 §13） |
| V1.7 | 2026-08-17 | 修复：顶部导航栏「设置」改为触发 open-settings 事件 → 打开首页设置弹窗（三入口统一、与路由解耦）+ router.onError 全局错误提示（详见次对接文档 §14） |
