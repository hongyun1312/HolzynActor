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

// 世界地图（V1.1 · PixiJS）调色与贴图规则——**视觉口径的唯一出口**。
//
// 为什么把颜色集中在这个文件：卫星俯视图的「像不像」几乎全在**色彩关系**上——
// 屋顶要压住草地、路面要亮于屋顶、田块要比林地暖。这些关系一旦散落在各个绘制函数里，
// 改一个类型的颜色就会破坏另一处对比。所以这里只回答两个问题：
//   ① 这个要素该是什么色（roof/wall/field/forest/water-side）；
//   ② 它比邻居亮还是暗（shade 系列）。
// 后端 palette 只负责「道路等级色」（必须与 Java 调试图 PNG 完全一致），其余在这里定。

/** 屋顶色兜底（数据里 roofColor 非法/缺失时用；按大类给一个不破坏气质的色）。 */
export const FALLBACK_ROOF = '#8a5a3c'

/** 墙面色（数据里没有 wallColor，按大类给：民居偏土黄、商铺偏暖赭、公共偏灰石）。 */
export const WALL_BY_CATEGORY = {
  '民居': '#d9c9a6',
  '商铺': '#e2cfa8',
  '工坊': '#c9bda2',
  '公共': '#e7ddc8',
  '生产': '#cdbf9e',
  '水工': '#c3b69b',
  '自然': '#8f9a6a'
}

/** 农田（按 code 哈希取一种作物色：麦黄 / 稻绿 / 菜畦 / 休耕土）。 */
export const FIELD_CROPS = ['#c9b06a', '#a8b45c', '#8f9f4e', '#b99a63', '#c4a86b', '#7f9450']

/** 林冠（深浅两档，绘制时按哈希取，制造层次）。 */
export const CANOPY = ['#4f6b3a', '#5c7a42', '#42603a', '#68854a']

/**
 * 广场/晒场铺地。
 *
 * <p>这是全图唯一一整块人造铺面，所以它必须"暗、暖、有缝"三件事同时成立：
 * 亮 = 一块没加载出来的白板；冷 = 水泥地（岛上没有水泥）；无缝 = 一张色卡。
 * 取青灰偏暖的条石色，压在草地（比它亮）和屋顶（比它艳）之间。</p>
 */
export const PAVING = '#a89d86'

/** 木作（码头/栈桥/桥面）。 */
export const TIMBER = '#8a6a45'

/** 围墙（院落）：比墙面色暗一档，让院子「有边界」而不是一团。 */
export const WALL_RUN = '#9e9278'

/**
 * 屋前踩实的裸土（院子/门前地）。
 *
 * <p>村子从空中看，房子不是"摆在草地上"，而是被一圈**踩秃的土**围住——
 * 那圈土是全院人一天走几十遍走出来的。没有它，再怎么画屋顶也像贴纸。</p>
 */
export const YARD = 0x7a6746

/** 院土/路旁裸土的透明度：低到只是"地面脏了一点"，高到能看出范围。 */
export const YARD_ALPHA = 0.46

/** 路旁被踩秃的土带（沿路两侧各约 4~5 米）。 */
export const VERGE_ALPHA = 0.20

/** 阴影（太阳方位角 315°，即西北来光；影子落向东南）。 */
export const SHADOW = 0x1b1a16

/** 阴影透明度 / 每米层高的影长（米）。 */
export const SHADOW_ALPHA = 0.26
export const SHADOW_PER_METER = 0.42

/** 夜间压色（乘算；白天为纯白，夜里偏蓝）。 */
export const NIGHT_TINT = 0x2a3550

/** 道路等级兜底色（后端 palette 缺失时；与 MapCompositePainter 同源）。 */
export const ROAD_FALLBACK = {
  0: '#c4b08a',
  1: '#a68f6a',
  2: '#b8b2a4',
  3: '#c8c2b4'
}

/** 路肩兜底色（路基；比路面暗且偏冷，形成"路嵌在地里"的观感）。 */
export const SHOULDER_FALLBACK = {
  0: '#8d7c5e',
  1: '#6f6048',
  2: '#565044',
  3: '#4a443a'
}

/** 默认路宽（米），后端 palette 缺失时用。 */
export const WIDTH_FALLBACK = { 0: 1.8, 1: 2.8, 2: 5.0, 3: 7.0 }

// ==================== 颜色运算（无依赖，纯字符串进 / 数字出） ====================

/**
 * #rrggbb → 0xrrggbb（Pixi 只吃数字色）。
 *
 * @param {string} hex 形如 #8a5a3c（也容忍 8a5a3c 与 #abc）
 * @param {number} fallback 解析失败时的兜底色
 * @returns {number} 0xrrggbb
 */
export function toColor(hex, fallback = 0x888888) {
  if (typeof hex !== 'string') return fallback
  let s = hex.trim()
  if (s.startsWith('#')) s = s.slice(1)
  if (s.length === 3) s = s.split('').map((c) => c + c).join('')
  if (s.length !== 6 || !/^[0-9a-fA-F]{6}$/.test(s)) return fallback
  return parseInt(s, 16)
}

/**
 * 变亮/变暗（HSL 空间只动明度，色相不变——保证"同一面屋顶的向阳/背阴两面"）。
 *
 * @param {number} color 0xrrggbb
 * @param {number} amount -1~1（正=亮，负=暗）
 * @returns {number} 0xrrggbb
 */
export function shade(color, amount) {
  const r = (color >> 16) & 0xff
  const g = (color >> 8) & 0xff
  const b = color & 0xff
  const f = Math.max(-0.95, Math.min(0.95, amount))
  const mix = (c) => {
    const v = f >= 0 ? c + (255 - c) * f : c * (1 + f)
    return Math.max(0, Math.min(255, Math.round(v)))
  }
  return (mix(r) << 16) | (mix(g) << 8) | mix(b)
}

/**
 * 取"某套色板里的第 i 个"（负数/越界自动回绕，保证永远不返回 undefined）。
 *
 * @param {string[]} list 色板
 * @param {number} i 下标
 * @returns {string} 颜色
 */
export function pick(list, i) {
  return list[Math.abs(Math.trunc(i)) % list.length]
}

/**
 * 要素 → 屋顶色（数据里的 roofColor 优先，非法值回落大类默认）。
 *
 * @param {object} f 要素
 * @returns {number} 0xrrggbb
 */
export function roofOf(f) {
  return toColor(f?.roofColor, toColor(FALLBACK_ROOF))
}

/**
 * 要素 → 墙面色（大类映射；未知大类用民居色，避免出现纯灰块）。
 *
 * @param {object} f 要素
 * @returns {number} 0xrrggbb
 */
export function wallOf(f) {
  return toColor(WALL_BY_CATEGORY[f?.category], 0xd9c9a6)
}

/** 道路等级 → 路面色（后端 palette 下发值优先）。 */
export function roadColor(palette, grade) {
  const raw = palette?.roadColors?.[String(grade)]
  return toColor(raw, toColor(ROAD_FALLBACK[grade] || ROAD_FALLBACK[0]))
}

/** 道路等级 → 路肩色。 */
export function shoulderColor(palette, grade) {
  const raw = palette?.shoulderColors?.[String(grade)]
  return toColor(raw, toColor(SHOULDER_FALLBACK[grade] || SHOULDER_FALLBACK[0]))
}

/** 道路等级 → 宽度（米）。 */
export function roadWidth(palette, road) {
  const w = Number(road?.width)
  if (Number.isFinite(w) && w > 0) return w
  const g = Number(road?.grade) || 0
  return Number(palette?.roadWidths?.[String(g)]) || WIDTH_FALLBACK[g] || 2
}
