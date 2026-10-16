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
 * 圈子（社会结构分区）判定 —— UI V3.0 关系工作台「先看分成哪几拨人」的算法核心。
 *
 * <p><b>为什么不是纯图算法</b>：Louvain 之类的社区检测只在<b>关系数据存在</b>时才有意义。
 * 实测青禾村的真实状态是「17 个角色 / 0 条关系」（世界刚建、还没跑起来），此时任何图算法
 * 都只能给出 17 个孤立点——这正是重做前那块「空画布 + 一盘散点」的成因，界面看上去像坏了。
 * 而「谁和谁是一拨人」世界内核里本来就有答案：中观组织（宗族/祭祀/行会/群落/自发成组）
 * 与血缘谱系。所以分圈按固定优先级取用，**每一级都是真实世界事实，不是为了让画面好看而编的**。</p>
 *
 * <p>优先级（同一角色只落入一个圈，保证分区互斥且完备）：</p>
 * <ol>
 *   <li><b>中观组织</b>（{@code meta.groupId}）：世界内核自己长出来的势力/宗族/行会；</li>
 *   <li><b>血缘家族</b>（{@code meta.familyId}）：谱系连通分量（无组织归属者的家族）；</li>
 *   <li><b>图算法社区</b>（Louvain，边权=互动强度）：既无组织也无家族，但<b>确实有关系</b>的角色——
 *       这是「图算法只做补充」的落点；</li>
 *   <li><b>同业/归属文本</b>（occupation / affiliation）：既无归属又无关系的孤岛角色，
 *       按职业聚合，避免它们散落成无意义噪点；</li>
 *   <li><b>未归类</b>：连职业都没有的极少数节点，集中成一个「未归类」圈并在图例里明确标注——
 *       宁可诚实地说「这些人还没有社会关系」，也不假装他们属于某处。</li>
 * </ol>
 *
 * <p>纯函数：输入节点/边，输出圈子列表 + 角色→圈子索引；无 Vue、无 DOM、无副作用，
 * 可在 Node 里直接断言（见 tests/browser-verify/topology-logic-test.mjs）。</p>
 */
import algorithm from '@antv/algorithm'
// 显式 .js 后缀：本目录是「纯逻辑层」，既要被 Vite 打包，也要能被 Node 直接 import 做断言
// （Node ESM 不做扩展名补全，而 Vite 两种写法都认）。
import { clusterTypeLabel } from './relationPalette.js'
import { toClusterMap, normalizeDegreeMap } from './algorithmBridge.js'

// 默认导入（而非命名导入）：@antv/algorithm 的 lib 构建是 CJS，命名导入在 Node ESM 下不可见；
// 默认导入在 Vite（ESM 构建）与 Node（CJS 互操作）下都成立 —— 本模块因此可在 Node 里直接跑断言。
const { louvain, getDegree } = algorithm

/** 圈子来源（判定依据，详情栏与图例如实展示） */
export const CLUSTER_SOURCE = {
  /** 中观组织（世界内核真实归属） */
  GROUP: 'group',
  /** 血缘家族（谱系连通分量） */
  FAMILY: 'family',
  /** 图算法社区（关系数据推导） */
  DETECTED: 'detected',
  /** 同业（职业文本） */
  OCCUPATION: 'occupation',
  /** 未归类 */
  UNKNOWN: 'unknown'
}

/** 圈子来源 → 中文（详情栏展示「这个圈是怎么来的」） */
export const CLUSTER_SOURCE_LABEL = {
  group: '世界内核·组织归属',
  family: '世界内核·血缘谱系',
  detected: '关系网络·社区检测',
  occupation: '职业聚合',
  unknown: '未归类'
}

/**
 * 构建圈子分区。
 *
 * @param {object} input 输入
 *
 * @param {Array} input.nodes 归一化节点 [{ id, name, kind, importance, meta:{groupId,groupName,groupType,familyId,familyName,occupation,affiliation} }]
 *
 * @param {Array} input.edges 归一化边 [{ id, source, target, affinity, trust, weight }]
 *
 * @returns {{ clusters: Array, clusterOf: Object, sourceOf: Object }} 圈子列表（按成员数降序）、
 *          角色 ID → 圈子 ID、角色 ID → 来源
 */
export function buildClusters({ nodes = [], edges = [] } = {}) {
  const clusters = new Map() // clusterId → cluster
  const clusterOf = {}      // nodeId → clusterId
  const sourceOf = {}       // nodeId → CLUSTER_SOURCE

  const push = (id, label, type, source, node) => {
    let cluster = clusters.get(id)
    if (!cluster) {
      cluster = { id, label, type, source, members: [] }
      clusters.set(id, cluster)
    }
    cluster.members.push(node.id)
    clusterOf[node.id] = id
    sourceOf[node.id] = source
  }

  // ---------- ② 图算法社区：只处理「无组织/无家族归属」但有关系的角色 ----------
  const pending = []
  for (const n of nodes) {
    const meta = n.meta || {}
    if (meta.groupId != null) {
      push(`g:${meta.groupId}`, meta.groupName || '未命名组织', meta.groupType || 'community', CLUSTER_SOURCE.GROUP, n)
    } else if (meta.familyId) {
      push(`f:${meta.familyId}`, meta.familyName || '同族', 'blood', CLUSTER_SOURCE.FAMILY, n)
    } else {
      pending.push(n)
    }
  }

  const degree = safeDegree(nodes, edges)
  /** 角色 ID → 社区 ID（Louvain 命中者） */
  const communityOf = detectCommunities(pending, edges)
  /** 社区 ID → 成员（用于给社区起一个人类可读的名字） */
  const communityMembers = new Map()
  for (const n of pending) {
    const cid = communityOf[n.id]
    if (cid == null) continue
    if (!communityMembers.has(cid)) communityMembers.set(cid, [])
    communityMembers.get(cid).push(n)
  }

  // ---------- ③ 社区 / ④ 同业 / ⑤ 未归类 ----------
  for (const n of pending) {
    const cid = communityOf[n.id]
    if (cid != null) {
      const members = communityMembers.get(cid) || []
      // 社区用「度数最高的成员」命名（真实角色名，便于对照世界）——同名社区不重名靠 ID 区分
      const leader = members.slice().sort(
        (a, b) => (degree[b.id] || 0) - (degree[a.id] || 0) || String(a.id).localeCompare(String(b.id)))[0]
      push(`d:${cid}`, `往来圈 · ${leader?.name || '未命名'}`, 'community_detected', CLUSTER_SOURCE.DETECTED, n)
      continue
    }
    const occ = (n.meta?.occupation || '').trim()
    const aff = (n.meta?.affiliation || '').trim()
    if (occ) {
      push(`o:${occ}`, occ, 'occupation', CLUSTER_SOURCE.OCCUPATION, n)
    } else if (aff) {
      push(`a:${aff}`, aff, 'occupation', CLUSTER_SOURCE.OCCUPATION, n)
    } else {
      push('u:1', '未归类', 'unknown', CLUSTER_SOURCE.UNKNOWN, n)
    }
  }

  // ---------- 收尾：成员排序 + 圈子排序（确定性） ----------
  const list = [...clusters.values()].map(c => {
    c.members.sort((a, b) => (degree[b] || 0) - (degree[a] || 0) || String(a).localeCompare(String(b)))
    c.typeLabel = clusterTypeLabel(c.type)
    c.sourceLabel = CLUSTER_SOURCE_LABEL[c.source] || CLUSTER_SOURCE_LABEL.unknown
    c.size = c.members.length
    return c
  }).sort((a, b) => b.size - a.size || String(a.label).localeCompare(String(b.label)))

  // 圈子 ID 稳定排序后重新编号（布局与配色依赖顺序确定性）
  list.forEach((c, i) => { c.index = i })
  return { clusters: list, clusterOf, sourceOf }
}

/**
 * Louvain 社区检测（边权 = 互动强度；失败返回空对象，调用方走职业/未归类兜底）。
 *
 * @param {Array} nodes 待检测节点
 *
 * @param {Array} edges 全量边（内部只取两端都在待检测集合内的边）
 *
 * @returns {Object} 角色 ID → 社区 ID
 */
function detectCommunities(nodes, edges) {
  if (nodes.length === 0) return {}
  const ids = new Set(nodes.map(n => n.id))
  const subEdges = edges
    .filter(e => ids.has(e.source) && ids.has(e.target))
    .map(e => ({ id: e.id, source: e.source, target: e.target, weight: e.weight ?? 1 }))
  if (subEdges.length === 0) return {}
  try {
    // ⚠ 返回的是数组形状（见 algorithmBridge.js），必须经 toClusterMap 收敛
    return toClusterMap(louvain({ nodes: nodes.map(n => ({ id: n.id })), edges: subEdges }, false, 'weight'))
  } catch (e) {
    // 社区检测失败 → 交给下一级兜底（绝不让分圈失败拖垮整个工作台）
    return {}
  }
}

/**
 * 度数（无向合并；@antv/algorithm 不可用时本地兜底计算；返回值已归一化为纯数字）。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} edges 边
 *
 * @returns {Object} 角色 ID → 度数（number）
 */
function safeDegree(nodes, edges) {
  try {
    const d = getDegree({ nodes: nodes.map(n => ({ id: n.id })), edges: edges.map(e => ({ source: e.source, target: e.target })) })
    if (d && typeof d === 'object') {
      return normalizeDegreeMap(d)
    }
  } catch (e) {
    // 落到本地兜底
  }
  return fallbackDegree(nodes, edges)
}

/**
 * 度数本地兜底（无向）。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} edges 边
 *
 * @returns {Object} 角色 ID → 度数
 */
function fallbackDegree(nodes, edges) {
  const out = {}
  for (const n of nodes) out[n.id] = 0
  for (const e of edges) {
    out[e.source] = (out[e.source] || 0) + 1
    out[e.target] = (out[e.target] || 0) + 1
  }
  return out
}
