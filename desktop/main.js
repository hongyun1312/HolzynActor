'use strict';

/**
 * HolzynActor 桌面端主进程（Electron）。
 *
 * 职责：
 *  1. 作为 sidecar 启动内置后端（随包裁剪 JRE + Spring Boot fat jar），数据目录固定在
 *     %LOCALAPPDATA%\HolzynActor（首次运行自动注入示例世界「浮生小镇 · 青屿镇」）；
 *  2. 后端就绪后，用 Chromium 内核打开应用窗口（http://127.0.0.1:<随机空闲端口>），
 *     即「双击 exe → 弹出应用窗口」的桌面体验；窗口**无边框 + 16:9**（基准逻辑尺寸 1280×720，
 *     见 window-geometry.js）：标题栏由前端顶栏自绘（拖拽区 + 最小化/最大化/关闭三键），
 *     每次打开都按 1280×720 开、屏幕装不下时等比缩小，拖拽缩放时保持 16:9；
 *  3. 关闭窗口 → 停掉后端子进程 → 退出应用（单实例锁防重复启动）。
 *  4. 自动更新（U1+U2，设计文档《HolzynActor_桌面端更新机制_设计方案_V1.0》）：
 *     - electron-updater（GitHub Releases provider）启动后台检查 + 设置页手动检查；
 *     - 确认安装顺序：优雅停后端（H2 干净落盘）→ 备份 data+uploads → quitAndInstall；
 *     - 新版本启动失败：自动恢复更新前备份数据 + 引导手动重装。
 *
 * 目录布局（打包后）：
 *   <app>/resources/backend/holzyn-actor.jar   后端 fat jar
 *   <app>/resources/runtime/                    裁剪 JRE（jlink 生成，62MB）
 *   数据：%LOCALAPPDATA%\HolzynActor\{data,uploads,logs,backups,updater}
 */

const { app, BrowserWindow, dialog, shell, ipcMain, clipboard, Tray, Menu, nativeImage, screen } = require('electron');
const { spawn } = require('child_process');
const net = require('net');
const path = require('path');
const fs = require('fs');
const windowGeometry = require('./window-geometry');

const APP_NAME = 'HolzynActor';
const APP_ID = 'com.holzyn.actor'; // 与 desktop/package.json build.appId 一致（AUMID/托盘/通知）
const JAR_FILE = 'holzyn-actor.jar';
const BOOT_TIMEOUT_MS = 120_000; // 首次启动含示例世界注入，放宽到 120s
const BACKUP_KEEP = 3;            // 更新前备份滚动保留份数

// ---- 主窗口形态：无边框 + 16:9 沉浸式（UI 设计基准 1280×720，见 window-geometry.js 与设计文档）----
// 无边框（frame:false）后，原生标题栏与三个系统按钮都不再绘制，由渲染进程的顶栏承担：
// 顶栏是拖拽区（-webkit-app-region: drag），右侧自绘「最小化 / 最大化 / 关闭」控件（WindowControls.vue），
// 通过下面的 holzyn:window:* IPC 回到主进程执行真实窗口操作。thickFrame 保留 Windows 的
// 阴影与边框热区（无可见边框，但可以拖拽缩放、可以贴边分屏）。
const WINDOW_ASPECT_RATIO = windowGeometry.ASPECT_RATIO;
const WINDOW_BASE_WIDTH = windowGeometry.BASE_WIDTH;
const WINDOW_BASE_HEIGHT = windowGeometry.BASE_HEIGHT;
const WINDOW_MIN_WIDTH = windowGeometry.MIN_WIDTH;
const WINDOW_MIN_HEIGHT = windowGeometry.MIN_HEIGHT;

// ---- 启动闪屏（IDEA 式：点击快捷方式立即弹出等待弹窗，消除「点击 → 界面打开」的空窗期）----
const SPLASH_HTML = 'splash.html';
const SPLASH_PRELOAD = 'splash-preload.js';
// 启动闪屏：尺寸一比一复刻 IntelliJ IDEA 2025.2 真实启动画面 splash.png（640×400，16:10）
const SPLASH_WIDTH = 640;
const SPLASH_HEIGHT = 400;
const SPLASH_CLOSE_DELAY_MS = 340; // 闪屏淡出后再销毁
// 退出过渡延迟：真实关闭耗时 <300ms 时完全不显示遮罩（研究结论：<300ms 的加载态会被当成卡顿闪烁；
// 300ms 后才浮出「正在关闭」），修掉 JVM 不退出后正常路径约 0.4s，只有异常路径才看得到遮罩。
const CLOSE_OVERLAY_DELAY_MS = 300;

/** 后端启动阶段：按后端控制台日志特征串命中 → 驱动闪屏「真实进度」；时间兜底保证平滑前进 */
const BOOT_STAGES = [
  { key: 'db',     re: /\[数据库\]/,   label: '正在校验世界数据库…', span: [18, 32] },
  { key: 'demo',   re: /\[示例世界\]/, label: '正在注入示例世界…',   span: [34, 46] },
  { key: 'ai',     re: /\[SpringAI\]/, label: '正在装载 AI 意识层…', span: [48, 62] },
  { key: 'clock',  re: /\[世界时钟\]|Tomcat started|Started .*Application/, label: '正在唤醒世界时钟…', span: [64, 76] },
  { key: 'engine', re: /\[世界引擎\]/, label: '正在预热角色决策…',   span: [78, 88] },
];
const GITHUB_OWNER = 'hongyun1312';
const GITHUB_REPO = 'HolzynActor';
const RELEASE_PAGE_URL = `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases`;
const RELEASE_API_URL = `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest`;

// ---- 调试支持：设置 HOLZYN_DESKTOP_DEBUG_PORT=9333 时开启 CDP（自动化验证/远程调试用，正常使用不设置）----
if (process.env.HOLZYN_DESKTOP_DEBUG_PORT) {
  app.commandLine.appendSwitch('remote-debugging-port', process.env.HOLZYN_DESKTOP_DEBUG_PORT);
}

// ---- electron-updater（打包模式必须；开发模式经 dev-app-update.yml 联调）----
let autoUpdater = null;
try {
  autoUpdater = require('electron-updater').autoUpdater;
} catch (_) {
  autoUpdater = null;
}

// ---- 单实例锁（微信式：重复点击聚焦已有窗口）----
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  main();
}

function main() {
  let win = null;
  let backend = null;
  let backendPort = null;
  let quitting = false;
  let backendStarted = false;
  let intentionalBackendStop = false; // 更新/冒烟演练主动停后端时，不按「意外退出」处理
  let splash = null;          // 启动闪屏窗口（点击快捷方式后立即弹出，覆盖后端启动空窗期）
  let startupRunning = false; // 防止并发触发后端启动（初始启动 / 闪屏「重试」）
  let splashPreviewTimer = null; // 预览模式的进度循环定时器
  let closeOverlayTimer = null;  // 「正在关闭」遮罩的延迟定时器（超时才显示，避免闪一下）
  let closeOverlayShown = false; // 遮罩是否已推送渲染进程（防重复注入）

  const isDev = !app.isPackaged;
  const mockMode = process.env.HOLZYN_UPDATER_MOCK === '1'; // 开发冒烟：模拟「发现新版本→下载→可安装」
  // 闪屏预览模式：HOLZYN_SPLASH_PREVIEW=1 时只展示闪屏（进度循环动画），不启动后端、不自动关闭，供设计评审
  const splashPreview = process.env.HOLZYN_SPLASH_PREVIEW === '1';
  // U4 便携版检测：electron-builder portable 目标运行时注入 PORTABLE_EXECUTABLE_DIR；
  // 便携版不支持 electron-updater 自动安装 → 引导下载（设计文档 §12）
  const isPortable = app.isPackaged && !!process.env.PORTABLE_EXECUTABLE_DIR;
  const dataRoot = process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'HolzynActor')
    : path.join(app.getPath('userData'), 'HolzynActor');
  const logDir = path.join(dataRoot, 'logs');
  fs.mkdirSync(logDir, { recursive: true });
  const consoleLog = fs.createWriteStream(path.join(logDir, 'holzyn-actor-console.log'), { flags: 'a' });

  function log(line) {
    const t = new Date().toISOString();
    consoleLog.write(`[${t}] ${line}\n`);
  }

  // ==================== 更新器状态与事件（→ 渲染进程） ====================
  const updaterState = {
    enabled: !!autoUpdater && !isPortable, // 便携版自动安装不可用（走引导下载）
    portable: isPortable,
    isDev,
    mock: mockMode,
    currentVersion: app.getVersion(),
    status: 'idle',            // idle | checking | available | downloading | downloaded | not-available | error | installing
    info: null,                // UpdateInfo（version / releaseNotes / releaseName / files…）
    progress: null,            // { percent, transferred, total, bytesPerSecond }
    error: null,
    autoCheck: true,
    channel: 'latest',         // latest(stable) | beta
  };

  function pushUpdaterState() {
    if (win && !win.isDestroyed()) {
      win.webContents.send('holzyn:updater:state', { ...updaterState });
    }
  }

  function loadUpdaterSettings() {
    try {
      const s = JSON.parse(fs.readFileSync(path.join(dataRoot, 'updater', 'settings.json'), 'utf8'));
      updaterState.autoCheck = s.autoCheck !== false;
      updaterState.channel = s.channel || 'latest';
    } catch (_) { /* 首次运行/损坏：使用默认值 */ }
  }

  function saveUpdaterSettings() {
    fs.mkdirSync(path.join(dataRoot, 'updater'), { recursive: true });
    fs.writeFileSync(
      path.join(dataRoot, 'updater', 'settings.json'),
      JSON.stringify({ autoCheck: updaterState.autoCheck, channel: updaterState.channel }, null, 2)
    );
  }

  // ==================== 桌面端行为设置（后台运行 / 系统托盘） ====================
  const desktopSettingsFile = () => path.join(dataRoot, 'desktop-settings.json');
  let closeToTray = true; // 关闭窗口 → 最小化到系统托盘（后台运行），世界继续；默认开启
  // 后端端口记忆：窗口用 http://127.0.0.1:<port> 打开，而 localStorage 按 origin（含端口）隔离——
  // 端口每次启动都变，等于每次启动都换一份空的本地状态（主题/布局/教程进度/引导标记全丢）。
  // 所以优先复用上次的端口，只有被占用时才回退随机端口。
  let preferredPort = null;

  function loadDesktopSettings() {
    try {
      const s = JSON.parse(fs.readFileSync(desktopSettingsFile(), 'utf8'));
      if (typeof s.closeToTray === 'boolean') closeToTray = s.closeToTray;
      const p = Number(s.preferredPort);
      if (Number.isInteger(p) && p > 0 && p < 65536) preferredPort = p;
    } catch (_) { /* 首次运行/损坏：使用默认值 */ }
  }

  function saveDesktopSettings() {
    try {
      fs.mkdirSync(dataRoot, { recursive: true });
      fs.writeFileSync(desktopSettingsFile(), JSON.stringify({ closeToTray, preferredPort }, null, 2));
    } catch (_) { /* 忽略写入失败 */ }
  }

  function configureUpdater() {
    if (!autoUpdater) return;
    autoUpdater.autoDownload = true;          // 发现即下载，但不自动安装（安装时机由玩家确认）
    autoUpdater.autoInstallOnAppQuit = false; // 保证自定义「停后端→备份→安装」顺序执行
    autoUpdater.allowDowngrade = true;        // U3 双通道：beta 玩家切回 stable（降级）或回滚重装旧版时允许
    // 通道语义：'latest'=stable（latest.yml）/ 'beta'（latest-beta.yml）；默认 stable
    autoUpdater.channel = updaterState.channel === 'beta' ? 'beta' : 'latest';
    autoUpdater.logger = {
      info: (m) => log(`[updater] ${m}`),
      warn: (m) => log(`[updater] ${m}`),
      error: (m) => log(`[updater] ${m}`),
      debug: (m) => log(`[updater] ${m}`),
    };
    autoUpdater.on('checking-for-update', () => {
      updaterState.status = 'checking';
      updaterState.error = null;
      pushUpdaterState();
    });
    autoUpdater.on('update-available', (info) => {
      updaterState.status = 'available';
      updaterState.info = info;
      updaterState.error = null;
      pushUpdaterState();
    });
    autoUpdater.on('update-not-available', (info) => {
      updaterState.status = 'not-available';
      updaterState.info = info;
      updaterState.error = null;
      pushUpdaterState();
    });
    autoUpdater.on('download-progress', (p) => {
      updaterState.status = 'downloading';
      updaterState.progress = p;
      updaterState.error = null;
      pushUpdaterState();
    });
    autoUpdater.on('update-downloaded', (info) => {
      updaterState.status = 'downloaded';
      updaterState.info = info;
      updaterState.error = null;
      pushUpdaterState();
    });
    autoUpdater.on('error', (err) => {
      updaterState.status = 'error';
      updaterState.error = String((err && err.message) || err);
      log(`[updater] error: ${updaterState.error}`);
      pushUpdaterState();
    });
  }

  /** 开发冒烟模式：模拟「发现新版本 → 下载 → 就绪」，用于不发布真实版本的 UI/备份/顺序联调 */
  function mockCheckForUpdate() {
    updaterState.status = 'checking';
    updaterState.error = null;
    pushUpdaterState();
    setTimeout(() => {
      updaterState.status = 'available';
      updaterState.info = {
        version: '1.2.0-beta.1',
        releaseName: 'v1.2.0-beta.1',
        releaseNotes: '【本次世界更真实】角色决策更贴近真实动机；对谈记忆更连贯；群聊更自然；世界演化更细腻。',
      };
      pushUpdaterState();
      let pct = 0;
      const iv = setInterval(() => {
        pct = Math.min(100, pct + 7);
        updaterState.status = 'downloading';
        updaterState.progress = {
          percent: pct,
          transferred: Math.round((pct / 100) * 260 * 1024 * 1024),
          total: 260 * 1024 * 1024,
          bytesPerSecond: 4 * 1024 * 1024,
        };
        pushUpdaterState();
        if (pct >= 100) {
          clearInterval(iv);
          updaterState.status = 'downloaded';
          pushUpdaterState();
        }
      }, 180);
    }, 700);
  }

  /**
   * 便携版引导检查（U4，设计文档 §12）：electron-updater 不支持 portable（无 latest.yml），
   * 改经 GitHub Releases API 拉取最新 Release；比当前版本新 → available（引导打开下载页/复制链接），
   * 否则 not-available。便携版不做自动下载/安装。
   */
  async function portableCheckForUpdate() {
    updaterState.status = 'checking';
    updaterState.error = null;
    pushUpdaterState();
    try {
      const res = await fetch(RELEASE_API_URL, {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'HolzynActor' },
        signal: AbortSignal.timeout(10000),
      });
      if (!res.ok) throw new Error(`GitHub API ${res.status}`);
      const j = await res.json();
      const remote = j.tag_name || '';
      const current = app.getVersion();
      // 便携版仅关心「正式版 vs 当前」：tag v1.2.0 → 版本 1.2.0
      const remoteV = remote.replace(/^v/i, '');
      if (remoteV && remoteV !== current && !isVersionLower(remoteV, current)) {
        updaterState.status = 'available';
        updaterState.info = {
          version: remoteV,
          releaseName: j.name || remote,
          releaseNotes: (j.body || '').slice(0, 2000),
          portable: true, // 标记：需引导下载而非自动安装
        };
      } else {
        updaterState.status = 'not-available';
        updaterState.info = null;
      }
      pushUpdaterState();
    } catch (e) {
      updaterState.status = 'error';
      updaterState.error = `便携版更新检查失败：${(e && e.message) || e}`;
      pushUpdaterState();
    }
  }

  function checkForUpdate() {
    if (mockMode) { mockCheckForUpdate(); return; }
    if (isPortable) { portableCheckForUpdate(); return; }
    if (!autoUpdater || !app.isPackaged && !fs.existsSync(path.join(__dirname, 'dev-app-update.yml'))) {
      updaterState.status = 'error';
      updaterState.error = isDev ? '开发模式未配置 dev-app-update.yml，无法检查更新' : '更新模块不可用';
      pushUpdaterState();
      return;
    }
    autoUpdater.checkForUpdates().catch((err) => {
      updaterState.status = 'error';
      updaterState.error = String((err && err.message) || err);
      pushUpdaterState();
    });
  }

  // ==================== 更新前备份 / 回滚标记（U2 数据安全） ====================

  /** 更新前自动备份 data+uploads → backups/pre-update-<newVersion>-<时间戳>；滚动保留 3 份 */
  function createPreUpdateBackup(newVersion) {
    const backupsDir = path.join(dataRoot, 'backups');
    fs.mkdirSync(backupsDir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const target = path.join(backupsDir, `pre-update-${newVersion || 'unknown'}-${stamp}`);
    fs.mkdirSync(target, { recursive: true });
    const copied = [];
    for (const sub of ['data', 'uploads']) {
      const src = path.join(dataRoot, sub);
      if (fs.existsSync(src)) {
        fs.cpSync(src, path.join(target, sub), { recursive: true });
        copied.push(sub);
      }
    }
    fs.writeFileSync(
      path.join(target, 'manifest.json'),
      JSON.stringify({ appVersion: app.getVersion(), newVersion, createdAt: new Date().toISOString(), dirs: copied }, null, 2)
    );
    log(`[updater] 备份完成: ${target} (${copied.join(',')})`);

    // 滚动清理：只保留最近 BACKUP_KEEP 份
    const dirs = fs.readdirSync(backupsDir)
      .filter((n) => n.startsWith('pre-update-'))
      .map((n) => ({ n, t: fs.statSync(path.join(backupsDir, n)).mtimeMs }))
      .sort((a, b) => b.t - a.t);
    for (const d of dirs.slice(BACKUP_KEEP)) {
      try { fs.rmSync(path.join(backupsDir, d.n), { recursive: true, force: true }); } catch (_) { /* ignore */ }
    }
    return target;
  }

  function updaterDir() { return path.join(dataRoot, 'updater'); }

  function writePendingInstall(newVersion, backupDir) {
    fs.mkdirSync(updaterDir(), { recursive: true });
    const pending = { newVersion, backupDir, installedAt: new Date().toISOString(), attempts: 0 };
    fs.writeFileSync(path.join(updaterDir(), 'pending-install.json'), JSON.stringify(pending, null, 2));
    return pending;
  }

  function readPendingInstall() {
    try { return JSON.parse(fs.readFileSync(path.join(updaterDir(), 'pending-install.json'), 'utf8')); } catch (_) { return null; }
  }

  function clearPendingInstall() {
    try { fs.rmSync(path.join(updaterDir(), 'pending-install.json'), { force: true }); } catch (_) { /* ignore */ }
  }

  function recordLastGood() {
    fs.mkdirSync(updaterDir(), { recursive: true });
    fs.writeFileSync(
      path.join(updaterDir(), 'last-good-version.json'),
      JSON.stringify({ version: app.getVersion(), bootAt: new Date().toISOString() }, null, 2)
    );
  }

  /** 新版本启动失败：把最新备份的 data/uploads 拷回数据目录（当前损坏数据先移走留痕） */
  function restoreBackup(backupDir) {
    if (!backupDir || !fs.existsSync(backupDir)) return false;
    let ok = true;
    for (const sub of ['data', 'uploads']) {
      const src = path.join(backupDir, sub);
      if (!fs.existsSync(src)) continue;
      const dest = path.join(dataRoot, sub);
      const corrupt = path.join(dataRoot, `.corrupt-${sub}-${Date.now()}`);
      try {
        if (fs.existsSync(dest)) fs.renameSync(dest, corrupt);
        fs.cpSync(src, dest, { recursive: true });
        fs.rmSync(corrupt, { recursive: true, force: true });
      } catch (e) {
        log(`[updater] 恢复 ${sub} 失败: ${e}`);
        ok = false;
      }
    }
    return ok;
  }

  /**
   * U3 自动重装上一正式版（设计文档 §9.2 步骤 4）。
   * <p>新版本启动失败 → 数据已恢复 → 尝试经 electron-updater 拉取更旧的正式版（allowDowngrade 已启用）
   * 并静默重装；若更新源没有更低版本（如失败的就是当前唯一正式版），返回 false 交由调用方引导手动重装。</p>
   * @returns {Promise<boolean>} true=已触发降级重装（应用将退出安装）
   */
  async function reinstallPreviousVersion() {
    if (!autoUpdater || mockMode) return false;
    try {
      // 稳定通道兜底：切到 latest 再检查（beta 玩家失败 → stable 通常更低，可降级）
      autoUpdater.channel = 'latest';
      const result = await autoUpdater.checkForUpdates();
      const remote = result && result.updateInfo && result.updateInfo.version;
      const current = app.getVersion();
      if (!remote || !isVersionLower(remote, current)) {
        log(`[updater] 自动重装：无更低版本可装（remote=${remote}, current=${current}）`);
        return false;
      }
      log(`[updater] 自动重装：发现更低版本 ${remote}，开始下载并静默安装`);
      // 数据已在 handlePostUpdateStartupFailure 中恢复；重装后旧版启动即用已恢复数据
      if (result.downloadPromise) await result.downloadPromise;
      else await autoUpdater.downloadUpdate(); // autoDownload 被外部改过时兜底
      autoUpdater.quitAndInstall(false, true);
      return true;
    } catch (e) {
      log(`[updater] 自动重装失败: ${e}`);
      return false;
    }
  }

  /** 版本号比较：a < b（按 . 分段数值比较，忽略 beta/rc 后缀差异） */
  function isVersionLower(a, b) {
    const pa = String(a).split(/[.\-]/).map((x) => parseInt(x, 10) || 0);
    const pb = String(b).split(/[.\-]/).map((x) => parseInt(x, 10) || 0);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const x = pa[i] || 0, y = pb[i] || 0;
      if (x !== y) return x < y;
    }
    return false;
  }

  /** 启动失败时的回滚处理：恢复备份 + 自动重装旧版（U3）→ 失败再引导手动重装 */
  async function handlePostUpdateStartupFailure(pending) {
    const restored = restoreBackup(pending.backupDir);
    pending.attempts = (pending.attempts || 0) + 1;
    fs.writeFileSync(path.join(updaterDir(), 'pending-install.json'), JSON.stringify(pending, null, 2));
    log(`[updater] 新版本启动失败，数据恢复=${restored}，attempts=${pending.attempts}`);

    // 自动重装上一正式版（数据已恢复；若更新源无更低版本则降级为手动引导）
    if (pending.attempts <= 1) {
      const reinstalling = await reinstallPreviousVersion();
      if (reinstalling) return; // 应用即将退出重装，不再弹窗
    }

    if (pending.attempts >= 2) clearPendingInstall(); // 多次失败不再自动恢复，交给玩家
    dialog.showMessageBox({
      type: 'warning',
      title: `${APP_NAME} · 新版本启动失败`,
      message: `新版本 ${pending.newVersion || ''} 未能正常启动，您的世界数据已${restored ? '自动恢复到更新前状态' : '尝试恢复（可能未完全成功，请勿删除备份目录）'}。`,
      detail: `请手动重装上一版本以继续使用：\n${RELEASE_PAGE_URL}\n\n数据备份位置：${pending.backupDir || path.join(dataRoot, 'backups')}`,
      buttons: ['打开下载页', '打开数据目录', '知道了'],
      defaultId: 0,
      cancelId: 2,
    }).then(({ response }) => {
      if (response === 0) shell.openExternal(RELEASE_PAGE_URL);
      else if (response === 1) shell.openPath(dataRoot);
    });
  }

  // ==================== 启动闪屏（IDEA 式等待弹窗） ====================

  /** 立即弹出启动闪屏：无边框、屏幕居中、不占任务栏（普通层级，可被其他窗口遮挡）；深色 IDEA 风格 + 品牌渐变 */
  function createSplash() {
    if (splash && !splash.isDestroyed()) return;
    splash = new BrowserWindow({
      width: SPLASH_WIDTH,
      height: SPLASH_HEIGHT,
      useContentSize: true,  // 内容区精确等于设定尺寸（640×400，与 IDEA splash.png 一致）
      frame: false,
      thickFrame: false,     // 去掉 Windows 隐形缩放边框，避免内宽多出 2px
      resizable: false,
      maximizable: false,
      fullscreenable: false,
      center: true,
      show: false,
      skipTaskbar: true,
      backgroundColor: '#0B1220',
      title: `${APP_NAME} · 正在启动`,
      webPreferences: {
        preload: path.join(__dirname, SPLASH_PRELOAD),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    });
    splash.setMenuBarVisibility(false);
    splash.setContentSize(SPLASH_WIDTH, SPLASH_HEIGHT); // 强制内容区精确等于 IDEA splash.png 尺寸（640×400）
    splash.loadFile(path.join(__dirname, SPLASH_HTML));
    splash.once('ready-to-show', () => {
      splash.show();
      splash.focus();
    });
    splash.on('closed', () => {
      splash = null;
      if (splashPreviewTimer) { clearInterval(splashPreviewTimer); splashPreviewTimer = null; }
      if (splashPreview) app.quit(); // 预览模式：关掉闪屏即退出应用
    });
  }

  /** 闪屏预览模式：模拟启动进度循环播放（不启动后端、不自动关闭，供设计评审） */
  function runSplashPreview() {
    let idx = 0;
    let pct = 5;
    splashPreviewTimer = setInterval(() => {
      const stage = BOOT_STAGES[Math.min(idx, BOOT_STAGES.length - 1)];
      pct = Math.min(94, pct + 1 + Math.random() * 5);
      sendSplashState({ state: 'loading', progress: Math.round(pct), label: stage.label });
      if (pct >= 94) {
        idx = (idx + 1) % BOOT_STAGES.length;
        pct = BOOT_STAGES[idx].span[0] - 2;
      }
    }, 380);
  }

  /** 推启动状态给闪屏页面（loading 进度 / done 完成 / error 错误） */
  function sendSplashState(state) {
    if (splash && !splash.isDestroyed()) {
      splash.webContents.send('holzyn:splash:state', state);
    }
  }

  /** 销毁闪屏（主窗口就绪淡出后调用） */
  function closeSplash() {
    if (splash && !splash.isDestroyed()) splash.destroy();
    splash = null;
  }

  /**
   * 退出过渡层：延迟 {@link CLOSE_OVERLAY_DELAY_MS} 后才推送「正在关闭」遮罩。
   * <p>为什么要延迟：JVM 退出根因修复后，正常关闭约 0.4s，立刻弹遮罩会变成「闪一下」的假卡顿；
   * 只有真正在等（后端收尾、强杀兜底、更新前备份）时才让用户看到反馈。</p>
   *
   * @param {string} label 遮罩文案
   * @returns {() => void} 取消函数（关闭在延迟窗口内完成时调用，则什么都不显示）
   */
  function scheduleClosingOverlay(label) {
    if (closeOverlayTimer || closeOverlayShown) return () => {};
    closeOverlayTimer = setTimeout(() => {
      closeOverlayTimer = null;
      showClosingOverlay(label);
    }, CLOSE_OVERLAY_DELAY_MS);
    return () => {
      if (closeOverlayTimer) {
        clearTimeout(closeOverlayTimer);
        closeOverlayTimer = null;
      }
    };
  }

  /** 立即让主窗口浮出「正在关闭」遮罩（主窗口不可见时退回启动闪屏状态区，避免无反馈） */
  function showClosingOverlay(label) {
    if (closeOverlayShown) return;
    closeOverlayShown = true;
    if (win && !win.isDestroyed() && !win.webContents.isDestroyed() && win.isVisible()) {
      try {
        win.webContents.send('holzyn:app:closing', { label, hint: '正在安全保存世界数据，请稍候' });
        log(`[quit] 显示关闭过渡层：${label}`);
        return;
      } catch (_) { /* 渲染进程不可用：退回闪屏/静默退出 */ }
    }
    if (splash && !splash.isDestroyed()) {
      // 启动失败时主窗口可能从未创建，只剩闪屏：复用闪屏自己的状态区给反馈
      sendSplashState({ state: 'loading', progress: 100, label });
    }
  }

  /** 收起关闭过渡层（更新流程中止、开发态重启后端等「其实不用退出」的场景） */
  function hideClosingOverlay() {
    if (closeOverlayTimer) { clearTimeout(closeOverlayTimer); closeOverlayTimer = null; }
    if (!closeOverlayShown) return;
    closeOverlayShown = false;
    if (win && !win.isDestroyed() && !win.webContents.isDestroyed()) {
      try { win.webContents.send('holzyn:app:closing-cancel'); } catch (_) { /* ignore */ }
    }
  }

  /** 后端就绪 + 主窗口可见后：闪屏进度拉满并淡出关闭 */
  function finishSplash() {
    sendSplashState({ state: 'done', progress: 100, label: '欢迎来到世界', closing: true });
    setTimeout(() => closeSplash(), SPLASH_CLOSE_DELAY_MS);
  }

  /** 闪屏「重试」：清掉残留进程后重新拉起后端，进度从 0 重新推进 */
  async function retryStartup() {
    if (startupRunning) return;
    startupRunning = true;
    sendSplashState({ state: 'loading', progress: 0, label: '正在重新启动世界引擎…' });
    if (backend) killBackendTree(); // 兜底：上次启动残留的进程先清掉
    backendStarted = false;
    const onStage = (s) => sendSplashState({ state: 'loading', progress: s.progress, label: s.label });
    const result = await startBackend({ onStage, onError: (e) => sendSplashState({ state: 'error', ...e }) });
    startupRunning = false;
    if (result.ok) {
      if (!win || win.isDestroyed()) createWindow(`http://127.0.0.1:${result.port}`);
      createTray();
      scheduleStartupCheck();
    }
  }

  // ==================== 后端 sidecar 生命周期 ====================

  function resolveBackend() {
    if (isDev) {
      // 开发模式：直接用仓库构建产物 + 本机 JDK（免打包快速联调）
      const jar = path.resolve(__dirname, '..', 'backend', 'target', 'holzyn-actor-1.1.1-beta.1.jar');
      const java = process.env.JAVA_HOME
        ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
        : 'java';
      return { jar, java };
    }
    return {
      jar: path.join(process.resourcesPath, 'backend', JAR_FILE),
      java: path.join(process.resourcesPath, 'runtime', 'bin', 'java.exe'),
    };
  }

  /**
   * 探测单个端口是否可监听；可用返回端口号，被占用/失败返回 null。
   *
   * @param {number} port 0 表示由系统分配空闲端口
   * @returns {Promise<number|null>}
   */
  function probePort(port) {
    return new Promise((resolve) => {
      const srv = net.createServer();
      srv.unref();
      srv.once('error', () => resolve(null));
      srv.listen(port, '127.0.0.1', () => {
        const p = srv.address().port;
        srv.close(() => resolve(p));
      });
    });
  }

  /**
   * 取后端端口：优先复用上次端口（保证窗口 origin 稳定 → localStorage 连续），
   * 被占用时才回退系统随机端口。
   *
   * <p>2026-10-23 修复：旧实现每次启动都用 `listen(0)` 随机端口，导致窗口 origin 每次都变；
   * Chromium 的 localStorage 按 origin（scheme+host+port）隔离，于是「已跳过/看完的新手引导」
   * 「主题与布局偏好」「教程进度」在每次重启后全部归零，表现为引导反复出现。</p>
   */
  async function getFreePort() {
    if (preferredPort) {
      const p = await probePort(preferredPort);
      if (p) return p;
      log(`[window] 上次端口 ${preferredPort} 被占用，回退随机端口（本次本地偏好可能与上次不连续）`);
    }
    return (await probePort(0)) || 0;
  }

  async function waitForHttp(url, timeoutMs) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      try {
        const res = await fetch(url, { signal: AbortSignal.timeout(2000) });
        if (res.ok || res.status < 500) return true;
      } catch (_) { /* 未就绪，重试 */ }
      await new Promise((r) => setTimeout(r, 500));
    }
    return false;
  }

  function waitForProcExit(proc, ms) {
    return new Promise((resolve) => {
      if (!proc || proc.exitCode !== null) return resolve(true);
      const t = setTimeout(() => resolve(false), ms);
      proc.once('exit', () => { clearTimeout(t); resolve(true); });
    });
  }

  function killBackendTree() {
    if (!backend) return;
    try {
      if (process.platform === 'win32') {
        spawn('taskkill', ['/pid', String(backend.pid), '/t', '/f'], { windowsHide: true });
      } else {
        backend.kill('SIGTERM');
      }
    } catch (_) { /* ignore */ }
    backend = null;
  }

  /**
   * 优雅停后端：先请求 /api/system/shutdown（H2 干净落盘），超时再强杀进程树。
   * <p>关键：无论优雅退出还是强杀，都<b>等待进程真正退出</b>后才返回——
   * 否则 H2 文件锁未释放，紧随其后的更新前备份会 EBUSY（本机冒烟 2026-10-15 实测踩坑：
   * 页面 SSE 长连接让 Tomcat graceful shutdown 挂起 >8s → 强杀后立刻复制 → 锁冲突）。</p>
   */
  async function stopBackendGracefully() {
    if (!backend) return;
    if (backendPort) {
      try {
        await fetch(`http://127.0.0.1:${backendPort}/api/system/shutdown`, {
          method: 'POST',
          signal: AbortSignal.timeout(5000),
        }).catch(() => { /* 端点可能未就绪，走进程级兜底 */ });
      } catch (_) { /* ignore */ }
    }
    let exited = await waitForProcExit(backend, 8000);
    if (!exited) {
      log('[backend] 优雅退出超时，强杀进程树');
      const proc = backend;
      killBackendTree();
      // 等待被强杀的进程树真正退出（taskkill /t /f 是异步的），释放 H2 文件锁后再备份
      exited = await waitForProcExit(proc, 8000);
      if (!exited) log('[backend] 警告：强杀后进程仍未退出（文件锁可能残留）');
    } else {
      backend = null;
    }
  }

  /** 启动进度追踪：后端控制台日志命中阶段标记 → 推进「真实进度」；无标记时按时间兜底平滑前进 */
  function createBootStageTracker(onStage) {
    const matched = new Set();
    const matchedAt = new Map();
    const bootStart = Date.now();
    let lastPct = 5;

    const labelByTime = (pct) => {
      if (pct >= 76) return BOOT_STAGES[3].label; // clock
      if (pct >= 62) return BOOT_STAGES[2].label; // ai
      if (pct >= 46) return BOOT_STAGES[1].label; // demo
      if (pct >= 32) return BOOT_STAGES[0].label; // db
      return '正在启动世界引擎…';
    };

    const current = () => {
      const elapsed = Date.now() - bootStart;
      // 时间兜底：线性爬到 85（真实就绪由 /api/health 收尾）
      const timePct = Math.min(88, 5 + (elapsed / BOOT_TIMEOUT_MS) * 80);
      let pct = Math.max(lastPct, timePct);
      const highest = [...BOOT_STAGES].reverse().find((s) => matched.has(s.key));
      if (highest) {
        // 命中阶段后在区间内缓慢爬升（15s 内走完区间），观感接近真实加载
        const since = Date.now() - (matchedAt.get(highest.key) || bootStart);
        const ramp = highest.span[0] + Math.min(1, since / 15000) * (highest.span[1] - highest.span[0]);
        pct = Math.max(pct, ramp);
      }
      pct = Math.max(0, Math.min(88, Math.round(pct)));
      return { pct, label: highest ? highest.label : labelByTime(pct) };
    };

    const emit = () => {
      const { pct, label } = current();
      onStage({ progress: pct, label });
    };

    return {
      applyLine(line) {
        let changed = false;
        for (const s of BOOT_STAGES) {
          if (!matched.has(s.key) && s.re.test(line)) {
            matched.add(s.key);
            matchedAt.set(s.key, Date.now());
            lastPct = Math.max(lastPct, s.span[0]);
            changed = true;
          }
        }
        if (changed) emit();
      },
      emit,
    };
  }

  async function startBackend({ onStage = null, onError = null } = {}) {
    const { jar, java } = resolveBackend();
    if (!fs.existsSync(jar)) {
      const err = {
        title: '未找到后端程序',
        detail: `未找到后端程序：\n${jar}\n\n请先构建后端（仓库根 build.bat 或 desktop 目录 npm run build:backend）。`,
      };
      if (onError) { onError(err); } else { dialog.showErrorBox(APP_NAME, err.detail); app.quit(); }
      return { ok: false };
    }
    if (!fs.existsSync(java)) {
      const err = { title: '未找到 Java 运行时', detail: `未找到 Java 运行时：\n${java}` };
      if (onError) { onError(err); } else { dialog.showErrorBox(APP_NAME, err.detail); app.quit(); }
      return { ok: false };
    }

    const port = await getFreePort();
    backendPort = port;
    // 记住本次端口：下次启动继续复用，保证窗口 origin（进而 localStorage）稳定
    if (port && port !== preferredPort) {
      preferredPort = port;
      saveDesktopSettings();
    }
    const env = {
      ...process.env,
      HOLOZYN_ACTOR_DATA_DIR: path.join(dataRoot, 'data'),
      HOLOZYN_ACTOR_UPLOAD_DIR: path.join(dataRoot, 'uploads'),
      HOLOZYN_ACTOR_LOG_FILE: path.join(logDir, 'holzyn-actor.log'),
      // 桌面端恒用本地 H2；调试用 H2 TCP 端口留白，避免与开发者本机实例冲突
      HOLOZYN_ACTOR_H2_TCP_ENABLED: 'false',
      SERVER_PORT: String(port),
    };
    delete env.HOLOZYN_ACTOR_DB_URL; // 屏蔽外部数据源覆盖，桌面端始终本地

    // 闪屏进度：日志阶段标记 + 时间兜底（无 onStage 时不创建，保持原有行为）
    const tracker = onStage ? createBootStageTracker(onStage) : null;
    const nudge = onStage ? setInterval(() => tracker.emit(), 800) : null;
    let failed = false;
    const fail = (err) => {
      if (failed) return;
      failed = true;
      if (nudge) clearInterval(nudge);
      if (onError) { onError({ title: err.title || '启动失败', detail: err.detail || String((err && err.message) || err) }); }
      else if (splash && !splash.isDestroyed()) { sendSplashState({ state: 'error', title: err.title || '启动失败', detail: err.detail || String((err && err.message) || err) }); }
      else { dialog.showErrorBox(APP_NAME, err.detail || String((err && err.message) || err)); app.quit(); }
    };

    backend = spawn(java, ['-jar', jar], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const feedConsole = (d) => {
      const text = d.toString();
      log(`[out] ${text}`);
      if (tracker) for (const line of text.split(/\r?\n/)) tracker.applyLine(line);
    };
    backend.stdout.on('data', feedConsole);
    backend.stderr.on('data', feedConsole);
    backend.on('error', (err) => {
      log(`[spawn-error] ${err}`);
      if (!quitting) fail({ title: '无法启动后端进程', detail: `无法启动后端进程：${err.message}` });
    });
    backend.on('exit', (code) => {
      log(`[exit] backend exited code=${code}`);
      backend = null;
      if (backendStarted) {
        // 就绪后的意外退出：维持原有弹错并退出
        if (!quitting && !intentionalBackendStop) {
          dialog.showErrorBox(
            APP_NAME,
            `后端服务意外退出（code=${code}）。\n\n日志：${path.join(logDir, 'holzyn-actor-console.log')}`
          );
          app.quit();
        }
      } else if (!quitting && !intentionalBackendStop) {
        // 就绪前退出：闪屏内展示错误（可重试/退出）
        fail({ title: '后端进程提前退出', detail: `后端服务在就绪前退出（code=${code}）。\n\n日志：${path.join(logDir, 'holzyn-actor-console.log')}` });
      }
    });

    const ok = await waitForHttp(`http://127.0.0.1:${port}`, BOOT_TIMEOUT_MS);
    if (nudge) clearInterval(nudge);
    if (!ok) {
      if (!quitting) {
        // U2+U3 回滚：更新后首次启动失败 → 恢复备份数据 + 自动重装旧版（成功则退出装旧版；否则引导手动重装）
        const pending = readPendingInstall();
        if (pending) {
          await handlePostUpdateStartupFailure(pending);
          // 自动重装已触发时 quitAndInstall 会接管退出；未触发（引导手动）则正常退出本进程
          app.quit();
          return { ok: false };
        }
        fail({ title: '后端启动超时', detail: `后端启动超时（${BOOT_TIMEOUT_MS / 1000} 秒未就绪）。\n\n日志：${path.join(logDir, 'holzyn-actor-console.log')}` });
      }
      return { ok: false };
    }
    backendStarted = true;
    // 更新后首次成功启动 → 清除待安装标记（更新成功）
    if (readPendingInstall()) {
      clearPendingInstall();
      log('[updater] 新版本启动成功，更新完成');
    }
    recordLastGood();
    log(`[ready] backend ready at 127.0.0.1:${port}`);
    if (onStage) onStage({ progress: 94, label: '世界已就绪，正在进入…' });
    return { ok: true, port };
  }

  // ==================== 窗口 ====================

  /** 把「当前是否最大化」推给渲染进程（自绘标题栏据此切换「最大化/还原」图标） */
  function pushWindowState() {
    if (!win || win.isDestroyed()) return;
    try {
      win.webContents.send('holzyn:window:maximized', win.isMaximized());
    } catch (_) { /* 窗口正在销毁：忽略 */ }
  }

  /**
   * 16:9 拖拽夹紧：用户手动拖边框时先反算另一边，再 setBounds 落地。
   *
   * <p>为什么不用 `win.setAspectRatio()`：Windows 上不可靠（Electron 文档中 `extraSize`
   * 标注为 macOS 专属），实测拖拽仍会破比例。`will-resize` 在 macOS/Windows 都会触发，
   * 且只由**用户手动拖拽**触发（程序化 setBounds 不触发，不会自我递归）。
   * 最大化/全屏时放行——铺满工作区是系统级预期，16:9 保证的是「窗口化状态与每次打开时」。</p>
   */
  function applyAspectGuard() {
    if (!win || win.isDestroyed()) return;
    win.on('will-resize', (event, newBounds, details) => {
      if (win.isMaximized() || win.isFullScreen()) return;
      const current = win.getBounds();
      const fitted = windowGeometry.constrainToAspect(newBounds, details && details.edge, current);
      const unchanged = fitted.x === newBounds.x && fitted.y === newBounds.y
        && fitted.width === newBounds.width && fitted.height === newBounds.height;
      if (unchanged) return;
      event.preventDefault();
      win.setBounds(fitted);
    });
  }

  /**
   * 从最大化/托盘恢复后自检比例：系统还原的是「最大化之前的 bounds」，
   * 正常路径一定还是 16:9；但外部缩放工具/多屏切换可能把尺寸改飘，这里兜一层修正。
   */
  function ensureAspectAfterRestore() {
    if (!win || win.isDestroyed()) return;
    if (win.isMaximized() || win.isFullScreen() || win.isMinimized()) return;
    const bounds = win.getBounds();
    if (windowGeometry.isAspectRatioOk(bounds)) return;
    const fixed = windowGeometry.normalizeToAspect(bounds);
    log(`[window] 恢复后尺寸 ${bounds.width}x${bounds.height} 非 16:9 → 修正为 ${fixed.width}x${fixed.height}`);
    win.setBounds(fixed);
  }

  function createWindow(url) {
    // 每次打开都按 16:9 基准 1280×720 开（屏幕装不下时等比缩小，最小 960×540）
    const size = windowGeometry.fitToWorkArea(screen.getPrimaryDisplay().workAreaSize);
    log(`[window] 打开主窗口 ${size.width}x${size.height}（基准 ${WINDOW_BASE_WIDTH}x${WINDOW_BASE_HEIGHT}，比例 ${WINDOW_ASPECT_RATIO.toFixed(3)} = 16:9，无边框）`);
    win = new BrowserWindow({
      width: size.width,
      height: size.height,
      minWidth: WINDOW_MIN_WIDTH,
      minHeight: WINDOW_MIN_HEIGHT,
      show: false,
      // 无边框 + 沉浸式：标题栏/边框交给我们自己的顶栏（拖拽区 + 自绘三键）
      frame: false,
      thickFrame: true, // 保留 Windows 阴影与缩放热区（贴边分屏、拖拽缩放仍可用）
      titleBarStyle: 'hidden',
      autoHideMenuBar: true,
      backgroundColor: '#0B1220',
      title: `${APP_NAME} · 全境众生 · 浮生`,
      icon: path.join(__dirname, 'build', 'icon.png'),
      webPreferences: {
        preload: path.join(__dirname, 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    });
    win.setMenuBarVisibility(false);
    win.center();
    applyAspectGuard();
    win.on('maximize', pushWindowState);
    win.on('unmaximize', () => { ensureAspectAfterRestore(); pushWindowState(); });
    win.on('restore', () => { ensureAspectAfterRestore(); pushWindowState(); });
    win.webContents.on('did-finish-load', pushWindowState);
    win.once('ready-to-show', () => {
      win.show();
      win.focus();
      maybeShowSecurityNotice(); // U4：无签名首次运行展示安全说明（标记位防重复）
      finishSplash(); // 主窗口已可见 → 闪屏进度拉满并淡出关闭
    });
    // 应用内新窗口/外部链接一律交系统默认浏览器，避免弹出空壳窗口
    win.webContents.setWindowOpenHandler(({ url: target }) => {
      if (/^https?:\/\//.test(target)) shell.openExternal(target);
      return { action: 'deny' };
    });
    win.loadURL(url);
    // 关闭窗口：默认最小化到系统托盘（后台运行），世界继续推进；真正退出由托盘菜单/设置决定
    win.on('close', (e) => {
      if (quitting) return;
      if (closeToTray) {
        e.preventDefault();
        hideToTray();
        return;
      }
      // 「关闭窗口=退出」模式：以前直接关窗 → window-all-closed → app.quit() → before-quit 的
      // killBackendTree() 直接强杀后端，H2 不是干净落盘。现在拦住关闭，走统一的
      // 「优雅停后端 → 关窗退出」时序（与托盘退出完全一致，同样有延迟遮罩兜底）。
      e.preventDefault();
      quitApp();
    });
    win.on('closed', () => {
      win = null;
    });
  }

  // ==================== 系统托盘（后台运行） ====================
  let tray = null;
  let trayBalloonShown = false; // 首次隐藏到托盘时气泡提示一次

  function trayIconPath() {
    return app.isPackaged
      ? path.join(process.resourcesPath, 'tray', 'tray-icon.png')
      : path.join(__dirname, 'tray', 'tray-icon.png');
  }

  /** 从托盘恢复/聚焦主界面（窗口隐藏时重新显示） */
  function showMainWindow() {
    if (win && !win.isDestroyed()) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
    }
  }

  /** 关闭窗口 → 隐藏到托盘（后台运行），首次给一次气泡提示 */
  function hideToTray() {
    if (!win) return;
    win.hide();
    if (tray && !trayBalloonShown) {
      trayBalloonShown = true;
      try {
        tray.displayBalloon({
          iconType: 'info',
          title: `${APP_NAME} · 后台运行`,
          content: '窗口已最小化到系统托盘，世界仍在后台运行。点击托盘图标可回到主界面。',
        });
      } catch (_) { /* 个别环境不支持气泡提示，忽略 */ }
    }
  }

  /**
   * 真正退出（托盘「退出」/ 闪屏「退出」/「关闭窗口=退出」三条路径共用）：
   * 先优雅停后端（H2 干净落盘），再退出应用；关得慢时主窗口浮出「正在关闭」遮罩。
   */
  function quitApp() {
    if (quitting) return;
    quitting = true;
    intentionalBackendStop = true;
    scheduleClosingOverlay('正在关闭世界…'); // 关得快就不显示，慢才浮出（不制造闪烁）
    stopBackendGracefully().finally(() => {
      // 已关完：撤掉延迟定时器；已显示的遮罩不撤，直接随窗口一起消失（避免闪回主界面）
      if (closeOverlayTimer) { clearTimeout(closeOverlayTimer); closeOverlayTimer = null; }
      intentionalBackendStop = false;
      app.quit();
    });
  }

  function createTray() {
    if (tray) return;
    let icon = nativeImage.createFromPath(trayIconPath());
    if (icon.isEmpty()) icon = nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png'));
    tray = new Tray(icon);
    tray.setToolTip(`${APP_NAME} · 世界正在后台运行`);
    const menu = Menu.buildFromTemplate([
      { label: '打开主界面', click: () => showMainWindow() },
      { type: 'separator' },
      { label: `退出 ${APP_NAME}`, click: () => quitApp() },
    ]);
    // Windows：左键单击恢复窗口，右键弹出菜单（避免 setContextMenu 吞掉左键事件）
    tray.on('click', () => showMainWindow());
    tray.on('double-click', () => showMainWindow());
    tray.on('right-click', () => tray.popUpContextMenu(menu));
  }

  // ==================== 更新确认安装（玩家确认后） ====================

  /**
   * 确认安装顺序（U2 核心）：
   *  停后端（H2 干净落盘）→ 备份 data+uploads → 记录待安装标记 → quitAndInstall（静默安装并重启）。
   * 备份失败则中止更新（绝不在无备份时更新）。
   */
  async function confirmInstallAndRestart() {
    // 便携版：不自动安装，引导玩家下载（设计文档 §12）
    if (isPortable) {
      shell.openExternal(RELEASE_PAGE_URL);
      clipboard.writeText(RELEASE_PAGE_URL);
      return { ok: true, portable: true, url: RELEASE_PAGE_URL };
    }
    if (updaterState.status !== 'downloaded') {
      return { ok: false, reason: '更新尚未下载完成，无法安装' };
    }
    const newVersion = (updaterState.info && updaterState.info.version) || '';

    updaterState.status = 'installing';
    updaterState.error = null;
    pushUpdaterState();
    log(`[updater] 玩家确认安装 v${newVersion}，开始「停后端→备份→安装」`);

    // 1) 优雅停后端（H2 干净落盘）；主动停止不按「意外退出」弹错。
    //    这一步 + 后面的备份都可能要几秒，延迟遮罩负责「别让玩家以为卡死」。
    intentionalBackendStop = true;
    scheduleClosingOverlay('正在准备更新…');
    await stopBackendGracefully();
    intentionalBackendStop = false;

    // 2) 备份 data+uploads（失败则中止更新）
    let backupDir;
    try {
      backupDir = createPreUpdateBackup(newVersion);
    } catch (e) {
      log(`[updater] 备份失败，中止更新: ${e}`);
      hideClosingOverlay(); // 不更新了：撤掉「正在准备更新」遮罩，把界面还给玩家
      updaterState.status = 'error';
      updaterState.error = `更新前备份失败，已中止更新（${e.message}）。请检查磁盘空间后重试。`;
      pushUpdaterState();
      return { ok: false, reason: updaterState.error };
    }

    if (isDev || mockMode) {
      // 开发冒烟：完整演练「停后端→备份」后，重启后端保持会话可用（不执行真实安装）
      log(`[updater] dev 冒烟模式：备份完成 ${backupDir}，重启后端以继续会话`);
      hideClosingOverlay(); // 会话继续，遮罩必须收起
      backendStarted = false;
      const restart = await startBackend();
      const newPort = restart.ok ? restart.port : null;
      updaterState.status = 'idle';
      pushUpdaterState();
      // 延迟重载窗口，确保本次 invoke 响应先回到渲染进程
      if (newPort && win && !win.isDestroyed()) {
        setTimeout(() => win.loadURL(`http://127.0.0.1:${newPort}`), 800);
      }
      return { ok: true, dev: true, backupDir };
    }

    // 3) 记录待安装标记（供下次启动回滚判定）
    writePendingInstall(newVersion, backupDir);

    // 4) 静默安装并重启（autoInstallOnAppQuit=false 保证顺序已在上方完成）
    try {
      autoUpdater.quitAndInstall(false, true);
    } catch (e) {
      log(`[updater] quitAndInstall 失败: ${e}`);
      updaterState.status = 'error';
      updaterState.error = `启动安装失败：${e.message}。可稍后重启应用时自动完成安装。`;
      pushUpdaterState();
      return { ok: false, reason: updaterState.error };
    }
    return { ok: true };
  }

  // ==================== U4 SmartScreen 首次运行引导（无签名场景） ====================

  /** 无签名下首次运行展示一屏安全说明（设计文档 §10.2）；标记位防重复 */
  function maybeShowSecurityNotice() {
    if (isDev || isPortable) return; // 开发/便携版无需引导
    const markFile = path.join(updaterDir(), 'security-notice-shown.json');
    if (fs.existsSync(markFile)) return;
    dialog.showMessageBox(win || undefined, {
      type: 'info',
      title: `${APP_NAME} · 安全说明`,
      message: '欢迎使用开源世界模拟器 HolzynActor',
      detail:
        '本软件为开源项目（Apache-2.0），未购买商业代码签名证书，因此 Windows 可能提示「Windows 已保护你的电脑」' +
        '——这是无签名开源软件的正常现象，不是病毒。\n\n' +
        '首次安装 / 更新安装时请选择：更多信息 → 仍要运行。\n' +
        '您可在 GitHub Releases 页面核对每个安装包的 sha512 校验和，确保下载完整、未被篡改。\n\n' +
        '您的世界数据保存在 %LOCALAPPDATA%\\HolzynActor，安装/更新/卸载均不影响数据。',
      buttons: ['我已了解，进入世界', '打开校验说明页'],
      defaultId: 0,
      cancelId: 0,
    }).then(({ response }) => {
      if (response === 1) shell.openExternal(`${RELEASE_PAGE_URL}/latest`);
      fs.mkdirSync(updaterDir(), { recursive: true });
      fs.writeFileSync(markFile, JSON.stringify({ shownAt: new Date().toISOString(), version: app.getVersion() }));
    });
  }

  // ==================== IPC（preload 桥） ====================

  function registerIpc() {
    // ===== 无边框窗口的自绘标题栏（WindowControls.vue → preload → 这里）=====
    // 窗口是 frame:false，系统按钮不存在；三个动作都必须回主进程执行真实窗口操作。
    ipcMain.handle('holzyn:window:minimize', () => {
      if (win && !win.isDestroyed()) win.minimize();
    });
    ipcMain.handle('holzyn:window:toggle-maximize', () => {
      if (!win || win.isDestroyed()) return false;
      if (win.isMaximized()) win.unmaximize();
      else win.maximize();
      return win.isMaximized();
    });
    // 关闭按钮走 win.close()，因此仍然复用既有 close 语义：
    // 「关闭到托盘后台运行」或「关闭=退出（优雅停后端 + 关闭遮罩）」由设置决定。
    ipcMain.handle('holzyn:window:close', () => {
      if (win && !win.isDestroyed()) win.close();
    });
    ipcMain.handle('holzyn:window:is-maximized', () => !!(win && !win.isDestroyed() && win.isMaximized()));

    ipcMain.handle('holzyn:updater:get-state', () => ({ ...updaterState }));
    ipcMain.handle('holzyn:updater:check', () => { checkForUpdate(); return { ...updaterState }; });
    ipcMain.handle('holzyn:updater:confirm-install', () => confirmInstallAndRestart());
    ipcMain.handle('holzyn:updater:open-release-page', () => shell.openExternal(RELEASE_PAGE_URL));
    ipcMain.handle('holzyn:updater:open-data-dir', () => shell.openPath(dataRoot));
    ipcMain.handle('holzyn:updater:set-auto-check', (_e, value) => {
      updaterState.autoCheck = value !== false;
      saveUpdaterSettings();
      return { ...updaterState };
    });
    ipcMain.handle('holzyn:updater:set-channel', (_e, value) => {
      // U3 双通道切换：latest(stable) | beta
      if (value !== 'latest' && value !== 'beta') {
        return { ...updaterState, error: '无效的更新通道' };
      }
      updaterState.channel = value;
      saveUpdaterSettings();
      // 重新配置 electron-updater 通道（allowDowngrade 已启用，beta→stable 降级可用）
      if (autoUpdater) {
        autoUpdater.channel = value === 'beta' ? 'beta' : 'latest';
      }
      // 切换后立即触发一次检查，让玩家立刻看到目标通道的最新版本
      checkForUpdate();
      return { ...updaterState };
    });
    ipcMain.handle('holzyn:updater:release-notes', async () => {
      // 拉取 GitHub Release 正文（展示「本次世界更真实」清单摘要）；失败返回 null
      try {
        const res = await fetch(RELEASE_API_URL, {
          headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'HolzynActor' },
          signal: AbortSignal.timeout(10000),
        });
        if (!res.ok) return null;
        const j = await res.json();
        return { tagName: j.tag_name, name: j.name, body: j.body || '', publishedAt: j.published_at };
      } catch (_) { return null; }
    });
    ipcMain.handle('holzyn:app-version', () => app.getVersion());
    // 桌面端行为（后台运行 / 系统托盘）
    ipcMain.handle('holzyn:desktop:get-settings', () => ({ closeToTray, trayAvailable: !!tray }));
    ipcMain.handle('holzyn:desktop:set-close-behavior', (_e, value) => {
      // 'tray'（关闭→最小化到托盘，后台运行）或 'quit'（关闭→直接退出）
      closeToTray = value !== 'quit';
      saveDesktopSettings();
      return { closeToTray };
    });
    // 启动闪屏：错误态操作（打开日志目录 / 重试启动 / 退出）
    ipcMain.handle('holzyn:splash:open-log-dir', () => shell.openPath(logDir));
    ipcMain.handle('holzyn:splash:retry', () => retryStartup());
    ipcMain.handle('holzyn:splash:quit', () => quitApp());
  }

  // ==================== 启动 ====================

  function scheduleStartupCheck() {
    if (!updaterState.autoCheck) return;
    if (mockMode) {
      setTimeout(mockCheckForUpdate, 4000); // 冒烟：窗口稳定后模拟发现新版
      return;
    }
    if (isDev) return; // 开发模式不自动检查（避免未经允许请求 GitHub）；手动检查经 dev-app-update.yml 联调
    setTimeout(() => { checkForUpdate(); }, 6000); // 等窗口渲染稳定后再后台检查，不打扰首屏
  }

  app.whenReady().then(async () => {
    app.setAppUserModelId(APP_ID);
    loadUpdaterSettings();
    loadDesktopSettings();
    configureUpdater();
    registerIpc();
    createSplash(); // 点击快捷方式后立即弹出启动闪屏（覆盖后端启动空窗期，给出即时反馈）
    if (splashPreview) {
      runSplashPreview(); // 预览模式：闪屏一直保留在屏幕上（进度循环动画），不启动后端
      return;
    }
    startupRunning = true;
    const onStage = (s) => sendSplashState({ state: 'loading', progress: s.progress, label: s.label });
    const onError = (e) => sendSplashState({ state: 'error', ...e });
    const result = await startBackend({ onStage, onError });
    startupRunning = false;
    if (!result.ok) return; // 失败由闪屏内展示（打开日志/重试/退出）；更新回滚路径已在内部退出
    createWindow(`http://127.0.0.1:${result.port}`);
    createTray();
    scheduleStartupCheck();
  });

  app.on('window-all-closed', () => {
    // 后台运行模式下窗口是「隐藏」而非关闭，不会走到这里；
    // 仅在「关闭窗口=退出程序」模式下兜底：仍走统一的优雅停后端时序（不再直接 app.quit() 强杀）。
    if (closeToTray) return;
    if (quitting) { app.quit(); return; }
    quitApp();
  });
  app.on('before-quit', () => {
    quitting = true;
    if (splash && !splash.isDestroyed()) splash.destroy();
    splash = null;
    killBackendTree();
  });
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
    }
  });
}
