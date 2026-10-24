'use strict';

/**
 * 桌面窗口几何单测（无需 Electron / GUI，直接 `node scripts/test-window-geometry.js`）。
 *
 * 覆盖三类边界：
 *   1. 每次打开窗口的初始尺寸（基准 1280×720，屏幕装不下等比缩小，最小 960×540）；
 *   2. 用户拖拽边框时的 16:9 反算与最小档钳制（左右驱动 / 上下驱动 / 四角）；
 *   3. 比例自检与「从托盘恢复后修正」。
 */

const assert = require('assert');
const geo = require('../window-geometry');

let passed = 0;
const failures = [];

function check(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`PASS  ${name}`);
  } catch (e) {
    failures.push({ name, error: e });
    console.log(`FAIL  ${name}\n      ${e && e.message}`);
  }
}

function assertAspect(size, label) {
  const expected = Math.round((size.width * 9) / 16);
  assert.ok(
    Math.abs(size.height - expected) <= 2,
    `${label}: ${size.width}x${size.height} is not 16:9 (expected height ${expected})`
  );
}

// ==================== 1. 初始尺寸 ====================

check('open: 1920x1080 work area -> base 1280x720', () => {
  const s = geo.fitToWorkArea({ width: 1920, height: 1040 });
  assert.deepStrictEqual(s, { width: 1280, height: 720 });
});

check('open: 1366x728 work area still fits base size', () => {
  const s = geo.fitToWorkArea({ width: 1366, height: 728 });
  assert.deepStrictEqual(s, { width: 1280, height: 720 });
});

check('open: 1366x700 work area shrinks but keeps 16:9', () => {
  const s = geo.fitToWorkArea({ width: 1366, height: 700 });
  assert.ok(s.height <= 700, `height ${s.height} exceeds work area`);
  assert.ok(s.width < 1280 && s.width > 1200, `unexpected width ${s.width}`);
  assertAspect(s, 'shrunk');
});

check('open: 1024x600 work area -> 1024x576', () => {
  const s = geo.fitToWorkArea({ width: 1024, height: 600 });
  assert.deepStrictEqual(s, { width: 1024, height: 576 });
});

check('open: tiny 800x450 work area degrades to 800x450 (still 16:9)', () => {
  const s = geo.fitToWorkArea({ width: 800, height: 450 });
  assert.deepStrictEqual(s, { width: 800, height: 450 });
});

check('open: missing work area falls back to base size', () => {
  assert.deepStrictEqual(geo.fitToWorkArea(), { width: 1280, height: 720 });
});

check('open: never smaller than 960x540 when work area allows', () => {
  const s = geo.fitToWorkArea({ width: 1100, height: 900 });
  assert.ok(s.width >= 960 && s.height >= 540, `${s.width}x${s.height} below minimum`);
  assertAspect(s, 'min');
});

// ==================== 2. 拖拽夹紧 ====================

const current = { x: 100, y: 100, width: 1280, height: 720 };

check('resize right edge: width drives height, top-left anchored', () => {
  const r = geo.constrainToAspect({ x: 100, y: 100, width: 1000, height: 720 }, 'right', current);
  assert.deepStrictEqual(r, { x: 100, y: 100, width: 1000, height: 563 });
  assertAspect(r, 'right');
});

check('resize bottom edge: height drives width, top-left anchored', () => {
  const r = geo.constrainToAspect({ x: 100, y: 100, width: 1280, height: 600 }, 'bottom', current);
  assert.deepStrictEqual(r, { x: 100, y: 100, width: 1067, height: 600 });
  assertAspect(r, 'bottom');
});

check('resize left edge: right edge stays fixed', () => {
  const r = geo.constrainToAspect({ x: 300, y: 100, width: 1080, height: 720 }, 'left', current);
  assert.strictEqual(r.x + r.width, current.x + current.width, 'right edge moved');
  assert.strictEqual(r.width, 1080);
  assertAspect(r, 'left');
});

check('resize top edge: bottom edge stays fixed', () => {
  const r = geo.constrainToAspect({ x: 100, y: 250, width: 1280, height: 570 }, 'top', current);
  assert.strictEqual(r.y + r.height, current.y + current.height, 'bottom edge moved');
  assert.strictEqual(r.height, 570);
  assertAspect(r, 'top');
});

check('resize corner bottom-right: width drives', () => {
  const r = geo.constrainToAspect({ x: 100, y: 100, width: 1400, height: 900 }, 'bottom-right', current);
  assert.deepStrictEqual(r, { x: 100, y: 100, width: 1400, height: 788 });
  assertAspect(r, 'corner');
});

check('resize below minimum clamps to 960x540', () => {
  const r = geo.constrainToAspect({ x: 100, y: 100, width: 500, height: 300 }, 'right', current);
  assert.deepStrictEqual(r, { x: 100, y: 100, width: 960, height: 540 });
});

check('resize below minimum from left keeps right edge', () => {
  const r = geo.constrainToAspect({ x: 900, y: 100, width: 480, height: 300 }, 'left', current);
  assert.strictEqual(r.width, 960);
  assert.strictEqual(r.height, 540);
  assert.strictEqual(r.x, current.x + current.width - 960);
});

// ==================== 3. 自检与恢复修正 ====================

check('isAspectRatioOk: accepts 1280x720 and 1000x563', () => {
  assert.strictEqual(geo.isAspectRatioOk({ width: 1280, height: 720 }), true);
  assert.strictEqual(geo.isAspectRatioOk({ width: 1000, height: 563 }), true);
  assert.strictEqual(geo.isAspectRatioOk({ width: 1280, height: 700 }), false);
  assert.strictEqual(geo.isAspectRatioOk({ width: 0, height: 0 }), false);
});

check('normalizeToAspect: restores 16:9 height for a drifted window', () => {
  const r = geo.normalizeToAspect({ x: 40, y: 60, width: 1200, height: 500 });
  assert.deepStrictEqual(r, { x: 40, y: 60, width: 1200, height: 675 });
});

check('normalizeToAspect: keeps minimum size floor', () => {
  const r = geo.normalizeToAspect({ x: 0, y: 0, width: 600, height: 200 });
  assert.deepStrictEqual(r, { x: 0, y: 0, width: 960, height: 540 });
});

// ==================== 汇总 ====================

console.log(`\n${passed} passed, ${failures.length} failed`);
if (failures.length) {
  for (const f of failures) console.log(`  - ${f.name}: ${f.error && (f.error.stack || f.error.message)}`);
  process.exit(1);
}
