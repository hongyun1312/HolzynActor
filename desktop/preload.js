'use strict';

/**
 * HolzynActor 桌面端预加载脚本（沙箱内运行，仅暴露受控 API 给页面）。
 * 职责：
 *  - 只读环境信息（platform / versions / appVersion）；
 *  - 更新器桥（window.holzynDesktop.updater）：状态订阅、手动检查、确认安装、
 *    打开发布页/数据目录、自动检查开关、拉取 Release 更新说明；
 *  - 无边框窗口桥（window.holzynDesktop.window）：主窗口无系统标题栏，
 *    前端顶栏自绘「最小化 / 最大化 / 关闭」（拖拽移动走 CSS 拖拽区，不经过 IPC）；
 *  - 退出过渡层：接收主进程 holzyn:app:closing，在主窗口内注入「正在关闭」遮罩
 *    （不依赖 Vue 路由/挂载状态，退出发生在任何页面都能显示）。
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('holzynDesktop', {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
  /** 应用版本（desktop/package.json，与后端同版本号口径） */
  appVersion: () => ipcRenderer.invoke('holzyn:app-version'),
  /** 更新器桥：桌面端自动更新（electron-updater + GitHub Releases） */
  updater: {
    /** 获取当前更新器状态快照 */
    getState: () => ipcRenderer.invoke('holzyn:updater:get-state'),
    /** 手动触发检查更新（幂等；状态经 onState 推送） */
    check: () => ipcRenderer.invoke('holzyn:updater:check'),
    /** 玩家确认后执行「停后端→备份→静默安装→重启」 */
    confirmInstall: () => ipcRenderer.invoke('holzyn:updater:confirm-install'),
    /** 浏览器打开 GitHub Releases 下载页 */
    openReleasePage: () => ipcRenderer.invoke('holzyn:updater:open-release-page'),
    /** 打开数据目录（%LOCALAPPDATA%\HolzynActor） */
    openDataDir: () => ipcRenderer.invoke('holzyn:updater:open-data-dir'),
    /** 设置启动自动检查开关（持久化） */
    setAutoCheck: (value) => ipcRenderer.invoke('holzyn:updater:set-auto-check', value),
    /** 切换更新通道（latest=stable / beta；持久化并触发一次检查；切回 stable 属降级需 allowDowngrade） */
    setChannel: (value) => ipcRenderer.invoke('holzyn:updater:set-channel', value),
    /** 拉取 GitHub 最新 Release 正文（「本次世界更真实」清单摘要）；无则返回 null */
    fetchReleaseNotes: () => ipcRenderer.invoke('holzyn:updater:release-notes'),
    /** 订阅更新器状态推送；返回取消订阅函数 */
    onState: (cb) => {
      const handler = (_event, state) => cb(state);
      ipcRenderer.on('holzyn:updater:state', handler);
      return () => ipcRenderer.removeListener('holzyn:updater:state', handler);
    },
  },
  /** 桌面端行为桥：后台运行 / 系统托盘 */
  desktop: {
    /** 获取桌面端行为设置（closeToTray：关闭窗口是否最小化到托盘后台运行） */
    getSettings: () => ipcRenderer.invoke('holzyn:desktop:get-settings'),
    /** 设置关闭窗口行为：'tray'（最小化到托盘，后台运行）或 'quit'（直接退出）；持久化 */
    setCloseBehavior: (value) => ipcRenderer.invoke('holzyn:desktop:set-close-behavior', value),
  },
  /**
   * 开发者模式桥（设置页 5 连击解锁后可用）：
   *  - open：打开独立的开发者控制台窗口（单例；已开则聚焦并切到目标路由）；
   *  - restartBackend：「保存并重启后端」——优雅停 JVM → 重新拉起 → 两个窗口重载到新端口；
   *  - close：关闭开发者窗口（页面内「隐藏开发者入口」时可选调用）。
   */
  dev: {
    open: (route) => ipcRenderer.invoke('holzyn:dev:open', route),
    restartBackend: () => ipcRenderer.invoke('holzyn:dev:restart-backend'),
    close: () => ipcRenderer.invoke('holzyn:dev:close'),
  },
  /**
   * 无边框窗口桥：主窗口是 frame:false（无系统标题栏），
   * 由前端顶栏自绘「最小化 / 最大化切换 / 关闭」三个控件（WindowControls.vue）；
   * 拖拽移动窗口靠顶栏的 `-webkit-app-region: drag`，不需要经过这里。
   */
  window: {
    /** 恒为 true：页面据此决定是否渲染自绘窗口控件（纯浏览器环境没有该对象） */
    frameless: true,
    /** 最小化到任务栏 */
    minimize: () => ipcRenderer.invoke('holzyn:window:minimize'),
    /** 最大化 ⇄ 还原；返回操作后的最大化状态 */
    toggleMaximize: () => ipcRenderer.invoke('holzyn:window:toggle-maximize'),
    /** 关闭窗口（复用「关闭到托盘 / 关闭=退出」既有语义） */
    close: () => ipcRenderer.invoke('holzyn:window:close'),
    /** 查询当前是否最大化（用于初始化图标） */
    isMaximized: () => ipcRenderer.invoke('holzyn:window:is-maximized'),
    /** 订阅最大化状态变化（最大化/还原/从托盘恢复）；返回取消订阅函数 */
    onMaximizedChange: (cb) => {
      const handler = (_event, value) => cb(!!value);
      ipcRenderer.on('holzyn:window:maximized', handler);
      return () => ipcRenderer.removeListener('holzyn:window:maximized', handler);
    },
  },
});

// ==================== 退出过渡层（「正在关闭」遮罩） ====================
//
// 为什么在 preload 里直接注入 DOM，而不是做成 Vue 组件：
//   退出可能发生在任意路由（画廊/世界/设置/教程），甚至渲染进程已半挂；
//   覆盖层只依赖 DOM 是否存在，不依赖 Vue 挂载状态、store 与路由，才能「任何情况下都能浮出来」。
// 时序：主进程延迟 300ms 才推送（<300ms 关完就完全不显示，避免闪一下）；
//       z-index 复用项目令牌 --z-toast（variables.css），不另造魔法数字。
const CLOSING_STYLE_ID = 'holzyn-closing-style';
let closingEl = null;

function ensureClosingStyle() {
  if (document.getElementById(CLOSING_STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = CLOSING_STYLE_ID;
  style.textContent = [
    '.holzyn-closing-mask{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;',
    'justify-content:center;gap:14px;background:rgba(11,18,32,.82);backdrop-filter:blur(6px);',
    '-webkit-backdrop-filter:blur(6px);color:#E6EDF7;font-family:"Microsoft YaHei","PingFang SC",system-ui,sans-serif;',
    'opacity:0;transition:opacity .18s ease;cursor:progress;}',
    '.holzyn-closing-mask.is-in{opacity:1;}',
    '.holzyn-closing-ring{width:34px;height:34px;border-radius:50%;border:3px solid rgba(230,237,247,.22);',
    'border-top-color:#7CB8FF;animation:holzyn-closing-spin .9s linear infinite;}',
    '@keyframes holzyn-closing-spin{to{transform:rotate(360deg)}}',
    '.holzyn-closing-title{font-size:15px;font-weight:600;letter-spacing:.02em;}',
    '.holzyn-closing-hint{font-size:12px;color:rgba(230,237,247,.6);}',
    '@media (prefers-reduced-motion:reduce){.holzyn-closing-ring{animation-duration:2.4s}}',
  ].join('');
  (document.head || document.documentElement).appendChild(style);
}

/** 浮出「正在关闭」遮罩（幂等；payload: { label, hint }） */
function showClosingOverlay(payload) {
  if (closingEl) return;
  ensureClosingStyle();
  const p = payload || {};
  const mask = document.createElement('div');
  mask.className = 'holzyn-closing-mask';
  mask.setAttribute('role', 'alertdialog');
  mask.setAttribute('aria-live', 'assertive');
  mask.setAttribute('aria-label', p.label || '正在关闭');
  mask.style.zIndex = 'calc(var(--z-toast, 80) + 10)'; // 盖住 toast/命令面板，仍走项目 z-index 令牌

  const ring = document.createElement('div');
  ring.className = 'holzyn-closing-ring';
  const title = document.createElement('div');
  title.className = 'holzyn-closing-title';
  title.textContent = p.label || '正在关闭世界…';
  const hint = document.createElement('div');
  hint.className = 'holzyn-closing-hint';
  hint.textContent = p.hint || '正在安全保存世界数据，请稍候';

  mask.append(ring, title, hint);
  (document.body || document.documentElement).appendChild(mask);
  closingEl = mask;
  requestAnimationFrame(() => mask.classList.add('is-in'));
}

/** 收起遮罩（更新流程中止 / 开发态重启后端时复用窗口） */
function hideClosingOverlay() {
  if (!closingEl) return;
  const el = closingEl;
  closingEl = null;
  el.classList.remove('is-in');
  setTimeout(() => { try { el.remove(); } catch (_) { /* 窗口已销毁，忽略 */ } }, 200);
}

ipcRenderer.on('holzyn:app:closing', (_event, payload) => showClosingOverlay(payload));
ipcRenderer.on('holzyn:app:closing-cancel', () => hideClosingOverlay());
