'use strict';

/**
 * HolzynActor 桌面端主进程（Electron）。
 *
 * 职责：
 *  1. 作为 sidecar 启动内置后端（随包裁剪 JRE + Spring Boot fat jar），数据目录固定在
 *     %LOCALAPPDATA%\HolzynActor（首次运行自动注入示例世界「青禾村」）；
 *  2. 后端就绪后，用 Chromium 内核打开应用窗口（http://127.0.0.1:<随机空闲端口>），
 *     即「双击 exe → 弹出应用窗口」的桌面体验；
 *  3. 关闭窗口 → 停掉后端子进程 → 退出应用（单实例锁防重复启动）。
 *
 * 目录布局（打包后）：
 *   <app>/resources/backend/holzyn-actor.jar   后端 fat jar
 *   <app>/resources/runtime/                    裁剪 JRE（jlink 生成，62MB）
 *   数据：%LOCALAPPDATA%\HolzynActor\{data,uploads,logs}
 */

const { app, BrowserWindow, dialog, shell } = require('electron');
const { spawn } = require('child_process');
const net = require('net');
const path = require('path');
const fs = require('fs');

const APP_NAME = 'HolzynActor';
const JAR_FILE = 'holzyn-actor.jar';
const BOOT_TIMEOUT_MS = 120_000; // 首次启动含示例世界注入，放宽到 120s

// 调试支持：设置 HOLZYN_DESKTOP_DEBUG_PORT=9333 时开启 CDP（自动化验证/远程调试用，正常使用不设置）
if (process.env.HOLZYN_DESKTOP_DEBUG_PORT) {
  app.commandLine.appendSwitch('remote-debugging-port', process.env.HOLZYN_DESKTOP_DEBUG_PORT);
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
  let quitting = false;
  let backendStarted = false;

  const isDev = !app.isPackaged;
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

  // ---- 后端/运行时定位 ----
  function resolveBackend() {
    if (isDev) {
      // 开发模式：直接用仓库构建产物 + 本机 JDK（免打包快速联调）
      const jar = path.resolve(__dirname, '..', 'backend', 'target', 'holzyn-actor-1.0.0-beta.1.jar');
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

  function getFreePort() {
    return new Promise((resolve, reject) => {
      const srv = net.createServer();
      srv.unref();
      srv.on('error', reject);
      srv.listen(0, '127.0.0.1', () => {
        const port = srv.address().port;
        srv.close(() => resolve(port));
      });
    });
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

  function stopBackend() {
    if (!backend) return;
    try {
      if (process.platform === 'win32') {
        // 确保 java 子进程树一并退出
        spawn('taskkill', ['/pid', String(backend.pid), '/t', '/f'], { windowsHide: true });
      } else {
        backend.kill('SIGTERM');
      }
    } catch (_) { /* ignore */ }
    backend = null;
  }

  async function startBackend() {
    const { jar, java } = resolveBackend();
    if (!fs.existsSync(jar)) {
      dialog.showErrorBox(APP_NAME, `未找到后端程序：\n${jar}\n\n请先构建后端（仓库根 build.bat 或 desktop 目录 npm run build:backend）。`);
      app.quit();
      return null;
    }
    if (!fs.existsSync(java)) {
      dialog.showErrorBox(APP_NAME, `未找到 Java 运行时：\n${java}`);
      app.quit();
      return null;
    }

    const port = await getFreePort();
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

    backend = spawn(java, ['-jar', jar], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    backend.stdout.on('data', (d) => log(`[out] ${d}`));
    backend.stderr.on('data', (d) => log(`[err] ${d}`));
    backend.on('error', (err) => {
      log(`[spawn-error] ${err}`);
      if (!quitting) {
        dialog.showErrorBox(APP_NAME, `无法启动后端进程：${err.message}`);
        app.quit();
      }
    });
    backend.on('exit', (code) => {
      log(`[exit] backend exited code=${code}`);
      if (backendStarted && !quitting) {
        dialog.showErrorBox(
          APP_NAME,
          `后端服务意外退出（code=${code}）。\n\n日志：${path.join(logDir, 'holzyn-actor-console.log')}`
        );
        app.quit();
      }
    });

    const ok = await waitForHttp(`http://127.0.0.1:${port}`, BOOT_TIMEOUT_MS);
    if (!ok) {
      if (!quitting) {
        dialog.showErrorBox(
          APP_NAME,
          `后端启动超时（${BOOT_TIMEOUT_MS / 1000} 秒未就绪）。\n\n日志：${path.join(logDir, 'holzyn-actor-console.log')}`
        );
        app.quit();
      }
      return null;
    }
    backendStarted = true;
    log(`[ready] backend ready at 127.0.0.1:${port}`);
    return port;
  }

  function createWindow(url) {
    win = new BrowserWindow({
      width: 1440,
      height: 900,
      minWidth: 1024,
      minHeight: 640,
      show: false,
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
    win.once('ready-to-show', () => win.show());
    // 应用内新窗口/外部链接一律交系统默认浏览器，避免弹出空壳窗口
    win.webContents.setWindowOpenHandler(({ url: target }) => {
      if (/^https?:\/\//.test(target)) shell.openExternal(target);
      return { action: 'deny' };
    });
    win.loadURL(url);
    win.on('closed', () => {
      win = null;
      app.quit();
    });
  }

  app.whenReady().then(async () => {
    const port = await startBackend();
    if (!port) return;
    createWindow(`http://127.0.0.1:${port}`);
  });

  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => {
    quitting = true;
    stopBackend();
  });
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
}
