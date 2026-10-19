# HolzynActor 桌面端（Electron）

把 HolzynActor 打包成**双击即用的桌面应用**：点击 `HolzynActor.exe` → 自动拉起内置后端（随包裁剪 JRE + Spring Boot jar）→ 弹出应用窗口（Chromium 内核），体验与其他桌面应用一致；首次运行自动注入示例世界「青禾村」。

## 目录结构

```
desktop/
  main.js               Electron 主进程（sidecar 启动后端 + 窗口）
  preload.js            预加载（最小上下文桥）
  package.json          依赖与 electron-builder 配置
  build/icon.ico|png    应用/安装包图标
  release/              打包输出（git 忽略）
```

## 打包流程（Windows）

1. 构建后端 fat jar（含前端产物）：仓库根 `build.bat`，产物 `backend\target\holzyn-actor-1.1.0-beta.2.jar`。
2. 生成裁剪 JRE 运行时：`packaging/runtime`（jlink，约 62MB；缺失时 `build-desktop.bat` 会自动生成）。
3. 打包安装包：在 `desktop/` 目录执行：

```bat
npm install
npm run pack          :: NSIS 安装版  → release\HolzynActor-Setup-1.1.0-beta.2.exe
npm run pack:portable :: 免安装绿色版 → release\HolzynActor-Portable-1.1.0-beta.2.exe
```

或一键执行 `desktop\build-desktop.bat`（步骤 1+2+3 全自动）。

> 首次执行 `npm install` 需联网下载 Electron 二进制（约 100MB）；国内网络慢可设置镜像
> `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`。

## 运行机制

- **后端 sidecar**：`java -jar holzyn-actor.jar`（`resources/runtime/bin/java.exe`），端口自动挑选**随机空闲端口**（不占用开发者本机固定端口 18080，避免与开发实例冲突）。
- **数据目录**：`%LOCALAPPDATA%\HolzynActor\{data,uploads,logs}`——H2 数据库与上传文件都在这里，卸载/删除不影响用户数据；首次启动（data 为空）自动注入示例世界「青禾村」。
- **日志**：`%LOCALAPPDATA%\HolzynActor\logs\holzyn-actor.log`（应用日志）与 `holzyn-actor-console.log`（后端控制台输出）。
- **后台运行**：关闭窗口默认最小化到**系统托盘**，世界继续在后台推进（无人值守 · 世界不停止）——点击托盘图标回到主界面，托盘菜单「退出」才真正结束程序；设置页「更新与版本 → 后台运行」可改为「关闭窗口=直接退出程序」（选择持久化于 `%LOCALAPPDATA%\HolzynActor\desktop-settings.json`）。
- **退出**：从托盘菜单「退出」→ 先优雅停后端（后端 `/api/system/shutdown`，H2 干净落盘）再退出应用（单实例锁，重复双击只会聚焦已有窗口）。

## 自动更新（U1+U2+U3+U4，v1.1.0-beta.2 起）

桌面端接入 **electron-updater（GitHub Releases provider）**，安装版从 v1.1.0-beta.2 起支持自动更新：

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

## 常见问题

| 现象 | 处理 |
|---|---|
| 启动后白屏/长时间无窗口 | 看 `%LOCALAPPDATA%\HolzynActor\logs\holzyn-actor-console.log`；多为首次注入示例世界耗时（≤120s） |
| 提示「后端启动超时」 | 打开上面的日志，通常是端口被占/数据目录被其他实例占用 |
| 想重置数据 | 删除 `%LOCALAPPDATA%\HolzynActor\data` 后重启（会重新注入示例世界） |
