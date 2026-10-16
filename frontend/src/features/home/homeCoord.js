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

// 新首页坐标工具（M4）
// 职责：坐标域换算 + 2D 地图画布投影的纯函数（零依赖、可单测）。
// 坐标域约定（对齐后端「位置移动与地图生成系统」）：
//   - 地图坐标域（map）：[0, scene_size)，world-map 的 regions/placements/homes 原值；
//   - 中心化坐标域（sandbox）：[-size/2, +size/2]，沙盘状态 posX/posZ、SSE、movements 同域；
//   - 1 格 = 0.2 米（速度换算 / 距离展示用）。

/** 1 格对应的真实米数（与后端 WorldMapProperties 同源） */
export const METERS_PER_GRID = 0.2

/** 场景边长兜底（非法/过小用 500；对齐沙盘页 validSceneSize） */
export function validSceneSize(v) {
  const n = Number(v)
  return Number.isFinite(n) && n >= 100 ? n : 500
}

/** 地图坐标 → 中心化坐标 */
export function mapToSandbox(coord, size) {
  const n = Number(coord)
  if (!Number.isFinite(n)) return 0
  return n - validSceneSize(size) / 2
}

/** 中心化坐标 → 地图坐标 */
export function sandboxToMap(coord, size) {
  const n = Number(coord)
  if (!Number.isFinite(n)) return 0
  return n + validSceneSize(size) / 2
}

/**
 * 中心化坐标 → 2D 画布百分比（0~100，Y 轴向下）。
 * 画布以「正方形场景 + 等比例留白」为基准：中心化坐标 -size/2..+size/2 线性映射到 5..95，
 * 留出区域圆/角色圆点/姓名标签不被画布边缘裁切。
 *
 * @param {number} coord 中心化坐标
 * @param {number} size 场景边长
 * @returns {number} 0~100 百分比
 */
export function toPercent(coord, size) {
  const s = validSceneSize(size)
  const n = Number(coord)
  if (!Number.isFinite(n)) return 50
  const t = Math.max(-s / 2, Math.min(s / 2, n)) / s // -0.5..0.5
  return 50 + t * 90 // 5..95
}

/** 两中心化坐标点欧氏距离（格） */
export function distGrids(x1, z1, x2, z2) {
  return Math.hypot(Number(x1) || 0 - Number(x2) || 0, Number(z1) || 0 - Number(z2) || 0)
}

/** 两中心化坐标点欧氏距离（米，1 格=0.2m） */
export function distMeters(x1, z1, x2, z2) {
  return distGrids(x1, z1, x2, z2) * METERS_PER_GRID
}

/**
 * 角色是否位于区域圆内（中心化坐标；region 的 centerX/centerZ 已中心化）。
 * 含边界容差 1 格，用于地图在场判定。
 */
export function inRegion(x, z, region) {
  if (!region || x == null || z == null) return false
  return distGrids(x, z, region.centerX, region.centerZ) <= (Number(region.radius) || 0) + 1
}

/** 半径米数 → 格数（区域半径展示辅助；radius 单位=格） */
export function radiusMeters(radius) {
  return (Number(radius) || 0) * METERS_PER_GRID
}

/* ============================================================
   2D 世界地图「内容自适应投影」（2026-10-14 第 65 次修复）
   背景：地图原先固定把「整个场景 500×500 格」投影到画布 5%~95%，
   而青禾村的实际内容只占中心约 260×415 格（= 场景的 52%×83% 里的
   一小块，村落本体甚至只占 ±40 格）——于是：
   ① 所有区域/住所/角色被压在画布中段一小片区域里（实测截图：10 个
      区域名 + 12 个角色名 + 7 个住所全部叠在一起，完全不可读）；
   ② 画布是纵向 596×816，正方形 viewBox 触发等比留白，上下各空一条。
   修法：先算「内容包围盒」，再把包围盒等比铺满画布（保比例、居中、留边），
   即真实地图软件的 fit-to-content 行为。以下为纯函数（零依赖、可单测）。
   ============================================================ */

/** 内容包围盒的最小跨度（格）：内容过小/只有一个点时避免被放大到失真 */
export const MIN_CONTENT_SPAN = 90

/** 各类元素的外扩量（格）：圆点/字形自身也有体积，不能被画布边缘裁切 */
const PAD_REGION_MIN = 6
const PAD_PLACEMENT = 6
const PAD_HOME = 9
const PAD_NPC = 6
const PAD_ROAD = 4

/** 数值安全转换（非有限值返回 null，便于调用方跳过） */
function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 计算地图内容包围盒（中心化坐标域）。
 *
 * @param {object} src 各类元素：{ regions, placements, homes, npcs, roads, movements, sceneSize }
 * @returns {{minX:number,maxX:number,minZ:number,maxZ:number}|null} null=无任何内容
 */
export function computeContentBounds(src = {}) {
  const size = validSceneSize(src.sceneSize)
  const half = size / 2
  let minX = Infinity; let maxX = -Infinity; let minZ = Infinity; let maxZ = -Infinity

  const grow = (x, z, pad = 0) => {
    if (x == null || z == null) return
    minX = Math.min(minX, x - pad); maxX = Math.max(maxX, x + pad)
    minZ = Math.min(minZ, z - pad); maxZ = Math.max(maxZ, z + pad)
  }

  // 区域圆：圆心 ± 半径（半径过小时给一个最小可视外扩）
  for (const r of src.regions || []) {
    const cx = num(r?.centerX); const cz = num(r?.centerZ)
    if (cx == null || cz == null) continue
    const rad = Math.max(Number(r?.radius) || 0, PAD_REGION_MIN)
    grow(cx, cz, rad)
  }
  for (const p of src.placements || []) grow(num(p?.posX), num(p?.posZ), PAD_PLACEMENT)
  for (const h of src.homes || []) grow(num(h?.posX), num(h?.posZ), PAD_HOME)
  for (const n of src.npcs || []) grow(num(n?.posX), num(n?.posZ), PAD_NPC)
  for (const rd of src.roads || []) {
    for (const pt of rd?.path || []) {
      if (!Array.isArray(pt)) continue
      grow(num(pt[0]), num(pt[1]), PAD_ROAD)
    }
  }
  for (const m of src.movements || []) {
    grow(num(m?.fromX), num(m?.fromZ), PAD_ROAD)
    grow(num(m?.toX), num(m?.toZ), PAD_ROAD)
  }

  if (!Number.isFinite(minX)) return null
  // 钳制到场景内（数据异常时不让视图飞出场景）
  return {
    minX: Math.max(-half, minX), maxX: Math.min(half, maxX),
    minZ: Math.max(-half, minZ), maxZ: Math.min(half, maxZ)
  }
}

/**
 * 内容自适应投影（fit-to-content）。
 * <p>把内容包围盒等比铺进 viewBox（保留边距、居中），返回线性投影参数：</p>
 * <pre>screenX = x0 + coord * scale；screenZ = z0 + coord * scale</pre>
 * <p>等比（单一 scale）= 世界不变形；minSpan 保证内容极少时不会放大到失真。</p>
 *
 * @param {{minX,maxX,minZ,maxZ}|null} bounds 内容包围盒（null → 退化为整场景）
 * @param {number} vbW viewBox 宽（用户单位）
 * @param {number} vbH viewBox 高（用户单位）
 * @param {object} [opts] { sceneSize, padding（0~0.3 边距比例）, minSpan }
 * @returns {{scale:number,x0:number,z0:number,cx:number,cz:number}}
 */
export function fitProjection(bounds, vbW, vbH, opts = {}) {
  const size = validSceneSize(opts.sceneSize)
  const half = size / 2
  // 5% 边距：既让内容尽量铺满画布（大厂地图观感），又给标签光晕留出余量
  const padding = Number.isFinite(opts.padding) ? opts.padding : 0.05
  const minSpan = Number.isFinite(opts.minSpan) ? opts.minSpan : MIN_CONTENT_SPAN

  const bb = bounds || { minX: -half, maxX: half, minZ: -half, maxZ: half }
  const cx = (bb.minX + bb.maxX) / 2
  const cz = (bb.minZ + bb.maxZ) / 2
  const w = Math.max(bb.maxX - bb.minX, minSpan)
  const h = Math.max(bb.maxZ - bb.minZ, minSpan)

  const usableW = Math.max(1, vbW * (1 - padding * 2))
  const usableH = Math.max(1, vbH * (1 - padding * 2))
  const scale = Math.min(usableW / w, usableH / h)

  return { scale, cx, cz, x0: vbW / 2 - cx * scale, z0: vbH / 2 - cz * scale }
}

