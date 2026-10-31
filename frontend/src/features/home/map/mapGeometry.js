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

// 地图几何小工具：确定性哈希 + 矩形四角 + 包围盒。
//
// 为什么前端也要「确定性」：同一栋房子每次刷新都该长同一个样（同一组树冠位置、
// 同一个瓦片色），否则滚一下缩放就"变脸"，用户会立刻察觉这是随机噪声而不是房子。

/**
 * 字符串 → 32 位无符号哈希（FNV-1a）。用于「按 code 取稳定的伪随机值」。
 *
 * @param {string} str 输入
 * @returns {number} 0..2^32-1
 */
export function hash32(str) {
  let h = 0x811c9dc5
  const s = String(str ?? '')
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h >>> 0
}

/**
 * 稳定的 0~1 小数（同一 key 永远同一个值）。
 *
 * @param {string} key 键
 * @param {number} salt 盐（同一 key 取多个不同随机值时用）
 * @returns {number} 0~1
 */
export function unit(key, salt = 0) {
  return (hash32(`${key}#${salt}`) % 100000) / 100000
}

/**
 * 矩形四角（米）。要素的 polygon 与 (x,z,w,d,rot) 是同一份几何的两种表示，
 * 这里统一用后者重算——它天生"轴向已知"，画屋脊/条纹时不用再猜长轴方向。
 *
 * @param {object} f 要素（x,z,w,d,rot）
 * @returns {number[][]} [[x,z] × 4]，顺序：左上、右上、右下、左下（相对长轴/短轴）
 */
export function rectCorners(f) {
  const cx = Number(f?.x) || 0
  const cz = Number(f?.z) || 0
  const w = Math.max(0.2, Number(f?.w) || 0.2)
  const d = Math.max(0.2, Number(f?.d) || 0.2)
  const rot = ((Number(f?.rot) || 0) * Math.PI) / 180
  const cos = Math.cos(rot)
  const sin = Math.sin(rot)
  const hw = w / 2
  const hd = d / 2
  const at = (ox, oz) => [cx + ox * cos - oz * sin, cz + ox * sin + oz * cos]
  return [at(-hw, -hd), at(hw, -hd), at(hw, hd), at(-hw, hd)]
}

/**
 * 按 rot 旋转后的单位轴（长轴 u / 短轴 v），供条纹、屋脊使用。
 *
 * @param {number} rotDeg 旋转角（度）
 * @returns {{u:number[], v:number[]}} 单位向量
 */
export function axes(rotDeg) {
  const rot = ((Number(rotDeg) || 0) * Math.PI) / 180
  return { u: [Math.cos(rot), Math.sin(rot)], v: [-Math.sin(rot), Math.cos(rot)] }
}

/**
 * 要素包围盒（米）→ { minX, minZ, maxX, maxZ }。
 *
 * @param {object[]} features 要素表
 * @returns {{minX:number,minZ:number,maxX:number,maxZ:number}|null} 空表返回 null
 */
export function boundsOf(features) {
  let minX = Infinity
  let minZ = Infinity
  let maxX = -Infinity
  let maxZ = -Infinity
  for (const f of features || []) {
    const poly = Array.isArray(f?.polygon) && f.polygon.length >= 3 ? f.polygon : rectCorners(f)
    for (const pt of poly) {
      const x = Number(pt?.[0])
      const z = Number(pt?.[1])
      if (!Number.isFinite(x) || !Number.isFinite(z)) continue
      if (x < minX) minX = x
      if (z < minZ) minZ = z
      if (x > maxX) maxX = x
      if (z > maxZ) maxZ = z
    }
  }
  if (!Number.isFinite(minX)) return null
  return { minX, minZ, maxX, maxZ }
}

/**
 * 要素面积（平方米，鞋带公式）——用于「小配房/大仓房」的绘制排序与细节分级。
 *
 * @param {number[][]} poly 多边形（米）
 * @returns {number} 面积（绝对值）
 */
export function polygonArea(poly) {
  if (!Array.isArray(poly) || poly.length < 3) return 0
  let s = 0
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i]
    const b = poly[(i + 1) % poly.length]
    s += (Number(a[0]) || 0) * (Number(b[1]) || 0) - (Number(b[0]) || 0) * (Number(a[1]) || 0)
  }
  return Math.abs(s) / 2
}

/**
 * 折线总长（米）。
 *
 * <p>与后端 {@code GridAStar.polylineLength} 同一算法：轨迹的「走了多远」必须两边一致，
 * 否则前端算出来的 t 会偏，人会在半路突然跳一下。</p>
 *
 * @param {number[][]} pts 折线（米）
 * @returns {number} 长度（米）
 */
export function polylineLength(pts) {
  if (!Array.isArray(pts) || pts.length < 2) return 0
  let sum = 0
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1]
    const b = pts[i]
    const dx = (Number(b?.[0]) || 0) - (Number(a?.[0]) || 0)
    const dz = (Number(b?.[1]) || 0) - (Number(a?.[1]) || 0)
    sum += Math.hypot(dx, dz)
  }
  return sum
}

/**
 * 沿折线按弧长取点（与后端 {@code GridAStar.pointAt} 同语义，超出钳到端点）。
 *
 * @param {number[][]} pts  折线（米）
 * @param {number} along    已走距离（米）
 * @returns {number[]|null} [x, z]；空折线返回 null
 */
export function pointAtArc(pts, along) {
  if (!Array.isArray(pts) || pts.length === 0) return null
  if (pts.length === 1) return [Number(pts[0][0]) || 0, Number(pts[0][1]) || 0]
  let remain = Math.max(0, Number(along) || 0)
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1]
    const b = pts[i]
    const ax = Number(a?.[0]) || 0
    const az = Number(a?.[1]) || 0
    const bx = Number(b?.[0]) || 0
    const bz = Number(b?.[1]) || 0
    const seg = Math.hypot(bx - ax, bz - az)
    if (seg <= 1e-9) continue
    if (remain <= seg) {
      const t = remain / seg
      return [ax + (bx - ax) * t, az + (bz - az) * t]
    }
    remain -= seg
  }
  const last = pts[pts.length - 1]
  return [Number(last?.[0]) || 0, Number(last?.[1]) || 0]
}
