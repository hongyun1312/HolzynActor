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
 * 关系工作台文案与数值格式化（纯函数层）。
 *
 * <p>为什么单独一层：详情栏、状态条、Tooltip、关系管理抽屉都要显示同一批数值
 * （好感、信任、互动新鲜度、强度），四处各写一份格式化必然出现「同一个数在四个地方不一样」。
 * 这里统一口径，并可被 Node 断言覆盖。</p>
 */

/**
 * 好感文本（正数带 +，0 与负数原样）。
 *
 * @param {number} v 好感值
 *
 * @returns {string} 文本
 */
export function formatAffinity(v) {
  const n = Number(v) || 0
  return n > 0 ? `+${n}` : String(n)
}

/**
 * 好感语义类名（正/负/零，供颜色区分；敌对与挚友都能一眼分辨）。
 *
 * @param {number} v 好感值
 *
 * @returns {string} 'pos' | 'neg' | ''
 */
export function affinityTone(v) {
  const n = Number(v) || 0
  if (n > 0) return 'pos'
  if (n < 0) return 'neg'
  return ''
}

/**
 * 互动新鲜度文本（人话，不是时间戳）。
 *
 * @param {string|null} iso 最近互动时间（ISO 串）
 *
 * @param {number} [nowMs] 当前时刻
 *
 * @returns {string} 如「今天」「3 天前」「久未往来」
 */
export function freshnessText(iso, nowMs) {
  if (!iso) return '久未往来'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return '久未往来'
  const now = nowMs || Date.now()
  const days = Math.floor((now - t) / 86400000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days} 天前`
  if (days < 30) return `${Math.floor(days / 7)} 周前`
  if (days < 365) return `${Math.floor(days / 30)} 个月前`
  return `${Math.floor(days / 365)} 年前`
}

/**
 * 互动强度（0~1）→ 百分比文本。
 *
 * @param {number} w 强度
 *
 * @returns {string} 如 '72%'
 */
export function formatStrength(w) {
  const n = Math.max(0, Math.min(1, Number(w) || 0))
  return `${Math.round(n * 100)}%`
}

/**
 * 关系规模文案（状态条/空态共用）。
 *
 * @param {number} nodeCount 可见节点数
 *
 * @param {number} edgeCount 可见边数
 *
 * @param {number} [totalNodes] 总节点数
 *
 * @param {number} [totalEdges] 总边数
 *
 * @returns {string} 如「节点 12 · 关系 8」
 */
export function countText(nodeCount, edgeCount, totalNodes, totalEdges) {
  const base = `节点 ${nodeCount} · 关系 ${edgeCount}`
  if (totalNodes != null && totalEdges != null && (totalNodes !== nodeCount || totalEdges !== edgeCount)) {
    return `${base}（全量 ${totalNodes}/${totalEdges}）`
  }
  return base
}
