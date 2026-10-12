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

// 世界沙盘 API（S1：读接口 + 上次项目记录；S2：SSE 实时流 + 干预）
import http from './http'

/**
 * 沙盘总览：场景（含主场景）+ 全部 NPC 沙盘状态（读取前后端惰性初始化状态行）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchSandboxOverview = (projectId) => http.get(`/api/projects/${projectId}/sandbox/overview`)

/**
 * 沙盘场景列表（只读）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchSandboxScenes = (projectId) => http.get(`/api/projects/${projectId}/sandbox/scenes`)

/**
 * NPC 沙盘状态列表（只读，读取前同步状态行）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchSandboxStates = (projectId) => http.get(`/api/projects/${projectId}/sandbox/states`)

/**
 * 记录「上次所在项目」（应用启动直达上次项目；只写不清，返回画廊不清空）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const setLastProject = (projectId) => http.post('/api/local-account/last-project', { projectId: Number(projectId) })

// ==================== S2：SSE 实时流（订阅型长连接，10 事件契约） ====================

/**
 * 沙盘实时流 SSE URL（EventSource 消费；事件名：sandbox:init / npc:moved / npc:arrived /
 * npc:state / npc:thought / npc:talk / npc:join / npc:leave / scene:event / clock）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const sandboxStreamUrl = (projectId) => `/api/projects/${projectId}/sandbox/stream`

// ==================== S2：干预（统一委托后端既有能力） ====================

/**
 * 干预-插话：向指定 NPC 说一句话（自动查找或创建该 NPC 的单聊会话，回复经沙盘流 npc:talk 推送）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{npcRef: string, content: string}} data 插话请求
 */
export const sandboxInterveneTalk = (projectId, data) =>
  http.post(`/api/projects/${projectId}/sandbox/intervene/talk`, data, { timeout: 20000 })

/**
 * 干预-触发行动：手动触发特殊角色一轮思考决策 + 行动（AI 不可用时程序化兜底；人群不支持）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{npcRef: string, reason?: string}} data 触发请求（npcRef 统一身份引用，形如 npc:{id}）
 */
export const sandboxInterveneAction = (projectId, data) =>
  http.post(`/api/projects/${projectId}/sandbox/intervene/action`, data, { timeout: 30000 })

/**
 * 干预-手动事件：向世界注入一条事件（持久化 + 事件总线广播，沙盘经 scene:event 实时可视化）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{title?: string, content: string, kind?: string}} data 事件请求
 *
 * @returns {Promise<object>} 新增事件视图（同时间线接口）
 */
export const sandboxInterveneEvent = (projectId, data) =>
  http.post(`/api/projects/${projectId}/sandbox/intervene/event`, data)

/**
 * 干预-时间控制：更新世界时钟（rate/paused/worldRunning）或手动补推一轮（advance=true）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{rate?: number, paused?: boolean, worldRunning?: number, advance?: boolean}} data 时间控制请求
 *
 * @returns {Promise<object>} advance=false → { clock }；advance=true → 推进结果 Map
 */
export const sandboxInterveneClock = (projectId, data) =>
  http.post(`/api/projects/${projectId}/sandbox/intervene/clock`, data, { timeout: 30000 })

// ==================== S4：天气切换 ====================

/**
 * 干预-天气切换：把当前世界帧天气确定性切换为指定条件（晴/雨/雪/阴）。
 * 就地更新帧天气 + 环境刺激挂钩（NPC 感知）+ 世界事件 + 沙盘 SSE weather 推送。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {string} condition 天气条件（晴 / 雨 / 雪 / 阴）
 */
export const sandboxSwitchWeather = (projectId, condition) =>
  http.post(`/api/projects/${projectId}/sandbox/weather`, { condition })

// ==================== S3：资产摆放（actor_scene_placement CRUD） ====================

/**
 * 资产摆放-按场景列表（附资产登记表解析：资产名/分类/GLB 路径）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {number|string} sceneId 场景 ID
 */
export const fetchScenePlacements = (projectId, sceneId) =>
  http.get(`/api/projects/${projectId}/sandbox/scenes/${sceneId}/placements`)

/**
 * 资产摆放-创建（asset_id 必填；位置/缩放/朝向可缺省）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {number|string} sceneId 场景 ID
 * scaleX?: number, scaleY?: number, scaleZ?: number,
 * rotX?: number, rotY?: number, rotZ?: number, sortOrder?: number}} data 摆放请求
 */
export const createScenePlacement = (projectId, sceneId, data) =>
  http.post(`/api/projects/${projectId}/sandbox/scenes/${sceneId}/placements`, data)

/**
 * 资产摆放-更新（仅覆盖非空字段）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {number|string} placementId 摆放记录 ID
 *
 * @param {object} data 同创建（可只传需修改字段）
 *
 * @returns {Promise<object>} 更新后 ScenePlacementVO
 */
export const updateScenePlacement = (projectId, placementId, data) =>
  http.put(`/api/projects/${projectId}/sandbox/placements/${placementId}`, data)

/**
 * 资产摆放-删除。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {number|string} placementId 摆放记录 ID
 */
export const deleteScenePlacement = (projectId, placementId) =>
  http.delete(`/api/projects/${projectId}/sandbox/placements/${placementId}`)

// ==================== S3 补全：资产数据包 + 场景预设 ====================

/**
 * 资产数据包列表（扫描 uploads/scene-assets/ 下全部 manifest）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchSandboxPacks = (projectId) => http.get(`/api/projects/${projectId}/sandbox/packs`)

/**
 * 资产清单（含全局内置包 project_id=0 与项目级；可按 packId 过滤）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {string} [packId] 可选：数据包 ID 过滤
 */
export const fetchSandboxAssets = (projectId, packId) =>
  http.get(`/api/projects/${projectId}/sandbox/assets`, { params: { packId } })

/**
 * 导入资产数据包（manifest → actor_sandbox_asset 登记，project_id=0 全局；幂等可重跑）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {string} packId 数据包 ID（目录名，如 sandbox-xianxia-v1）
 */
export const importSandboxAssets = (projectId, packId) =>
  http.post(`/api/projects/${projectId}/sandbox/assets/import`, { packId })

/**
 * 内置场景预设列表（classpath:scene-presets/*.json）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchSandboxPresets = (projectId) => http.get(`/api/projects/${projectId}/sandbox/presets`)

/**
 * 应用内置场景预设到指定场景（校验 → 写 layout_json + 重建摆放表；幂等可重跑）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {number|string} sceneId 场景 ID
 *
 * @param {string} presetName 预设名（如 qinghe-village-v1）
 */
export const applySandboxPreset = (projectId, sceneId, presetName) =>
  http.post(`/api/projects/${projectId}/sandbox/scenes/${sceneId}/preset`, { presetName })

// ====================：NPC 内部状态（actor_npc_state 读接口） ====================

/**
 * 单 NPC 内部状态（角色页「状态」Tab / 沙盘详情；读取前惰性建行）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {string} npcRef NPC 引用（统一身份引用 npc:{id}）
 *
 * @returns {Promise<object>} NpcStateVO（需求/心情/情绪/urgency + fatigue + needLabels）
 */
export const fetchNpcState = (projectId, npcRef) =>
  http.get(`/api/projects/${projectId}/npc-state/${encodeURIComponent(npcRef)}`)

/**
 * 项目全部 NPC 内部状态（调试/批量展示）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchAllNpcStates = (projectId) => http.get(`/api/projects/${projectId}/npc-state`)

/**
 * 单 NPC 状态历史快照。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {string} npcRef NPC 引用（统一身份引用 npc:{id}）
 *
 * @param {number} [limit] 返回条数上限（可空=全部，默认对齐保留上限）
 */
export const fetchNpcStateHistory = (projectId, npcRef, limit) =>
  http.get(`/api/projects/${projectId}/npc-state/history`, {
    params: { npcRef, limit }
  })
