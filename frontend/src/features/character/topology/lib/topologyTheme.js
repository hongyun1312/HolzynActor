/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * 关系工作台主题令牌桥 —— 把 DSH 设计令牌（variables.css）翻译成 G6 画布能用的颜色。
 *
 * <p><b>为什么单独一层</b>：画布（Canvas 渲染）读不到 CSS 变量，必须把
 * {@code --accent/--bg-layer-1/--text-primary/...} 解析成具体色值再喂给 G6；
 * 而主题（深/浅）与个性化主色（用户改 --accent）会在运行中变化，需要一条
 * 「令牌 → 主题包 → 重建画布」的单向链路。旧实现把这段逻辑写在组件里，
 * 导致工作台拆分后每个子组件都要各自读一遍 DOM；抽成纯模块后
 * 子组件共享同一份主题包，也便于在 Node 里断言颜色混合的正确性。</p>
 *
 * <p>纯函数（除 readTokens 读 DOM 外无副作用）：颜色解析/混合/透明化都可独立测试。</p>
 */

/**
 * 读取当前设计令牌（每次调用都取实时值——主题切换后必须拿到新值）。
 *
 * @returns {object} 令牌快照
 */
export function readTokens() {
  const root = document.documentElement
  const cs = getComputedStyle(root)
  const v = (name, fb) => (cs.getPropertyValue(name) || '').trim() || fb
  return {
    isDark: root.getAttribute('data-theme') === 'dark',
    accent: v('--accent', '#4176e6'),
    bgLayer1: v('--bg-layer-1', '#ffffff'),
    bgLayer2: v('--bg-layer-2', '#f1f3f7'),
    textPrimary: v('--text-primary', '#17191d'),
    textSecondary: v('--text-secondary', '#61666e'),
    textPlaceholder: v('--text-placeholder', '#b2b6bd'),
    borderL1: v('--border-l1', 'rgba(15,17,21,.16)'),
    borderL2: v('--border-l2', 'rgba(15,17,21,.24)'),
    radiusMd: v('--radius-md', '10px')
  }
}

/**
 * 主题指纹：深/浅 + 主色。指纹变化才需要重建画布（避免无谓重绘）。
 *
 * @param {object} [t] 令牌快照（缺省现场读取）
 *
 * @returns {string} 指纹
 */
export function themeKey(t) {
  const k = t || readTokens()
  return `${k.isDark ? 'd' : 'l'}:${k.accent}`
}

/**
 * 颜色 → [r,g,b]。
 *
 * @param {string} c 颜色（#rgb / #rrggbb / rgb() / rgba()）
 *
 * @returns {number[]} RGB 三元组（解析失败返回中性灰）
 */
export function parseColor(c) {
  const s = String(c || '').trim()
  if (s.startsWith('#')) {
    const h = s.slice(1)
    if (h.length === 3) return h.split('').map(x => parseInt(x + x, 16))
    if (h.length >= 6) return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16))
  }
  const m = s.match(/rgba?\(([^)]+)\)/)
  if (m) {
    const p = m[1].split(',').map(x => parseFloat(x.trim()))
    return [p[0] || 0, p[1] || 0, p[2] || 0]
  }
  return [128, 128, 128]
}

/**
 * 颜色混合（线性插值）。
 *
 * @param {string} a 起始色
 *
 * @param {string} b 终止色
 *
 * @param {number} t 0=a 1=b
 *
 * @returns {string} rgb(...) 字符串
 */
export function mixColor(a, b, t) {
  const pa = parseColor(a)
  const pb = parseColor(b)
  const c = pa.map((x, i) => Math.round(x + (pb[i] - x) * t))
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`
}

/**
 * 颜色 + 透明度 → rgba(...)。G6 的 opacity 与 fill 透明度语义不同：
 * 前者会把描边/标签一起变淡，后者只影响填充——分区块底色需要用后者。
 *
 * @param {string} c 颜色
 *
 * @param {number} a 透明度 0~1
 *
 * @returns {string} rgba(...) 字符串
 */
export function alphaColor(c, a) {
  const p = parseColor(c)
  return `rgba(${p[0]}, ${p[1]}, ${p[2]}, ${Math.max(0, Math.min(1, a))})`
}

/**
 * 相对亮度（0~1）：决定某色上应放深色还是浅色文字（圈子标签底色自适应）。
 *
 * @param {string} c 颜色
 *
 * @returns {number} 亮度
 */
export function luminance(c) {
  const [r, g, b] = parseColor(c).map(v => {
    const x = v / 255
    return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * 构建画布主题包（节点/边/圈子/标签全部色值一次算好，回调里只做取值）。
 *
 * <p>可见性是这套配色反复踩过的坑：填充若向背景混 82%+，浅色主题下节点几乎消失。
 * 这里统一约定——<b>填充最多混 55% 背景（保持淡染质感但清晰可见）</b>，
 * 描边与文字一律用不透明色，弱化只靠 inactive 状态而不是靠降低基样式对比度。</p>
 *
 * @param {object} [tokens] 令牌快照（缺省现场读取）
 *
 * @returns {object} 主题包
 */
export function buildTheme(tokens) {
  const t = tokens || readTokens()
  const onBg = t.bgLayer1
  return {
    tokens: t,
    isDark: t.isDark,
    /** 标签底（半透明胶囊，保证压在任何元素上都可读） */
    labelBg: t.isDark ? 'rgba(20,22,26,.86)' : 'rgba(255,255,255,.92)',
    /** 画布底：极淡的第二层背景，让白色节点有边界感（fullbleed 时不再是纯白） */
    canvasBg: t.isDark ? 'rgb(17,18,20)' : t.bgLayer1,
    /** 网格线（画布底纹；深色下更暗） */
    gridStroke: t.isDark ? 'rgba(255,255,255,.05)' : 'rgba(15,17,21,.05)',
    /** 节点：以圈子色为填充，混背景保证淡染质感且清晰 */
    nodeFill: (color) => mixColor(color, onBg, 0.42),
    /** 节点描边：圈子色原色（保证 2px 描边在浅色主题下依然可见） */
    nodeStroke: (color) => (t.isDark ? mixColor(color, '#ffffff', 0.15) : color),
    /** 幽灵节点（关系表引用但角色不存在）：灰色虚线，暗示"信息缺失"而非"普通角色" */
    ghostFill: mixColor(t.textSecondary, onBg, 0.62),
    ghostStroke: t.textSecondary,
    /** 主角光环 */
    protagonistHalo: t.accent,
    /** 文字 */
    textPrimary: t.textPrimary,
    textSecondary: t.textSecondary,
    textPlaceholder: t.textPlaceholder,
    /** 选中/悬停 */
    accent: t.accent,
    accentSoft: alphaColor(t.accent, 0.18),
    /**
     * 圈子块：填充极淡（不能抢节点的视觉权重），但描边必须看得见——
     * 「分成哪几拨人」正是靠这条虚线边界读出来的，描边太淡等于这个功能不存在。
     */
    hullFill: (color) => alphaColor(color, t.isDark ? 0.10 : 0.06),
    hullStroke: (color) => alphaColor(color, t.isDark ? 0.60 : 0.52),
    hullLabelFill: t.textPrimary,
    hullLabelBg: t.isDark ? 'rgba(20,22,26,.72)' : 'rgba(255,255,255,.82)',
    /** 边：关系语义色 + 新鲜度透明度 */
    edgeLabelBg: t.isDark ? 'rgba(20,22,26,.88)' : 'rgba(255,255,255,.94)',
    border: t.borderL1,
    borderStrong: t.borderL2
  }
}
