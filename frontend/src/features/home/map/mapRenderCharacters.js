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

// 角色点绘制（屏幕空间，恒定像素大小）。
//
// 为什么画在**屏幕空间**而不是地图空间：角色点是一个 UI 记号，不占地。若画在地图空间，
// 缩小时它会缩成一粒沙、放大时变成一张圆桌——两种都不对。屏幕空间 + 每帧按相机重投影，
// 才能做到「点永远是 4 像素、位置永远对」。
//
// ⚠ 本文件只负责"把已有米坐标画成点"。米坐标从哪来（日程段 / 日程插值 / 旧坐标仿射）
//   由调用方决定，与绘制无关。

/** 点半径（屏幕像素）。 */
const DOT_R = 4.2

/** 选中点半径。 */
const SEL_R = 6.4

/** 人群点色 / 特殊角色点色。 */
const CROWD = 0x6f8fd0
const SPECIAL = 0xe8b34a

/**
 * 活动色：一眼看出「这个人在干什么」。
 *
 * <p>为什么值得按活动上色：一张镇上标着 36 个人点、全都一个颜色，看起来像撒了一把芝麻；
 * 分成「在睡（灰）/ 干活（琥珀）/ 吃饭（暖橙）/ 跑腿（蓝）/ 闲聊（绿）」之后，
 * 扫一眼就知道此刻镇子在什么节律上——白天田里一片琥珀、中午各家转暖橙、傍晚广场转绿。</p>
 */
const ACTIVITY_COLOR = {
  sleep: 0x6a7896,
  chores: 0x9a8fc0,
  work: 0xe0a94f,
  meal: 0xd07a5a,
  errand: 0x7fb2e8,
  chat: 0x8fd0a8,
  return: 0xa8b6cc
}

/**
 * 图例用的活动表（颜色与 {@link ACTIVITY_COLOR} 同源，前端只维护一份）。
 *
 * @type {{key:string,label:string,color:string}[]}
 */
export const ACTIVITY_LEGEND = [
  { key: 'work', label: '干活', color: '#e0a94f' },
  { key: 'meal', label: '吃饭', color: '#d07a5a' },
  { key: 'errand', label: '跑腿', color: '#7fb2e8' },
  { key: 'chat', label: '闲聊', color: '#8fd0a8' },
  { key: 'chores', label: '家务', color: '#9a8fc0' },
  { key: 'sleep', label: '睡觉（屋里）', color: '#6a7896' }
]

/**
 * 重画角色层。
 *
 * @param {import('pixi.js').Graphics} g 目标（必须是未缩放的屏幕空间容器）
 * @param {object[]} items 角色表 [{ key, x, z, special, selected, indoor, activity }]
 * @param {{scale:number, tx:number, ty:number, width:number, height:number, night?:number}} view 相机
 */
export function drawCharacters(g, items, view) {
  g.clear()
  if (!Array.isArray(items) || items.length === 0) return
  const { scale, tx, ty, width, height } = view
  // 夜里点稍微压一点亮度：不是关掉，而是让它退到夜色后面
  const nightAlpha = 1 - Math.max(0, Math.min(1, Number(view.night) || 0)) * 0.18
  const pad = 12
  for (const c of items) {
    const sx = Number(c.x) * scale + tx
    const sy = Number(c.z) * scale + ty
    if (!Number.isFinite(sx) || !Number.isFinite(sy)) continue
    if (sx < -pad || sy < -pad || sx > width + pad || sy > height + pad) continue
    const r = c.selected ? SEL_R : DOT_R
    const color = c.special ? SPECIAL : (ACTIVITY_COLOR[c.activity] || CROWD)
    // 在屋里的人画成「空心环」：他确实在那儿，但你在图上看到的是那栋房子，不是他站在门口
    const indoor = !!c.indoor && !c.selected
    if (indoor) {
      g.circle(sx, sy, r * 0.72)
      g.fill({ color, alpha: 0.22 * nightAlpha })
      g.circle(sx, sy, r * 0.72)
      g.stroke({ width: 1.2, color, alpha: 0.85 * nightAlpha })
      continue
    }
    g.circle(sx, sy, r)
    g.fill({ color, alpha: nightAlpha })
    g.circle(sx, sy, r)
    g.stroke({
      width: c.selected ? 2 : 1.4,
      color: c.selected ? 0xffe6a8 : 0xffffff,
      alpha: (c.selected ? 1 : 0.85) * nightAlpha
    })
    // 朝向箭头（只在近景画）：让「他在往哪走」看得见。
    // 朝向约定与后端一致：0=北（-Z），顺时针递增 → 屏幕向量 (sin, -cos)。
    const hd = Number(c.heading)
    if (Number.isFinite(hd) && scale >= 1.2) {
      const rad = (hd * Math.PI) / 180
      const dx = Math.sin(rad)
      const dz = -Math.cos(rad)
      g.moveTo(sx + dx * (r + 1.0), sy + dz * (r + 1.0))
      g.lineTo(sx + dx * (r + 5.5), sy + dz * (r + 5.5))
      g.stroke({ width: 1.5, color: 0xffe6a8, alpha: 0.85 * nightAlpha })
    }
  }
}
