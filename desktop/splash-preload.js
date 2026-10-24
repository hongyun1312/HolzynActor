'use strict';

/**
 * HolzynActor 桌面端 · 启动闪屏预加载脚本（沙箱内运行，仅暴露受控 API 给 splash.html）。
 * 职责：
 *  - 订阅主进程推送的启动状态（进度 / 阶段文字 / 完成 / 错误）；
 *  - 版本号（与主进程 app.getVersion() 同口径，当前 v1.1.1-beta.1）；
 *  - 错误态操作：打开日志目录 / 重试启动 / 退出。
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('holzynSplash', {
  /** 应用版本（desktop/package.json，与后端同版本号口径） */
  getVersion: () => ipcRenderer.invoke('holzyn:app-version'),
  /** 打开桌面端日志目录（%LOCALAPPDATA%\HolzynActor\logs） */
  openLogDir: () => ipcRenderer.invoke('holzyn:splash:open-log-dir'),
  /** 重试启动（重新拉起后端，进度从 0 重新推进） */
  retry: () => ipcRenderer.invoke('holzyn:splash:retry'),
  /** 退出应用（先优雅停后端，H2 干净落盘） */
  quit: () => ipcRenderer.invoke('holzyn:splash:quit'),
  /** 订阅启动状态推送（{ state, progress, label, title?, detail?, closing? }）；返回取消订阅函数 */
  onState: (cb) => {
    const handler = (_event, state) => cb(state);
    ipcRenderer.on('holzyn:splash:state', handler);
    return () => ipcRenderer.removeListener('holzyn:splash:state', handler);
  },
});
