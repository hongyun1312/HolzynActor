/*
 * Copyright (c) 2026 HONGYUN
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * 高亮片段（Spotlight）语义的唯一实现。
 *
 * <p>**为什么要有这个模块**：高亮（"我搜的词在文本哪里"）本来是各页各写一遍的小逻辑，
 * 于是同名关键词在不同页面出现不同口径——有的区分大小写、有的用 `v-html` 拼字符串（关键词里带
 * `<` 或 `&` 就能注入 HTML）、有的遇到正则元字符（`[`、`(`、`\`）直接抛异常让整页白屏。
 * 本模块把口径固定下来，组件层只负责渲染：</p>
 *
 * <ol>
 *   <li>**大小写不敏感**（中英文一致：`QING` 能命中 `qing`）；</li>
 *   <li>**多关键词**：以空白切分，逐个标记（"灵脉 封印" 两个词都亮）；</li>
 *   <li>**正则元字符安全**：关键词一律按字面量处理（`[`、`(`、`\`、`*` 都不会当正则用）；</li>
 *   <li>**纯数据输出**：返回 `{ text, hit }` 片段数组，调用方用 `<mark>` 渲染，
 *       **不生成 HTML 字符串**，因此关键词天然无法注入标记；</li>
 *   <li>**重叠取最长**：多个关键词命中同一段时合并为一段，避免嵌套标记；</li>
 *   <li>**窗口化**：`maxLength` 大于 0 时不是"砍头截断"，而是**以首个命中为中心截取窗口**
 *       并加省略号——否则命中在文本中后部时用户看不到"为什么命中"。</li>
 * </ol>
 *
 * @module shared/components/spotlight
 */

/** 省略号（与中文排版一致，用单个字符而非三个点） */
const ELLIPSIS = '…'

/**
 * 把关键词串/数组归一化成去重后的关键词数组。
 *
 * @param {string|Array<string>} keywords 关键词（字符串按空白切分）
 * @returns {Array<string>} 关键词数组（保持出现顺序，忽略空串）
 */
export function normalizeKeywords(keywords) {
  const raw = Array.isArray(keywords) ? keywords : String(keywords ?? '').split(/\s+/)
  const out = []
  for (const k of raw) {
    const t = String(k ?? '').trim()
    if (t && !out.includes(t)) out.push(t)
  }
  return out
}

/**
 * 转义正则元字符（本模块不把关键词当正则用，但保留该工具供将来"正则模式"复用）。
 *
 * @param {string} s 原串
 * @returns {string} 转义后的串
 */
export function escapeRegExp(s) {
  return String(s ?? '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 把文本按关键词切成片段（纯数据，不做任何 HTML 拼接）。
 *
 * @param {string} text 原文本
 * @param {string|Array<string>} keywords 关键词
 * @returns {Array<{text: string, hit: boolean}>} 片段数组；无关键词时返回单个非命中片段
 */
export function splitHighlights(text, keywords) {
  const src = String(text ?? '')
  const terms = normalizeKeywords(keywords)
  if (!src || !terms.length) return src ? [{ text: src, hit: false }] : []

  const lower = src.toLowerCase()
  const lowerTerms = terms.map((t) => t.toLowerCase())

  // 收集所有命中区间（大小写不敏感的字面量匹配）
  const ranges = []
  for (const term of lowerTerms) {
    let from = 0
    for (;;) {
      const at = lower.indexOf(term, from)
      if (at < 0) break
      ranges.push([at, at + term.length])
      from = at + term.length
    }
  }
  if (!ranges.length) return [{ text: src, hit: false }]

  // 排序 + 合并重叠/相邻（重叠取最长：合并后即最长覆盖，且不会产生嵌套标记）
  ranges.sort((a, b) => a[0] - b[0] || b[1] - a[1])
  const merged = [ranges[0]]
  for (let i = 1; i < ranges.length; i++) {
    const last = merged[merged.length - 1]
    const cur = ranges[i]
    if (cur[0] <= last[1]) last[1] = Math.max(last[1], cur[1])
    else merged.push(cur)
  }

  const out = []
  let cursor = 0
  for (const [s, e] of merged) {
    if (s > cursor) out.push({ text: src.slice(cursor, s), hit: false })
    out.push({ text: src.slice(s, e), hit: true })
    cursor = e
  }
  if (cursor < src.length) out.push({ text: src.slice(cursor), hit: false })
  return out
}

/**
 * 以首个命中为中心截取窗口（命中在窗口内必可见），并在两端加省略号。
 *
 * @param {Array<{text: string, hit: boolean}>} segments {@link splitHighlights} 的产物
 * @param {number} maxLength 最大字符数（<=0 表示不截断）
 * @returns {Array<{text: string, hit: boolean}>} 截断后的片段数组
 */
export function windowSegments(segments, maxLength) {
  if (!maxLength || maxLength <= 0) return segments
  const total = segments.reduce((n, s) => n + s.text.length, 0)
  if (total <= maxLength) return segments

  // 找到首个命中片段的绝对起点
  let firstHitAt = -1
  let acc = 0
  for (const s of segments) {
    if (s.hit) { firstHitAt = acc; break }
    acc += s.text.length
  }
  // 无命中：直接头部截断
  if (firstHitAt < 0) {
    const head = segments[0].text.slice(0, Math.max(0, maxLength - ELLIPSIS.length))
    return [{ text: head + ELLIPSIS, hit: false }]
  }

  // 命中居中：前面留 1/4 上下文，其余给命中与后文
  const lead = Math.floor(maxLength / 4)
  const start = Math.max(0, firstHitAt - lead)
  const end = Math.min(total, start + maxLength - (start > 0 ? ELLIPSIS.length : 0) - (start + maxLength < total ? ELLIPSIS.length : 0))

  const out = []
  let acc2 = 0
  for (const s of segments) {
    const s0 = acc2
    const s1 = acc2 + s.text.length
    acc2 = s1
    if (s1 <= start || s0 >= end) continue
    const from = Math.max(0, start - s0)
    const to = Math.min(s.text.length, end - s0)
    out.push({ text: s.text.slice(from, to), hit: s.hit })
  }
  if (start > 0) out.unshift({ text: ELLIPSIS, hit: false })
  if (end < total) out.push({ text: ELLIPSIS, hit: false })
  return out
}

/**
 * 一步到位：文本 + 关键词 → 渲染片段（截断 + 高亮）。
 *
 * @param {string} text 原文本
 * @param {string|Array<string>} keywords 关键词
 * @param {number} [maxLength=0] 最大字符数（<=0 不截断）
 * @returns {Array<{text: string, hit: boolean}>} 片段数组
 */
export function spotlight(text, keywords, maxLength = 0) {
  return windowSegments(splitHighlights(text, keywords), maxLength)
}

/**
 * 文本是否命中任一关键词（列表筛选用；与高亮同一口径，避免"筛出来了但一个都不亮"）。
 *
 * @param {string} text 文本
 * @param {string|Array<string>} keywords 关键词
 * @returns {boolean} 是否命中
 */
export function matchesKeywords(text, keywords) {
  const terms = normalizeKeywords(keywords)
  if (!terms.length) return true
  const lower = String(text ?? '').toLowerCase()
  return terms.some((t) => lower.includes(t.toLowerCase()))
}
