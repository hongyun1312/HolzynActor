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
 * @antv/algorithm 适配层（UI V3.0 · 纯函数层）。
 *
 * <p><b>为什么需要它</b>：这个库（G6 的依赖，MIT，已随项目本地安装）的真实返回形状与直觉不同，
 * 实测（v0.1.26）为：</p>
 * <ul>
 *   <li>{@code louvain(graph, directed, weightKey)} → <b>数组</b>：
 *       {@code { clusters: [{ id, nodes: [{ id, clusterId }], sumTot }], clusterEdges }}，
 *       <b>不是</b> {@code {nodeId: clusterId}} 映射；孤立节点不出现在结果里（诚实：它不属于任何社区）；</li>
 *   <li>{@code getDegree(graph)} → {@code { id: { degree, inDegree, outDegree } }}，
 *       取数值必须走 {@code .degree}（直接参与算术会得到 NaN，且 NaN 比较恒 false
 *       → 排序静默退化成「按名字」，这类 bug 在截图上完全看不出来）。</li>
 * </ul>
 *
 * <p>本层把两种形状都收敛成前端真正需要的形状，并对「库版本变化 / 库不可用」保持容错。</p>
 */

/**
 * Louvain 结果 → 节点 ID → 社区 ID 映射（兼容数组与映射两种形状）。
 *
 * @param {object} result louvain 返回值
 *
 * @returns {Object} 节点 ID → 社区 ID（字符串）
 */
export function toClusterMap(result) {
  const out = {}
  const clusters = result?.clusters
  if (Array.isArray(clusters)) {
    for (const c of clusters) {
      const cid = c?.id == null ? null : String(c.id)
      if (cid == null) continue
      const nodes = Array.isArray(c?.nodes) ? c.nodes : []
      for (const n of nodes) {
        const nid = n?.id
        if (nid != null) out[nid] = n?.clusterId != null ? String(n.clusterId) : cid
      }
    }
    return out
  }
  if (clusters && typeof clusters === 'object') {
    for (const [k, v] of Object.entries(clusters)) {
      if (v != null) out[k] = String(v)
    }
  }
  return out
}

/**
 * 度数取值（兼容 {degree,inDegree,outDegree} 对象与纯数字两种形状）。
 *
 * @param {Object} degreeMap getDegree / 本地兜底结果
 *
 * @param {string} id 节点 ID
 *
 * @returns {number} 度数（无法取值返回 0，绝不返回 NaN）
 */
export function degreeValueOf(degreeMap, id) {
  const raw = degreeMap ? degreeMap[id] : 0
  if (raw == null) return 0
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : 0
  const v = raw.degree ?? raw.outDegree ?? raw.inDegree
  return Number.isFinite(v) ? v : 0
}

/**
 * 度数表归一化：{@code {id: number|object}} → {@code {id: number}}。
 *
 * @param {Object} degreeMap 原始度数表
 *
 * @returns {Object} 归一化度数表
 */
export function normalizeDegreeMap(degreeMap) {
  const out = {}
  if (!degreeMap || typeof degreeMap !== 'object') return out
  for (const id of Object.keys(degreeMap)) out[id] = degreeValueOf(degreeMap, id)
  return out
}
