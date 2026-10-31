/*
 * Copyright (c) 2026 HONGYUN
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
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
 * 引导用「示例世界」选取规则（唯一来源）。
 *
 * <p><b>为什么单独成模块</b>：新用户引导要说「点『青屿镇』的卡片进去」，世界画廊还要把
 * <b>同一张卡片</b>打上 `data-guide-demo="1"` 供聚光灯瞄准。两处各写一份匹配规则的话，
 * 一旦排序或命名规则变了（示例世界被改名 / 列表按最近打开排序），就会出现
 * 「引导嘴上说青屿镇、高亮却在另一个世界」的错位——这种错位在真实渲染里很难自查。</p>
 *
 * <p>优先级：名字像示例世界 → id 为 1 → 列表第一个。全部不命中时返回 null（引导会降级成
 * 「画廊里还没有世界」文案，不挖洞）。</p>
 */

/** 示例世界命名特征（浮生小镇 · 青屿镇 / 示例世界 ××） */
export const DEMO_WORLD_PATTERN = /青屿|示例世界/

/**
 * 从世界列表里挑出引导要带入的世界。
 *
 * @param {Array<{id:number,name?:string}>|null|undefined} list 世界列表（GET /api/worlds）
 * @returns {{id:number,name?:string}|null} 选中的世界；空列表返回 null
 */
export function pickGuideProject(list) {
  if (!Array.isArray(list) || list.length === 0) return null
  return (
    list.find((p) => p && DEMO_WORLD_PATTERN.test(p.name || '')) ||
    list.find((p) => p && p.id === 1) ||
    list[0] ||
    null
  )
}
