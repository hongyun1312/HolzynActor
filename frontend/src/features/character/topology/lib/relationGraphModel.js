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
 * 关系图数据模型（UI V3.0 · 纯函数层）：归一化 / 双向合并 / 筛选 / 自我中心网络 / 中心性。
 *
 * <p>职责：把后端 {@code CharacterRelationGraphVO} 的原始载荷，转成渲染与交互直接可用的模型：</p>
 * <ul>
 *   <li><b>归一化</b>：节点补齐 meta（无 meta 时给空对象，前端全程不判空）；边补齐权重（互动强度）；</li>
 *   <li><b>双向合并</b>：A→B 与 B→A 两条关系合并为一条双箭头边（原始两条都留在 {@code items} 里，
 *       详情栏可分别查看与编辑）——这是「大厂图谱」的常规处理：并排两条反向边既难看又难读；</li>
 *   <li><b>筛选</b>：类型 / 状态 / 强度阈值 / 节点类型 / 圈子 / 自我中心半径，统一在这里判定，
 *       画布与图例共用同一份判定，避免「图例说筛了、图上没筛」；</li>
 *   <li><b>中心性</b>：度数 + PageRank（影响力排行；@antv/algorithm 提供，失败本地兜底）。</li>
 * </ul>
 */

import algorithm from '@antv/algorithm'
// 显式 .js 后缀（同 communities.js：本目录要同时被 Vite 打包与 Node 断言）
import { edgeWidthOf, freshnessOpacity, NODE_GEOMETRY_REV } from './relationPalette.js'
import { normalizeDegreeMap } from './algorithmBridge.js'

// 默认导入：CJS/ESM 双环境下都可用（详见 communities.js 同名说明）
const { pageRank, getDegree } = algorithm

/**
 * 归一化后端载荷。
 *
 * @param {object} raw 后端返回 { nodes, relations }
 *
 * @returns {{ nodes: Array, edges: Array, relations: Array }} 归一化模型
 */
export function normalizeGraph(raw) {
  const rawNodes = raw?.nodes || []
  const rawRels = raw?.relations || []
  const nodes = rawNodes.map(n => ({
    id: n.id,
    name: n.name || '',
    kind: n.kind || 'ghost',
    type: n.type || null,
    importance: n.importance == null ? null : Number(n.importance),
    isProtagonist: n.isProtagonist === 1 || n.isProtagonist === true,
    title: n.title || '',
    detail: n.detail || '',
    avatarUrl: n.meta?.avatarUrl || null,
    meta: n.meta || {}
  }))
  const nodeIds = new Set(nodes.map(n => n.id))
  const relations = rawRels
    .filter(r => r.fromKey && r.toKey && nodeIds.has(r.fromKey) && nodeIds.has(r.toKey) && r.fromKey !== r.toKey)
    .map(r => ({
      id: r.id,
      fromKey: r.fromKey,
      toKey: r.toKey,
      fromName: r.fromName || '',
      toName: r.toName || '',
      relationType: r.relationType || '未知关系',
      description: r.description || '',
      affinity: Number(r.affinity ?? 0),
      trust: Number(r.trust ?? 0),
      relationState: r.relationState || '',
      relationStateLabel: r.relationStateLabel || '',
      lastInteractionAt: r.lastInteractionAt || null,
      fromRef: r.fromRef || null,
      toRef: r.toRef || null
    }))

  // 双向合并：以无序对为键，正反各一条 → 一条边（items 保留两条）
  const pairKey = (a, b) => (a < b ? `${a}__${b}` : `${b}__${a}`)
  const groups = new Map()
  for (const r of relations) {
    const key = pairKey(r.fromKey, r.toKey)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(r)
  }
  const edges = []
  for (const [key, items] of groups.entries()) {
    // 主关系取互动强度更高者（决定边色/粗细/标签），另一条作为 items 里的补充
    const sorted = items.slice().sort((a, b) => strengthOf(b) - strengthOf(a) || Number(a.id) - Number(b.id))
    const main = sorted[0]
    const weight = sorted.reduce((s, r) => s + strengthOf(r), 0) / sorted.length
    edges.push({
      id: `e:${key}`,
      source: main.fromKey,
      target: main.toKey,
      bidirectional: items.length > 1,
      items: sorted,
      relationType: main.relationType,
      relationTypes: [...new Set(sorted.map(r => r.relationType))],
      description: main.description,
      affinity: sorted.reduce((s, r) => s + r.affinity, 0) / sorted.length,
      trust: sorted.reduce((s, r) => s + r.trust, 0) / sorted.length,
      relationState: main.relationState,
      relationStateLabel: main.relationStateLabel,
      lastInteractionAt: sorted
        .map(r => r.lastInteractionAt)
        .filter(Boolean)
        .sort()
        .pop() || null,
      // 权重：互动强度（好感绝对值 + 信任）——供力导向引力与筛选阈值共用
      weight,
      width: edgeWidthOf(main.affinity, main.trust),
      opacity: freshnessOpacity(
        sorted.map(r => r.lastInteractionAt).filter(Boolean).sort().pop() || null)
    })
  }
  edges.sort((a, b) => String(a.id).localeCompare(String(b.id)))
  return { nodes, edges, relations }
}

/**
 * 单条关系的互动强度（0~1）。
 *
 * @param {object} r 关系
 *
 * @returns {number} 强度
 */
export function strengthOf(r) {
  const aff = Math.min(100, Math.abs(Number(r?.affinity) || 0)) / 100
  const tru = Math.min(100, Math.max(0, Number(r?.trust) || 0)) / 100
  return aff * 0.6 + tru * 0.4
}

/**
 * 构建自我中心网络（ego）：中心 + N 跳邻居。
 *
 * @param {string} centerId 中心角色 ID
 *
 * @param {Array} edges 边
 *
 * @param {number} [radius] 跳数（0=不限）
 *
 * @returns {Set<string>} 命中的角色 ID 集合（centerId 为 null 时返回空集）
 */
export function egoNeighborhood(centerId, edges, radius = 1) {
  const out = new Set()
  if (!centerId) return out
  out.add(centerId)
  if (!radius) return out
  const adj = new Map()
  for (const e of edges) {
    if (!adj.has(e.source)) adj.set(e.source, [])
    if (!adj.has(e.target)) adj.set(e.target, [])
    adj.get(e.source).push(e.target)
    adj.get(e.target).push(e.source)
  }
  let frontier = [centerId]
  for (let d = 0; d < radius; d++) {
    const next = []
    for (const id of frontier) {
      for (const nb of adj.get(id) || []) {
        if (!out.has(nb)) {
          out.add(nb)
          next.push(nb)
        }
      }
    }
    frontier = next
  }
  return out
}

/** 默认筛选条件（全部放开；UI 只在用户显式收窄时改这些值） */
export function defaultFilters() {
  return {
    /** 关系类型白名单（空数组=不限） */
    types: [],
    /** 关系状态白名单（空数组=不限） */
    states: [],
    /** 互动强度下限 0~1 */
    minStrength: 0,
    /** 节点类型：npc / crowd / ghost（空数组=不限） */
    kinds: [],
    /** 圈子 ID 白名单（空数组=不限） */
    clusterIds: [],
    /** 只看某人的 N 跳邻居（ego；null=不限） */
    egoCenter: null,
    egoRadius: 1
  }
}

/**
 * 应用筛选（画布、图例、计数、详情栏共用同一判定）。
 *
 * @param {object} model 归一化模型 { nodes, edges }
 *
 * @param {object} filters 筛选条件（defaultFilters 形状）
 *
 * @param {object} [extra] 额外上下文
 *
 * @param {object} [extra.clusterOf] 角色 ID → 圈子 ID
 *
 * @param {string} [extra.search] 搜索词（姓名/头衔/职业/归属/圈子名模糊匹配）
 *
 * @returns {{ nodes: Array, edges: Array, hiddenByEgo: Set<string>, matchedBySearch: Set<string> }}
 *          筛选后的节点与边（边要求两端都留下）、被 ego 排除的 ID、搜索命中的 ID
 */
export function applyFilters(model, filters, extra = {}) {
  const f = { ...defaultFilters(), ...(filters || {}) }
  const clusterOf = extra.clusterOf || {}
  const search = (extra.search || '').trim().toLowerCase()
  const egoSet = f.egoCenter ? egoNeighborhood(f.egoCenter, model.edges, f.egoRadius) : null

  const matchedBySearch = new Set()
  const hiddenByEgo = new Set()
  const kept = []
  for (const n of model.nodes) {
    if (egoSet && !egoSet.has(n.id)) {
      hiddenByEgo.add(n.id)
      continue
    }
    if (f.kinds.length && !f.kinds.includes(n.kind)) continue
    if (f.clusterIds.length && !f.clusterIds.includes(clusterOf[n.id])) continue
    kept.push(n)
    if (search && searchHits(n, search, extra.clusterLabelOf)) matchedBySearch.add(n.id)
  }
  const keptIds = new Set(kept.map(n => n.id))
  const edges = model.edges.filter(e => {
    if (!keptIds.has(e.source) || !keptIds.has(e.target)) return false
    if (f.types.length && !(e.relationTypes || [e.relationType]).some(t => f.types.includes(t))) return false
    if (f.states.length && !f.states.includes(e.relationState)) return false
    if (f.minStrength > 0 && e.weight < f.minStrength) return false
    return true
  })
  // 搜索时，命中者及其直接关联一起保留（图谱搜索的常规行为：命中的是人，就要看得见他的关系）
  if (search) {
    const keep = new Set(matchedBySearch)
    for (const e of edges) {
      if (keep.has(e.source)) keep.add(e.target)
      if (keep.has(e.target)) keep.add(e.source)
    }
    const nodes = kept.filter(n => keep.has(n.id) || matchedBySearch.size === 0)
    const ids = new Set(nodes.map(n => n.id))
    return {
      nodes,
      edges: edges.filter(e => ids.has(e.source) && ids.has(e.target)),
      hiddenByEgo,
      matchedBySearch
    }
  }
  return { nodes: kept, edges, hiddenByEgo, matchedBySearch }
}

/**
 * 搜索命中判定：姓名 / 头衔 / 职业 / 归属 / 圈子名 任一模糊命中。
 *
 * @param {object} node 节点
 *
 * @param {string} search 小写搜索词
 *
 * @param {Function} [clusterLabelOf] (nodeId) => 圈子名
 *
 * @returns {boolean} 是否命中
 */
function searchHits(node, search, clusterLabelOf) {
  const fields = [
    node.name,
    node.title,
    node.meta?.occupation,
    node.meta?.affiliation,
    node.meta?.groupName,
    node.meta?.familyName,
    node.meta?.location,
    typeof clusterLabelOf === 'function' ? clusterLabelOf(node.id) : ''
  ]
  return fields.some(v => v && String(v).toLowerCase().includes(search))
}

/**
 * 某人的关系行（详情栏「关系清单」的数据形状）。
 *
 * <p>为什么放在模型层：一行的字段（对方是谁、方向、关系类型组合、好感/信任、
 * 状态文案、互动新鲜度）会被详情栏、Tooltip、关系管理列表三处复用；
 * 统一在这里成形，可以保证「悬停看到的和点开看到的是同一份口径」。</p>
 *
 * @param {string} centerId 中心角色 ID
 *
 * @param {Array} edges 边（已双向合并）
 *
 * @param {Map|Object} nodeById 角色 ID → 节点
 *
 * @param {Function} [colorOf] (nodeId) => 颜色（圈子色，缺省中性色）
 *
 * @returns {Array} 关系行（按互动强度降序）
 */
export function relationRowsOf(centerId, edges, nodeById, colorOf) {
  const get = (id) => (nodeById instanceof Map ? nodeById.get(id) : nodeById?.[id])
  const colors = typeof colorOf === 'function' ? colorOf : () => '#94a3b8'
  const rows = []
  for (const e of edges) {
    const outgoing = e.source === centerId
    const incoming = e.target === centerId
    if (!outgoing && !incoming) continue
    const otherId = outgoing ? e.target : e.source
    const other = get(otherId)
    // 方向：双向合并的边既不算「我对他」也不算「他对我」，如实标注为「往来」
    const direction = e.bidirectional ? 'both' : (outgoing ? 'out' : 'in')
    rows.push({
      edgeId: e.id,
      direction,
      otherId,
      otherName: (other && other.name) || otherId,
      otherKind: (other && other.kind) || 'ghost',
      otherColor: colors(otherId),
      types: e.relationTypes && e.relationTypes.length ? e.relationTypes : [e.relationType],
      affinity: e.affinity,
      trust: e.trust,
      relationState: e.relationState,
      relationStateLabel: e.relationStateLabel,
      weight: e.weight,
      bidirectional: !!e.bidirectional,
      lastInteractionAt: e.lastInteractionAt,
      description: e.description,
      items: e.items || []
    })
  }
  return rows.sort((a, b) => (b.weight || 0) - (a.weight || 0)
    || String(a.otherName).localeCompare(String(b.otherName)))
}

/**
 * 中心性（度数 + PageRank）——影响力排行与「以人为心」布局的中心选取都靠它。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} edges 边
 *
 * @returns {{ degree: Object, rank: Object, top: Array }} 度数、PageRank、按 PageRank 降序排行
 */
export function computeCentrality(nodes, edges) {
  const simple = {
    nodes: nodes.map(n => ({ id: n.id })),
    edges: edges.map(e => ({ source: e.source, target: e.target }))
  }
  let degree = {}
  let rank = {}
  try {
    // ⚠ getDegree 返回 {degree,inDegree,outDegree} 对象（见 algorithmBridge.js），必须归一化
    degree = normalizeDegreeMap(getDegree(simple))
  } catch (e) {
    degree = fallbackDegree(nodes, edges)
  }
  try {
    rank = pageRank(simple, 1e-6, 0.85) || {}
  } catch (e) {
    rank = {}
  }
  const top = nodes.slice().sort((a, b) => (rank[b.id] || 0) - (rank[a.id] || 0)
    || (degree[b.id] || 0) - (degree[a.id] || 0)
    || String(a.id).localeCompare(String(b.id)))
  return { degree, rank, top }
}

/** 度数兜底计算（@antv/algorithm 不可用时）。 */
function fallbackDegree(nodes, edges) {
  const d = {}
  for (const n of nodes) d[n.id] = 0
  for (const e of edges) {
    d[e.source] = (d[e.source] || 0) + 1
    d[e.target] = (d[e.target] || 0) + 1
  }
  return d
}

/**
 * 数据指纹（节点集合 + 关系集合 + 布局 ID）：布局缓存的键，数据没变就不重算。
 *
 * @param {object} model 归一化模型
 *
 * @param {string} layout 布局 ID
 *
 * @returns {string} 指纹
 */
export function datasetKey(model, layout) {
  const nk = model.nodes.map(n => `${n.id}:${n.importance ?? ''}:${n.meta?.groupId ?? ''}:${n.meta?.familyId ?? ''}`).sort().join(',')
  const ek = model.edges.map(e => `${e.id}:${Math.round(e.weight * 100)}`).sort().join(',')
  let h = 2166136261
  const s = `${layout}|g${NODE_GEOMETRY_REV}|${nk}|${ek}`
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(36)
}
