'use strict';

/**
 * 桌面主窗口几何：16:9 比例 + 基准逻辑尺寸 1280×720（**UI 设计基准**）。
 *
 * <p>为什么单独抽成模块而不是写在 main.js 里：</p>
 * <ol>
 *   <li>这里全是**纯函数**（不碰 Electron API），可以用 `node scripts/test-window-geometry.js`
 *       直接单测「尺寸贴合工作区 / 拖拽反算另一边 / 最小档钳制」三类边界；</li>
 *   <li>「基准尺寸 + 比例」是全产品共用的设计契约（窗口、闪屏文案、前端断点、文档），
 *       只能有一份数字来源，禁止前端与主进程各写一遍。</li>
 * </ol>
 *
 * <p>基准取舍：1280×720 = 16:9，是 UI 设计与验收的唯一基准画布；窗口每次打开都按它开，
 * 屏幕装不下时**等比缩小**（仍是 16:9），不小于 960×540（基准的 0.75 倍，此时外壳自动把侧栏
 * 收回为图标栏，见 frontend 的 `--bp-shell-rail`）。</p>
 *
 * @see docs/设计文档/HolzynActor_桌面端16比9沉浸式窗口与UI基准_设计_V1.0.md
 */

/** 基准逻辑尺寸（UI 设计画布，也是每次打开时的窗口内容区尺寸） */
const BASE_WIDTH = 1280;
const BASE_HEIGHT = 720;

/** 基准宽高比 16:9（= 1.777…） */
const ASPECT_RATIO = BASE_WIDTH / BASE_HEIGHT;

/** 最小档：基准 × 0.75，仍是 16:9（此尺寸下侧栏自动收回图标栏） */
const MIN_WIDTH = 960;
const MIN_HEIGHT = 540;

/**
 * 把窗口宽度换算成 16:9 对应高度（四舍五入到整像素）。
 *
 * @param {number} width 窗口宽度（px）
 * @returns {number} 对应高度（px，16:9）
 */
function heightForWidth(width) {
  const w = Number(width);
  if (!Number.isFinite(w) || w <= 0) return BASE_HEIGHT;
  return Math.round((w * 9) / 16);
}

/**
 * 把窗口高度换算成 16:9 对应宽度（四舍五入到整像素）。
 *
 * @param {number} height 窗口高度（px）
 * @returns {number} 对应宽度（px，16:9）
 */
function widthForHeight(height) {
  const h = Number(height);
  if (!Number.isFinite(h) || h <= 0) return BASE_WIDTH;
  return Math.round((h * 16) / 9);
}

/**
 * 计算「每次打开窗口」的初始尺寸：基准 1280×720，屏幕装不下就等比缩小。
 *
 * <p>为什么不直接给 1280×720 了事：1366×768 / 1440×900 / 高分屏 125% 缩放后，
 * 工作区高度可能不足 720，硬开会让窗口底部落到任务栏下面（玩家第一眼就「窗口被切掉」）。</p>
 *
 * @param {{width:number,height:number}} [workArea] 屏幕可用工作区（Electron `display.workAreaSize`，DIP）
 * @returns {{width:number,height:number}} 16:9 的初始窗口尺寸（内容区 = 整窗，因为无边框）
 */
function fitToWorkArea(workArea) {
  const maxWidth = Math.max(320, Math.round(Number(workArea && workArea.width) || BASE_WIDTH));
  const maxHeight = Math.max(240, Math.round(Number(workArea && workArea.height) || BASE_HEIGHT));

  let width = BASE_WIDTH;
  let height = BASE_HEIGHT;

  // 装不下：按「更紧的那一边」等比缩，保持 16:9
  if (width > maxWidth || height > maxHeight) {
    const scale = Math.min(maxWidth / BASE_WIDTH, maxHeight / BASE_HEIGHT);
    width = Math.floor(BASE_WIDTH * scale);
    height = heightForWidth(width);
  }
  // 小于最小档：先抬到最小档（工作区真的更小时，以工作区为准）
  if (width < MIN_WIDTH) {
    width = Math.min(MIN_WIDTH, maxWidth);
    height = heightForWidth(width);
  }
  if (height < MIN_HEIGHT) {
    height = Math.min(MIN_HEIGHT, maxHeight);
    width = widthForHeight(height);
  }
  return { width: Math.round(width), height: Math.round(height) };
}

/**
 * 把用户拖拽边框产生的目标 bounds 夹紧成 16:9。
 *
 * <p>驱动轴按 `edge` 判定：拖左右边/四角 → 以宽度为准反算高度；拖上下边 → 以高度为准反算宽度。
 * 锚点取「未被拖拽的那一侧」，这样左侧/上侧拖拽时对侧边线与窗口位置都不跳动。</p>
 *
 * <p>为什么不用 `win.setAspectRatio()`：Windows 上该 API 不可靠（Electron 文档里
 * `extraSize` 参数标注为 macOS 专属，且实测拖拽仍会破坏比例）。改为监听 `will-resize`
 * 后自己反算 + `setBounds`，三平台行为一致，也便于单测。</p>
 *
 * @param {{x:number,y:number,width:number,height:number}} bounds 用户拖拽出的目标 bounds
 * @param {string} edge 拖拽边：left/right/top/bottom/top-left/top-right/bottom-left/bottom-right
 * @param {{x:number,y:number,width:number,height:number}} current 当前（拖拽前）bounds，用于取锚点
 * @returns {{x:number,y:number,width:number,height:number}} 夹紧后的 bounds（16:9 且不小于最小档）
 */
function constrainToAspect(bounds, edge, current) {
  const target = bounds || {};
  const base = current || target;
  const side = String(edge || '');
  const widthDriven = side.includes('left') || side.includes('right');

  let width;
  let height;
  if (widthDriven) {
    width = Math.round(Number(target.width) || BASE_WIDTH);
    height = heightForWidth(width);
  } else {
    height = Math.round(Number(target.height) || BASE_HEIGHT);
    width = widthForHeight(height);
  }

  // 最小档钳制（先宽后高，两轴都满足）
  if (width < MIN_WIDTH) {
    width = MIN_WIDTH;
    height = heightForWidth(width);
  }
  if (height < MIN_HEIGHT) {
    height = MIN_HEIGHT;
    width = widthForHeight(height);
  }

  const currentRight = Math.round(Number(base.x) || 0) + Math.round(Number(base.width) || width);
  const currentBottom = Math.round(Number(base.y) || 0) + Math.round(Number(base.height) || height);
  const x = side.includes('left') ? currentRight - width : Math.round(Number(base.x) || 0);
  const y = side.includes('top') ? currentBottom - height : Math.round(Number(base.y) || 0);
  return { x, y, width, height };
}

/**
 * 判断一组 bounds 是否已是 16:9（带像素容差：整数四舍五入会有 1px 误差）。
 *
 * @param {{width:number,height:number}} bounds 待判定 bounds
 * @param {number} [tolerance] 高度容差（px，默认 2）
 * @returns {boolean} 是否满足 16:9
 */
function isAspectRatioOk(bounds, tolerance) {
  const b = bounds || {};
  const width = Number(b.width);
  const height = Number(b.height);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return false;
  const expected = heightForWidth(width);
  return Math.abs(height - expected) <= (Number.isFinite(tolerance) ? tolerance : 2);
}

/**
 * 把任意 bounds 修正为 16:9（窗口从托盘恢复/系统还原尺寸后自检用）。
 *
 * @param {{x:number,y:number,width:number,height:number}} bounds 原 bounds
 * @returns {{x:number,y:number,width:number,height:number}} 修正后的 bounds（宽不变，高度对齐 16:9）
 */
function normalizeToAspect(bounds) {
  const b = bounds || {};
  const width = Math.max(MIN_WIDTH, Math.round(Number(b.width) || BASE_WIDTH));
  return {
    x: Math.round(Number(b.x) || 0),
    y: Math.round(Number(b.y) || 0),
    width,
    height: Math.max(MIN_HEIGHT, heightForWidth(width))
  };
}

module.exports = {
  BASE_WIDTH,
  BASE_HEIGHT,
  ASPECT_RATIO,
  MIN_WIDTH,
  MIN_HEIGHT,
  heightForWidth,
  widthForHeight,
  fitToWorkArea,
  constrainToAspect,
  isAspectRatioOk,
  normalizeToAspect
};
