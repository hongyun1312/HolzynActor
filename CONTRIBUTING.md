# 参与贡献（CONTRIBUTING）

感谢你愿意参与 **HolzynActor（全境众生 · 浮生）**。本项目以 Apache-2.0 协议开源，欢迎一切形式的贡献：修 Bug、补文档、写测试、提建议、反馈真实使用体验。

## 这是什么项目

一台**让世界自己活着的 AI 世界模拟引擎**——给它一段世界观，它还你一个会自己过日子、自己记事、自己长出故事的世界。技术栈：

- 后端：Java 21 · Spring Boot 4.1 · Spring Data JPA · H2（本地）· Spring AI / LangChain4j
- 前端：Vue 3 · Element Plus · Vite · ECharts · AntV G6
- 桌面端：Electron（壳）+ 随包裁剪 JRE（sidecar 启动后端）

> ⚠️ 使用前必须自行配置 AI 模型（设置-API 配置）；程序化只作兜底，硬走兜底世界会很僵硬。

## 开发环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | **21**（LTS） | `JAVA_HOME` 指向 JDK 21 |
| Maven | 3.9+ | `mvn` 在 PATH（或设 `MAVEN_HOME`） |
| Node.js | 20+ | 前端与桌面端构建 |
| npm | 随 Node | 前端包管理（无 lockfile，`npm install` 即可） |

## 快速上手

```bat
:: 1) 构建（前端 → static → fat jar）
build.bat

:: 2) 运行（本地 H2 数据库自动创建在 <仓库>\data）
run.bat
:: 浏览器打开 http://localhost:18080（首次建库自动注入示例世界「浮生小镇 · 青屿镇」）

:: 桌面端（可选）：一键构建安装包
desktop\build-desktop.bat
```

开发联调：后端 `mvn -f backend/pom.xml spring-boot:run`；前端 `cd frontend && npm run dev`（Vite 代理 /api → 18080）。

## 仓库结构

```
backend/             Spring Boot 后端（domain 按功能域分包 + common / ai / config / agent）
frontend/            主前端（Vue3；src/features 功能域 + src/shared 公共）
debug-frontend/      调试可视化前端（构建进 static/debug）
desktop/             Electron 桌面端壳（main.js + electron-builder 配置）
packaging/           桌面端配套：jlink 裁剪 JRE、图标源与产物
docs/                文档（中文；含 主对接文档 / 次对接文档 / 架构 等治理文档）
scripts/             开发/测试辅助脚本
tests/               浏览器端到端验证等
```

## 开发约定（重要）

**架构不变量（强制）：**

1. **含 AI 调用的方法一律不得加 `@Transactional`**——H2 行锁跨 AI 调用会超时并丢已付费的 AI 结果。短事务、或提交后回调（`common/AfterCommit`）由业务自行组织；有 `AiCallOutsideTransactionTest` 反射守护。
2. **绝不改构造器签名**——新增依赖一律 `@Autowired(required=false)` 字段注入 + 测试用 `ReflectionTestUtils.setField`。
3. **`.holzyn` 导出/导入是核心资产**——改动导出字段必须同步导入还原逻辑（含 id 重映射），并保持 `formatVersion` 兼容。

**文档与测试：**

- 文档/注释/提交信息一律**中文**（代码标识符保留英文）。
- 每次功能改动同步更新治理文档（`docs/主对接文档.md` 加轮次行 + 新建 `docs/次对接文档_YYYYMMDD_NN.md`，并在涉及处更新 概念注册表 / 模块边界契约）。
- 提交前必须保证 **`mvn -f backend/pom.xml test` 全绿**（当前基线 **3120** 用例）。新增功能至少补对应的纯逻辑单测。
- 治理文档基线防回退：`backend/target/assertion-baseline/` 下基线文件，改动 Prompt/断言模板需同步基线。

## 提交与 PR

1. `fork` 本仓库 → 新建分支（`fix/xxx` 或 `feat/xxx`）。
2. 完成改动：代码 + 单测 + 治理文档 + 全量测试通过。
3. 提交信息建议格式：
   ```
   <类型>(<范围>): <一句话中文说明>
   （可选正文：改了啥 / 测试基线 N → N+ / 治理文档索引）
   类型：feat | fix | docs | refactor | test | chore
   ```
4. 提 PR：标题中文一句话，描述「改动 + 测试结果 + 验证方式」。CI 通过后等待评审。

## 反馈渠道

- 使用中发现问题：开 GitHub Issue（请附：操作系统/浏览器、操作步骤、`logs/holzyn-actor.log` 相关片段）。
- 安全问题：请走 **SECURITY.md** 的私有报告通道，勿公开。

## 许可

Apache-2.0（见 `LICENSE` / `NOTICE` / `THIRD_PARTY_NOTICES.md`）。参与贡献即表示同意在 Apache-2.0 条款下贡献你的代码。
