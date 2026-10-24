# HolzynActor 桌面端（Electron）

把 HolzynActor 打包成**双击即用的桌面应用**：点击 `HolzynActor.exe` → 自动拉起内置后端（随包裁剪 JRE + Spring Boot jar）→ 弹出应用窗口（Chromium 内核），体验与其他桌面应用一致；首次运行自动注入示例世界「浮生小镇 · 青屿镇」。

## 目录结构

```
desktop/
  main.js               Electron 主进程（sidecar 启动后端 + 窗口 + 启动闪屏 + 无边框窗口 IPC）
  window-geometry.js    窗口几何：16:9 / 基准 1280×720 的尺寸计算与拖拽夹紧（纯函数，可单测）
  preload.js            预加载（最小上下文桥；含无边框窗口三键的 window.holzynDesktop.window）
  splash.html           启动闪屏 UI（IDEA 式：Logo/版本号/真实进度条/阶段文字/错误处置）
  splash-preload.js     启动闪屏预加载（最小上下文桥）
  scripts/              调试/验证脚本（cdp-screenshot、smoke-updater、test-window-geometry）
  package.json          依赖与 electron-builder 配置
  build/icon.ico|png    应用/安装包图标
  release/              打包输出（git 忽略）
```

> 窗口几何单测（不需要 Electron / GUI）：`cd desktop && node scripts/test-window-geometry.js`。

## 打包流程（Windows）

1. 构建后端 fat jar（含前端产物）：仓库根 `build.bat`，产物 `backend\target\holzyn-actor-1.1.1-beta.1.jar`。
2. 生成裁剪 JRE 运行时：`packaging/runtime`（jlink，约 62MB；缺失时 `build-desktop.bat` 会自动生成）。
3. 打包安装包：在 `desktop/` 目录执行：

```bat
npm install
npm run pack          :: NSIS 安装版  → release\HolzynActor-Setup-1.1.1-beta.1.exe
npm run pack:portable :: 免安装绿色版 → release\HolzynActor-Portable-1.1.1-beta.1.exe
```

或一键执行 `desktop\build-desktop.bat`（步骤 1+2+3 全自动）。

> 首次执行 `npm install` 需联网下载 Electron 二进制（约 100MB）；国内网络慢可设置镜像
> `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`。

## 运行机制

- **后端 sidecar**：`java -jar holzyn-actor.jar`（`resources/runtime/bin/java.exe`），端口自动挑选**随机空闲端口**（不占用开发者本机固定端口 18080，避免与开发实例冲突）。
- **数据目录**：`%LOCALAPPDATA%\HolzynActor\{data,uploads,logs}`——H2 数据库与上传文件都在这里，卸载/删除不影响用户数据；首次启动（data 为空）自动注入示例世界「浮生小镇 · 青屿镇」。
- **日志**：`%LOCALAPPDATA%\HolzynActor\logs\holzyn-actor.log`（应用日志）与 `holzyn-actor-console.log`（后端控制台输出）。
- **窗口形态（无边框 + 16:9 沉浸式）**：主窗口 `frame: false`（无系统标题栏/边框），**每次打开都是 16:9、基准逻辑尺寸 1280×720**
  （屏幕装不下时等比缩小，最小 960×540）；用户拖拽缩放时由 `will-resize` 反算另一边保持 16:9。
  标题栏由前端顶栏承担：整条可拖拽移动（`-webkit-app-region: drag`），最右侧自绘「最小化 / 最大化 / 关闭」三键
  （`frontend/src/shared/layout/WindowControls.vue` → `window.holzynDesktop.window.*` IPC）。最大化铺满工作区（不锁比例），
  还原后自动回到 16:9。基准几何与断点见 `frontend/src/shared/layout/WindowControls.vue` 与 `desktop/window-geometry.js`。
- **侧栏默认展开**：1280 基准下侧栏常驻（264px 可拖拽）；只有窗口被拖到 **<1120** 才自动收回为 56px 图标栏
  （<960 才改覆盖式滑入，桌面端最小窗口 960×540 实际到不了）。页面是否走紧凑布局改按**内容区宽度 <1152** 判定
  （不再用视口宽度，避免基准 1280 被判成宽屏）。
- **启动闪屏（IDEA 式）**：点击快捷方式后**立即**弹出屏幕居中的启动等待弹窗（深色 IDEA 风格 + 品牌渐变，含 Logo / Slogan「让世界像真人一样生活」/ **版本号 v1.1.1-beta.1**），消除「点击 → 界面打开」的空窗期；进度条由后端真实信号驱动（控制台日志阶段标记 + `/api/health` 就绪探针，无标记时按启动时限时间兜底）；启动失败直接在闪屏内展示并支持「打开日志目录 / 重试 / 退出」；后端就绪 + 主窗口 `ready-to-show` 后淡出关闭。开发模式（`npm start`）同样生效。
- **后台运行**：关闭窗口默认最小化到**系统托盘**，世界继续在后台推进（无人值守 · 世界不停止）——点击托盘图标回到主界面，托盘菜单「退出」才真正结束程序；设置页「更新与版本 → 后台运行」可改为「关闭窗口=直接退出程序」（选择持久化于 `%LOCALAPPDATA%\HolzynActor\desktop-settings.json`）。
- **退出**：托盘菜单「退出」/「关闭窗口=退出程序」/ 闪屏「退出」三条路径统一走「先优雅停后端（`/api/system/shutdown`，H2 干净落盘）→ 再退出应用」（单实例锁，重复双击只会聚焦已有窗口）。
- **关闭过渡**：退出收尾超过 **300ms** 才在主窗口内浮出「正在关闭世界…」遮罩（`preload.js` 注入，不依赖 Vue 路由与挂载状态，任何页面都能显示；z-index 复用 `--z-toast` 令牌）；300ms 内关完则完全不显示，避免「闪一下」的假卡顿。2026-10-23 已修根因：`GracefulShutdownController` 原用**非守护线程池**且从不 shutdown，`SpringApplication.exit()` 只关上下文不结束 JVM，桌面端只能等满 8s 超时强杀——现在上下文关闭后显式结束 JVM，实测「收到关闭请求 → 进程退出」**881ms、exit code 0**（回归测试 `GracefulShutdownControllerTest` 守住「必须结束 JVM + 执行线程必须是守护线程」）。
  同日复验又发现第二层原因：**页面上的世界实时 SSE 长连接**让 Tomcat 优雅停机停在「Waiting for active requests to complete」直到 desktop 8s 超时强杀（`20:18:13` 请求 → `20:18:21` 强杀，exitCode=1，H2 未干净落盘）。已补 `spring.lifecycle.timeout-per-shutdown-phase=3s`：到点后 Web 层停、Bean 继续正常销毁（JPA → Hikari → H2 干净关闭）。复测 `20:28:29.223 请求 → 20:28:33.113 退出`（**3.89s、exitCode=0、无强杀**），`ShutdownYamlTimeoutTest` 守住「该值必须 ≤5s」。

## 自动更新（U1+U2+U3+U4，v1.1.1-beta.1 起）

桌面端接入 **electron-updater（GitHub Releases provider）**，安装版从 v1.1.1-beta.1 起支持自动更新：

- **检查**：启动后后台自动检查 + 设置页「更新与版本」手动检查（可关闭自动检查）。
- **更新源**：`github.com/hongyun1312/HolzynActor` Releases（`latest.yml` / `latest-beta.yml` 元数据 + sha512 校验；`releaseType: draft` 先草稿后转正式）。
- **安装版**：发现新版本 → 自动下载 → 玩家确认 → **保存世界 → 优雅退出（后端 H2 干净落盘）→ 备份 data+uploads → 静默安装 → 重启**。
- **双通道（U3）**：设置页可切换 **stable 正式（默认）/ beta 预发布**——beta 收到预发布版本（可能不稳定），切回 stable 属「降级」需 `allowDowngrade`（已启用）；切换即触发检查并持久化。
- **数据库迁移（U3）**：后端接入 Flyway 版本化迁移——`V1__baseline.sql`（全新库建全表）/ 既有库自动打基线到 V1 零风险升级；后续 schema 变更一律新增 `V2__...`、`V3__...` 迁移脚本；JPA `ddl-auto` 收敛为 `none`（以迁移为准）。迁移状态可查 `GET /api/system/schema`。
- **便携版（U4）**：electron-updater 不支持 portable 自更新，改为「启动检查 GitHub Releases → 弹窗引导打开下载页 / 复制链接 → 关闭应用后覆盖旧目录」，世界数据不受影响。
- **SmartScreen 引导（U4）**：无签名开源软件首次运行展示一屏安全说明（「更多信息 → 仍要运行」+ sha512 校验方法），标记位防重复。
- **数据安全**：玩家数据在 `%LOCALAPPDATA%\HolzynActor`，与安装目录分离、卸载不删；更新前自动全量备份到 `backups\pre-update-*`（滚动保留 3 份）。
- **回滚**：新版本启动失败（后端超时未就绪/迁移失败）→ 自动恢复最新备份数据 + **自动重装上一正式版**（`allowDowngrade` 拉取旧版静默安装；更新源无更低版本时才引导手动重装）。
- **UI**：设置页「更新与版本」子页（当前版本/检查/下载进度/通道/说明）+ 主界面顶部全局横幅。
- **更新说明**：拉取 GitHub Release 正文展示「本次世界更真实」清单（每次更新 = 世界真实性的一次跃迁）。

### 发布（发新版到 GitHub Releases）

```bat
:: stable 通道（生成 latest.yml）
npm run publish:stable
:: beta 通道（生成 latest-beta.yml，需在 GitHub 上把 Release 标记为 Pre-release）
npm run publish:beta
```

> 需要 `GH_TOKEN`（GitHub 有仓库 releases 写权限的 token）；发布前先在 Release 正文写「本次世界更真实」清单。
> CI 路径：`.github/workflows/release.yml` 打 `v*` 标签时自动 `--publish always` 上传安装包 + latest.yml。

### 开发联调（免打包）

```bat
set HOLZYN_UPDATER_MOCK=1   :: 模拟「发现新版本→下载→就绪」，演练备份/顺序/UI
npm start
```

- 真实检查链路：`desktop/dev-app-update.yml`（GitHub provider）已配置，开发模式手动「检查更新」会真实访问 GitHub Releases；
- 冒烟安装：mock 模式下点「下载并重启更新」会真实执行「停后端→备份 data+uploads」，但不退出应用（开发模式不执行真实安装）。

## 开发联调（免打包）

```bat
set JAVA_HOME=C:\path\to\jdk21
cd desktop
npm start     :: 直接用 ../backend/target 下的 jar + 本机 JDK 启动窗口
```

### 闪屏设计评审（预览模式）

```bat
set HOLZYN_SPLASH_PREVIEW=1
cd desktop
npm start     :: 只显示启动闪屏（进度循环动画），一直保留在屏幕、不启动后端、不自动关闭；Alt+F4 或结束进程即退出
```

## 常见问题

| 现象 | 处理 |
|---|---|
| 点击快捷方式后闪屏停留较久 | 属正常：闪屏进度由后端启动驱动，首次注入示例世界最长 120s；进度长时间不动看 `%LOCALAPPDATA%\HolzynActor\logs\holzyn-actor-console.log` |
| 闪屏内提示「后端启动超时」 | 点击闪屏「打开日志目录」查看日志，通常是端口被占/数据目录被其他实例占用；可点「重试」重新启动 |
| 想重置数据 | 删除 `%LOCALAPPDATA%\HolzynActor\data` 后重启（会重新注入示例世界） |
