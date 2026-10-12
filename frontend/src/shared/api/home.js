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

// 新首页（2D 世界地图 · 双主角并置）数据源封装（M4）
// 说明：零新后端——全复用「位置移动与地图生成系统」端点（world-map / movements）、
// 沙盘 SSE、世界图谱 11 只读端点、宏观 /macro/* 与观测域 /ai-runtime/status；
// 本文件只做「统一出口 + 坐标域换算」两个职责：
//   ① world-map 的 regions/placements/homes 坐标为【地图坐标域 [0, scene_size)】，
//      与沙盘状态 / SSE / movements 的【中心化坐标域】不一致，统一在此换算；
//   ② 2D 地图渲染、移动轨迹、在场判定全部以【中心化坐标】为唯一口径（对齐沙盘）。
import http from './http'

// ==================== 世界地图（M1：布局/住所） ====================

/**
 * 世界地图只读概览（regions/roads/placements/homes）。
 * 坐标域：返回前统一把 regions/placements/homes 的【地图坐标】换算为【中心化坐标】。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object>} { sceneSize, regions[], roads[], placements[], homes[] }
 */
export const fetchHomeWorldMap = async (projectId) => {
  const vo = await http.get(`/api/projects/${projectId}/world-map`)
  const size = Number(vo?.sceneSize) || 500
  const centered = (c) => (Number.isFinite(Number(c)) ? Number(c) - size / 2 : 0)
  return {
    projectId: vo?.projectId,
    sceneId: vo?.sceneId,
    sceneSize: size,
    style: vo?.style,
    source: vo?.source,
    regions: (vo?.regions || []).map((r) => ({
      ...r,
      centerX: centered(r.centerX),
      centerZ: centered(r.centerZ)
    })),
    roads: (vo?.roads || []).map((r) => ({
      ...r,
      path: (r.path || []).map((pt) => (Array.isArray(pt) && pt.length >= 2
        ? [centered(pt[0]), centered(pt[1])]
        : pt))
    })),
    placements: (vo?.placements || []).map((p) => ({
      ...p,
      posX: centered(p.posX),
      posZ: centered(p.posZ)
    })),
    homes: (vo?.homes || []).map((h) => ({
      ...h,
      posX: centered(h.posX),
      posZ: centered(h.posZ)
    }))
  }
}

/**
 * 单地点区域详情（区域 + 摆放 + 住所 + 在场角色）。
 * ⚠️ WorldMapLocationVO 不含 sceneSize，centerX/centerZ/posX/posZ 为【地图坐标域】；
 * 中心化换算由调用方用已加载的 world-map.sceneSize 完成（见 features/home/ 坐标工具）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {number|string} locationId 地点 ID
 * @returns {Promise<object>} WorldMapLocationVO（地图坐标域原值）
 */
export const fetchHomeLocationDetail = (projectId, locationId) =>
  http.get(`/api/projects/${projectId}/world-map/location/${locationId}`)

/**
 * 单角色住所（无住所返回 null；坐标中心化）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {string} ref NPC 引用（npc:{id}）
 * @returns {Promise<object|null>} HomeVO 或 null
 */
export const fetchHomeNpcHome = async (projectId, ref) => {
  const vo = await http.get(`/api/projects/${projectId}/world-map/npc/${encodeURIComponent(ref)}/home`)
  if (!vo) return null
  const size = Number(vo?.sceneSize) || 500
  const centered = (c) => (Number.isFinite(Number(c)) ? Number(c) - size / 2 : 0)
  if (vo?.posX != null) vo.posX = centered(vo.posX)
  if (vo?.posZ != null) vo.posZ = centered(vo.posZ)
  return vo
}

// ==================== 在途移动（M2：移动轨迹/进度） ====================

/**
 * 当前在途移动列表（MovementVO；坐标已是中心化域，直接透传）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<Array>} MovementVO[]
 */
export const fetchHomeMovements = (projectId) => http.get(`/api/projects/${projectId}/world-map/movements`)

// ==================== 沙盘（状态/干预/实时决策，复用既有 sandbox.js 出口） ====================

export {
  fetchSandboxOverview,
  fetchSandboxStates,
  sandboxStreamUrl,
  sandboxInterveneTalk,
  sandboxInterveneAction,
  sandboxInterveneEvent,
  sandboxInterveneClock,
  sandboxSwitchWeather
} from './sandbox'

export {
  fetchWorldClock,
  fetchWorldFrame,
  fetchEnvironmentContext,
  fetchRealtimeDecision,
  setRealtimeDecision,
  fetchMacroState,
  fetchMacroSnapshots,
  fetchMacroEvents,
  fetchMacroGroups,
  fetchMacroEmergence,
  fetchWorldLocations,
  fetchScenes,
  fetchEvolutions,
  startEvolution,
  fetchEvolutionDetail,
  joinEvolution,
  leaveEvolution,
  finishEvolution,
  deleteEvolution
} from './world'

export { fetchAiRuntimeStatus } from './observability'

export {
  fetchWorldGraphOverview,
  fetchWorldGraphLocations,
  fetchWorldGraphCharacters,
  fetchWorldGraphEvents,
  fetchWorldGraphFactions,
  fetchWorldGraphMacro,
  fetchWorldGraphLocationView,
  fetchWorldGraphCharacterView
} from './worldGraph'
