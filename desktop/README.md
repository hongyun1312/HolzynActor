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

1. 构建后端 fat jar（含前端产物）：仓库根 `build.bat`，产物 `backend\target\holzyn-actor-1.0.0-beta.1.jar`。
2. 生成裁剪 JRE 运行时：`packaging/runtime`（jlink，约 62MB；缺失时 `build-desktop.bat` 会自动生成）。
3. 打包安装包：在 `desktop/` 目录执行：

```bat
npm install
npm run pack          :: NSIS 安装版  → release\HolzynActor-Setup-1.0.0-beta.1.exe
npm run pack:portable :: 免安装绿色版 → release\HolzynActor-Portable-1.0.0-beta.1.exe
```

或一键执行 `desktop\build-desktop.bat`（步骤 1+2+3 全自动）。

> 首次执行 `npm install` 需联网下载 Electron 二进制（约 100MB）；国内网络慢可设置镜像
> `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`。

## 运行机制

- **后端 sidecar**：`java -jar holzyn-actor.jar`（`resources/runtime/bin/java.exe`），端口自动挑选**随机空闲端口**（不占用 8080，避免与开发者本机实例冲突）。
- **数据目录**：`%LOCALAPPDATA%\HolzynActor\{data,uploads,logs}`——H2 数据库与上传文件都在这里，卸载/删除不影响用户数据；首次启动（data 为空）自动注入示例世界「青禾村」。
- **日志**：`%LOCALAPPDATA%\HolzynActor\logs\holzyn-actor.log`（应用日志）与 `holzyn-actor-console.log`（后端控制台输出）。
- **退出**：关闭窗口 → 自动结束后端子进程（单实例锁，重复双击只会聚焦已有窗口）。

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
