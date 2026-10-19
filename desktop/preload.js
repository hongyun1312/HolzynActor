'use strict';

/**
 * HolzynActor 桌面端预加载脚本（沙箱内运行，仅暴露受控 API 给页面）。
 * 职责：
 *  - 只读环境信息（platform / versions / appVersion）；
 *  - 更新器桥（window.holzynDesktop.updater）：状态订阅、手动检查、确认安装、
 *    打开发布页/数据目录、自动检查开关、拉取 Release 更新说明。
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
});
