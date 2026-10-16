// ============================================================
// HolzynActor 桌面端更新器冒烟测试（开发模式，HOLZYN_UPDATER_MOCK=1）
// 用法：
//   1) 先启动应用：HOLZYN_DESKTOP_DEBUG_PORT=9333 HOLZYN_UPDATER_MOCK=1 npm start
//   2) 再运行：node scripts/smoke-updater.mjs
// 校验链路：CDP 连页面 → 等待 mock 自动检查到 downloaded →
//           confirmInstall（停后端→备份→重启后端）→ 校验备份目录与后端健康。
// ============================================================
const CDP = 'http://127.0.0.1:9333';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getPageWsUrl() {
  for (let i = 0; i < 90; i++) {
    try {
      const res = await fetch(`${CDP}/json`);
      const pages = await res.json();
      const page = pages.find((p) => p.type === 'page' && p.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch (_) { /* 未就绪 */ }
    await sleep(1000);
  }
  throw new Error('CDP 页面未就绪（后端/窗口未起来）');
}

async function main() {
  const wsUrl = await getPageWsUrl();
  const ws = new WebSocket(wsUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  };
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  const send = (method, params = {}) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method, params }));
    });

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.result && r.result.exceptionDetails) {
      throw new Error('页面执行异常: ' + JSON.stringify(r.result.exceptionDetails));
    }
    return r.result && r.result.result ? r.result.result.value : undefined;
  };

  // 1) 等待 mock 自动检查 → downloaded
  let state = null;
  for (let i = 0; i < 60; i++) {
    try {
      state = await evaluate('window.holzynDesktop ? window.holzynDesktop.updater.getState() : null');
    } catch (_) { /* 页面可能尚未注入 preload */ }
    if (state && (state.status === 'downloaded' || state.status === 'error')) break;
    await sleep(1000);
  }
  console.log('[smoke] 更新器状态:', JSON.stringify(state));
  if (!state || state.status !== 'downloaded') {
    console.error('[smoke] FAIL: 未到达 downloaded 状态（status=' + (state && state.status) + '）');
    process.exit(1);
  }
  if (state.mock !== true) {
    console.error('[smoke] FAIL: 非 mock 模式（确认已设 HOLZYN_UPDATER_MOCK=1）');
    process.exit(1);
  }
  if (state.portable !== false) {
    console.error('[smoke] FAIL: 安装版 portable 标志应为 false');
    process.exit(1);
  }

  // 1b) U3：通道切换（latest→beta→latest），验证持久化与状态流转
  const chBeta = await evaluate('window.holzynDesktop.updater.setChannel("beta")');
  console.log('[smoke] setChannel(beta):', JSON.stringify(chBeta));
  if (!chBeta || chBeta.channel !== 'beta') {
    console.error('[smoke] FAIL: 切到 beta 通道后 channel 未生效');
    process.exit(1);
  }
  // mock 模式下切换会触发 mockCheckForUpdate → 重新进入 downloaded
  let state2 = null;
  for (let i = 0; i < 40; i++) {
    state2 = await evaluate('window.holzynDesktop.updater.getState()');
    if (state2 && state2.status === 'downloaded') break;
    await sleep(800);
  }
  console.log('[smoke] beta 通道切换后状态:', state2 && state2.status);
  const chStable = await evaluate('window.holzynDesktop.updater.setChannel("latest")');
  console.log('[smoke] setChannel(latest):', JSON.stringify(chStable));
  if (!chStable || chStable.channel !== 'latest') {
    console.error('[smoke] FAIL: 切回 stable 后 channel 未生效');
    process.exit(1);
  }
  for (let i = 0; i < 40; i++) {
    state2 = await evaluate('window.holzynDesktop.updater.getState()');
    if (state2 && state2.status === 'downloaded') break;
    await sleep(800);
  }
  console.log('[smoke] stable 通道切换后状态:', state2 && state2.status);
  if (!state2 || state2.status !== 'downloaded') {
    console.error('[smoke] FAIL: 通道切换后未回到 downloaded');
    process.exit(1);
  }

  // 1c) U3：后端 Flyway schema 迁移状态（后端已由本应用启动，应已迁移到 V2）
  let schema = null;
  for (let i = 0; i < 20; i++) {
    try {
      schema = await evaluate("fetch('/api/system/schema').then(r=>r.json())");
      if (schema && schema.code === 200) break;
    } catch (_) { /* 重试 */ }
    await sleep(1000);
  }
  console.log('[smoke] Flyway schema:', JSON.stringify(schema));
  if (!schema || schema.code !== 200 || !schema.data || schema.data.schemaVersion !== '2' || schema.data.appliedVersion !== '2') {
    console.error('[smoke] FAIL: Flyway 迁移未就绪（期望 schemaVersion=2）');
    process.exit(1);
  }

  // 2) confirmInstall（开发模式：停后端→备份→重启后端）
  const res = await evaluate('window.holzynDesktop.updater.confirmInstall()');
  console.log('[smoke] confirmInstall:', JSON.stringify(res));
  if (!res || !res.ok || !res.dev || !res.backupDir) {
    console.error('[smoke] FAIL: confirmInstall 结果不符合预期');
    process.exit(1);
  }

  // 3) 校验后端重启后健康
  let health = null;
  for (let i = 0; i < 30; i++) {
    try {
      health = await evaluate("fetch('/api/health').then(r=>r.json()).then(j=>j.data && j.data.status)");
      if (health === 'UP') break;
    } catch (_) { /* 页面重载中 */ }
    await sleep(1000);
  }
  console.log('[smoke] 后端重启后健康:', health);
  if (health !== 'UP') {
    console.error('[smoke] FAIL: 后端未健康重启');
    process.exit(1);
  }

  console.log('[smoke] PASS：更新状态流转 / 停后端 / 备份 / 重启后端 全链路正常');
  ws.close();
  process.exit(0);
}

main().catch((e) => {
  console.error('[smoke] ERROR:', e.message || e);
  process.exit(2);
});
