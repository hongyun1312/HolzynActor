# 第三方依赖与字体声明（THIRD PARTY NOTICES）

HolzynActor 遵循 **Apache License 2.0** 开源（完整许可文本见 [LICENSE](LICENSE)，版权与归属声明见 [NOTICE](NOTICE)）。
本文件列出随本项目分发的第三方资源及其许可声明。

## HarmonyOS Sans 字体（随包分发）

- **字体名称**：HarmonyOS Sans SC（Thin / Light / Regular / Medium / Bold / Black）
- **来源**：[华为开发者官网 HarmonyOS 字体](https://developer.huawei.com/consumer/cn/design/harmonyos-sans/)（官方公开字体，仅作界面展示用途）
- **授权**：HarmonyOS Sans 字体由华为提供，遵循其官方许可与版权声明；完整许可文本见 rontend/src/assets/fonts/HARMONYOS_SANS_LICENSE.txt
- **使用范围**：本项目前端界面字体（--dsw-font-family 前插）；不得用于本项目之外的再分发或商业字体产品
- **分发要求**：随包保留了官方许可证文件，任何再分发本仓库者应一并保留该声明

## 主要运行时依赖（通过包管理器引入，未随包复制）

### 后端（Maven，见 ackend/pom.xml）

| 依赖 | 许可证 |
|---|---|
| Spring Boot / Spring Framework | Apache-2.0 |
| Spring AI | Apache-2.0 |
| LangChain4j | Apache-2.0 |
| Spring Data JPA / Hibernate ORM | Apache-2.0 / LGPL-2.1-or-later |
| H2 Database（本地嵌入式主库） | MPL-2.0 / EPL-1.0（双许可） |
| Jackson（tools.jackson / fasterxml） | Apache-2.0 |
| Lombok | MIT |

### 前端（npm，见 rontend/package.json）

| 依赖 | 许可证 |
|---|---|
| Vue 3 | MIT |
| Vite | MIT |
| Element Plus | MIT |
| AntV G6 v5（关系拓扑图） | MIT |
| markdown-it / highlight.js（对话 Markdown 渲染） | MIT / BSD-3-Clause |

> 说明：上表为直接依赖的许可概览，完整依赖清单以 ackend/pom.xml 与 rontend/package.json（含 lock 文件）为准。
> 本项目不包含上述二进制包，安装时由各包管理器从官方源拉取。