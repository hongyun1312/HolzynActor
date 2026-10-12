'use strict';

/**
 * HolzynActor 桌面端预加载脚本（沙箱内运行，仅暴露只读环境信息给页面）。
 * 页面本身无需桌面能力；这里保持最小面，便于未来扩展（如「打开数据目录」按钮）。
 */

const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('holzynDesktop', {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
});
