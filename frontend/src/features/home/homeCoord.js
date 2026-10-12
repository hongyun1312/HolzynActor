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
