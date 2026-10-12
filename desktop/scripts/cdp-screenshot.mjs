// HolzynActor 桌面端调试工具：经 CDP 抓取应用窗口截图。
// 用法：node cdp-screenshot.mjs <webSocketDebuggerUrl> <out.png>
// 前置：设置 HOLZYN_DESKTOP_DEBUG_PORT 启动桌面端，从 http://127.0.0.1:<port>/json 取页面 ws 地址。

import fs from 'node:fs';

const wsUrl = process.argv[2];
const out = process.argv[3];
if (!wsUrl || !out) {
  console.error('usage: node cdp-screenshot.mjs <wsUrl> <out.png>');
  process.exit(1);
}

const ws = new WebSocket(wsUrl);
let done = false;

ws.onopen = () => {
  console.log('[ws] open');
  ws.send(JSON.stringify({ id: 1, method: 'Page.enable' }));
  ws.send(JSON.stringify({ id: 2, method: 'Page.captureScreenshot', params: { format: 'png' } }));
};

ws.onmessage = (ev) => {
  console.log('[ws] message len=', String(ev.data).length);
  let msg;
  try { msg = JSON.parse(ev.data); } catch { console.log('[ws] non-json:', String(ev.data).slice(0, 120)); return; }
  if (msg.id === 2) {
    if (msg.result && msg.result.data) {
      fs.writeFileSync(out, Buffer.from(msg.result.data, 'base64'));
      console.log(`saved: ${out} (${fs.statSync(out).size} bytes)`);
      done = true;
      try { ws.close(); } catch {}
      process.exit(0);
    }
    console.error('CDP result empty/error:', JSON.stringify(msg).slice(0, 300));
    process.exit(1);
  }
};

ws.onerror = (e) => {
  console.error('[ws] error:', e && (e.message || e.error || String(e)));
  if (!done) process.exit(1);
};

ws.onclose = (e) => {
  console.log('[ws] close code=', e && e.code, 'reason=', e && e.reason);
  if (!done) { console.error('closed without screenshot'); process.exit(1); }
};

setTimeout(() => {
  if (!done) {
    console.error('timeout: no screenshot received');
    process.exit(1);
  }
}, 15000);
