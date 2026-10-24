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

// 道路绘制：路基 + 路面两遍描边（PixiJS v8 Graphics）。
//
// 为什么必须两遍：单色一根线在卫星图上读起来像「画在地图上的记号」，不像路。
// 真实航拍里一条路永远是「暗的路肩/排水沟 + 亮的路面」两条边——加上 0.6~1.0 米的路基，
// 路立刻从"贴在图上"变成"嵌在地里"。等级越高路基越宽（主街两侧是房基与台阶）。
//
// 桥不在这里画：桥面要比路面**晚**绘制（压在河上），由要素层负责。
import { Container, Graphics } from 'pixi.js'
import { roadColor, roadWidth, shoulderColor, VERGE_ALPHA, YARD } from './mapPalette'

/**
 * 路基比路面每侧多出的宽度（米）——主街宽、田埂窄。
 *
 * <p>土路（0/1 级）**不画路基**：真实土路没有边，它是被踩出来的一片，中间硬、两侧渐渐
 * 没入土里。给土路描一圈比路面深的边，等于给它画了个"描边"，整张图上所有巷子都变成
 * 一条条带黑边的棕带子——这正是"地图像示意图"的来源。只有石板主街和官道才有路缘石。</p>
 */
const CASING = { 0: 0, 1: 0, 2: 0.55, 3: 0.75 }

/**
 * 路旁裸土带宽出的量（米/侧）。
 *
 * <p>这个值必须**小**：它曾经是 1.6/3.0/5.0/6.0，于是一条 3 米宽的村道两侧各带 3 米土带，
 * 视觉宽到 9 米——比两边房子的面宽还宽，村子看起来像被几条泥河切成几块。
 * 聚落地面已经整片铺过（见 mapRenderGround），路两侧不需要再靠土带化开硬边。</p>
 */
const VERGE = { 0: 1.0, 1: 1.6, 2: 2.6, 3: 3.2 }

/** 低倍视野下（px/米 < 该值）只画路面，不画路基：此时路基不足 1 像素，画了只是糊。 */
const CASING_MIN_PX_PER_M = 0.45

/** 裸土带从这一档比例尺开始画（再小就只是一圈脏边）。 */
const VERGE_MIN_PX_PER_M = 1.0

/**
 * 把 bundle.roads 画成一层。
 *
 * @param {object} bundle 渲染包（roads + palette）
 * @param {{pxPerM?:number, grades?:number[]|Set<number>}} opts 当前缩放（px/米），
 *        以及**可选的道路等级白名单**（调试用：只画选中的等级；不传 = 全画）
 * @returns {Container} 道路层（子层顺序：田埂 → 村道 → 主街 → 官道）
 */
export function buildRoadLayer(bundle, opts = {}) {
  const layer = new Container()
  layer.label = 'roads'
  const palette = bundle?.palette || {}
  const roads = Array.isArray(bundle?.roads) ? bundle.roads : []
  const pxPerM = Number(opts.pxPerM) || 1
  const withCasing = pxPerM >= CASING_MIN_PX_PER_M
  // 调试筛选用：null/空 = 不过滤（生产路径永远不传，行为与旧版完全一致）
  const allow = opts.grades == null
    ? null
    : new Set(Array.from(opts.grades).map((v) => Number(v)))

  // 等级从低到高：大路压小路，交叉口才不会被田埂切断。
  // 这条排序就是「显示层级」的唯一实现：数组越靠后越后画、越在上层，
  // 所以等级 3（干线官道）永远压在 2（镇内主街）、1（村庄道路）、0（田埂土路）之上。
  const sorted = [...roads]
    .filter((r) => (allow == null ? true : allow.has(Number(r.grade) || 0)))
    .sort((a, b) => (Number(a.grade) || 0) - (Number(b.grade) || 0))

  // **两遍描边必须分成两层**：路基在同一个 Graphics 里半透明地一条条描，
  // 交叉口处两条路基叠加就会变深，路网立刻长出一个个"墨团"。
  // 分成「先所有路基、再所有路面」两层后，同色不透明叠加完全看不出来——
  // 这就是矢量布尔并集（Area.union）在没有布尔运算的 Pixi 里的等效做法。
  // 最下面还有一层"裸土带"：路两侧被踩秃的土，它把路面和草地之间的硬边化开。
  const verge = new Graphics()
  verge.label = 'road-verge'
  const casing = new Graphics()
  casing.label = 'road-casing'
  const surface = new Graphics()
  surface.label = 'road-surface'
  layer.addChild(verge, casing, surface)

  for (const road of sorted) {
    const path = (road.path || []).filter((p) => Array.isArray(p) && p.length >= 2)
    if (path.length < 2) continue
    const grade = Number(road.grade) || 0
    const width = roadWidth(palette, road)
    if (pxPerM >= VERGE_MIN_PX_PER_M) {
      tracePath(verge, path)
      verge.stroke({
        width: width + (VERGE[grade] ?? 2) * 2,
        color: YARD,
        alpha: VERGE_ALPHA,
        cap: 'round',
        join: 'round'
      })
    }
    if (withCasing && (CASING[grade] ?? 0) > 0) {
      tracePath(casing, path)
      casing.stroke({
        width: width + (CASING[grade] ?? 0.5) * 2,
        color: shoulderColor(palette, grade),
        alpha: 1,
        cap: 'round',
        join: 'round'
      })
    }
    tracePath(surface, path)
    surface.stroke({
      width,
      color: roadColor(palette, grade),
      alpha: 1,
      cap: 'round',
      join: 'round'
    })
    // 高等级路面加一道极淡的中脊高光：让石板路在放大时有"被踩亮"的中线，
    // 而不是一块均匀的色带（卫星图上主街中线确实更亮）。
    if (grade >= 2 && pxPerM >= 1.1) {
      tracePath(surface, path)
      surface.stroke({
        width: Math.max(0.25, width * 0.18),
        color: 0xffffff,
        alpha: 0.16,
        cap: 'round',
        join: 'round'
      })
    }
  }
  return layer
}

/**
 * 把折线（米）写到 Graphics 上（只写路径，描边由调用方决定——两遍描边要复用同一条路径）。
 *
 * @param {Graphics} g 目标
 * @param {number[][]} path 折线（米）
 */
function tracePath(g, path) {
  const first = path[0]
  g.moveTo(Number(first[0]) || 0, Number(first[1]) || 0)
  for (let i = 1; i < path.length; i++) {
    g.lineTo(Number(path[i][0]) || 0, Number(path[i][1]) || 0)
  }
}
