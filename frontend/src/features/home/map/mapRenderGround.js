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

// 聚落地面：房子的**地**，而不是房子脚下的草。
//
// 这一层解决的是整张图最刺眼的一件事：房子一栋一栋摆在草地上。
// 真实村镇里，房子之间是没有草的——那是被踩了几十年的土。院墙内是夯土院坝，
// 巷口是车辙，屋后是菜畦和柴堆，只有院墙外、田埂头才长草。
//
// 做法：把每栋房的平面按固定半径外扩，**所有矩形放进同一个 Graphics 一次性填充**。
// Pixi 对同一条路径的填充按 nonzero 绕数合并，所以重叠处不会叠成深色块，
// 整片聚落得到的是一个均一的土色面；再叠一层更小的外扩做"核心更实、边缘更虚"的过渡。
//
// 半径取 11 米 / 5 米两档：11 米正好是"一进院子 + 巷子"的尺度，
// 相邻两户的外扩会在巷子里接上，于是巷子两侧也是土；两档叠起来就是自然的浓淡。
import { Graphics } from 'pixi.js'
import { rectCorners } from './mapGeometry'

/** 聚落土色（踩实的黄褐土，比草地灰一档，和农田的作物黄分得开）。 */
export const EARTH = 0x9c8865

/** 外层（宽）：把整片聚落连成一块。 */
const OUTER_R = 10.0
const OUTER_ALPHA = 0.12

/** 内层（窄）：房子脚下更实。 */
const INNER_R = 4.6
const INNER_ALPHA = 0.15

/**
 * 构建聚落地面层。
 *
 * @param {object} bundle 渲染包
 * @returns {Graphics} 地面层（画在道路之下、地形之上）
 */
export function buildGroundLayer(bundle) {
  const g = new Graphics()
  g.label = 'ground'
  const features = Array.isArray(bundle?.features) ? bundle.features : []
  const houses = features.filter((f) => f?.kind === 'building')
  if (houses.length === 0) {
    return g
  }
  for (const [r, alpha] of [[OUTER_R, OUTER_ALPHA], [INNER_R, INNER_ALPHA]]) {
    for (const f of houses) {
      const w = Math.max(1, Number(f?.w) || 1) + r * 2
      const d = Math.max(1, Number(f?.d) || 1) + r * 2
      const corners = rectCorners({ x: f.x, z: f.z, w, d, rot: f.rot })
      // 外扩矩形自己就够「软」了：转角处两户的外扩天然重叠，union 之后边缘是折线而非直角块
      g.poly(corners.map(([x, z]) => [x, z]).flat())
    }
    g.fill({ color: EARTH, alpha })
  }
  return g
}
