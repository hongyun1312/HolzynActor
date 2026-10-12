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

// 世界功能域 API：世界时钟 / 时间线事件 / 场景 / 世界演化
import http from './http'

// ==================== 世界时钟 + 世界模拟 ====================
// 世界时钟状态（当前游戏时刻/速率/最近推进摘要）
export const fetchWorldClock = (projectId) => http.get(`/api/projects/${projectId}/world-clock`)
// 世界时钟（rate/paused/worldStartAt/worldStartGameHour；空字段保持原值）
export const updateWorldClock = (projectId, data) => http.put(`/api/projects/${projectId}/world-clock`, data)
// 手动补推一轮（调试/演示：立即按当前流逝推进角色/人群/事件）
export const advanceWorldClock = (projectId) => http.post(`/api/projects/${projectId}/world-clock/advance`)

// ==================== 每日世界帧（S4 天气可视化数据源） ====================
// 当前生效世界帧（active；无 → 404）。weatherJson = {"区域": {"condition": "晴", "temperature": 15, "wind": "北风"}}
export const fetchWorldFrame = (projectId) => http.get(`/api/projects/${projectId}/world-frame`)

// ==================== 时间季节系统 ====================
// 统一环境上下文（季节/游戏月/时刻阶段/日出日落/天气/气温/体感/环境状态文本/感官标签；
// 确定性计算 + 润色缓存读取，零同步 AI）
export const fetchEnvironmentContext = (projectId, gameSecond) =>
  http.get(`/api/projects/${projectId}/environment/context`, { params: { gameSecond } })
// 项目季节骨架配置（季节固定绑定表 + 四季骨架：内置默认/项目覆盖）
export const fetchClimateConfig = (projectId) => http.get(`/api/projects/${projectId}/climate-config`)
// 覆盖项目四季骨架（body={configJson}：含 spring/summer/autumn/winter 四季的骨架 JSON）
export const updateClimateConfig = (projectId, configJson) =>
  http.put(`/api/projects/${projectId}/climate-config`, { configJson })

// ==================== 实时决策激活 ====================
// 查询项目实时决策激活状态（{active, manualEnabled, sandboxActive, evolutionActive}）
export const fetchRealtimeDecision = (projectId) => http.get(`/api/projects/${projectId}/realtime-decision`)
// 实时决策激活：{action: open|close|set, reason?: sandbox|evolution, enabled?: boolean}
// open/close=页面生命周期驱动（沙盘页/演化页）；set=手动常开（手动优先于页面自动态）
export const setRealtimeDecision = (projectId, data) => http.put(`/api/projects/${projectId}/realtime-decision`, data)

// ==================== 时间线/事件 ====================
// 项目统一时间线（事件/行动/记忆聚合；params: types/characterId/startDate/endDate）
export const fetchProjectTimeline = (projectId, params) => http.get(`/api/projects/${projectId}/timeline`, { params })
// 手动新增事件（持久化 actor_event，source=manual）
export const createProjectEvent = (projectId, data) => http.post(`/api/projects/${projectId}/events`, data)
// AI 从世界观/项目概况识别生成事件（source=ai）
export const aiGenerateProjectEvent = (projectId) => http.post(`/api/projects/${projectId}/events/ai-generate`, null, { timeout: 60000 })
// B1 世界事件总线：项目世界事件查询（params: kind/source/locationId/characterRef/fromGameHour/toGameHour/limit）
export const fetchWorldEvents = (projectId, params) => http.get(`/api/projects/${projectId}/world-events`, { params })
// B1 世界事件总线：项目世界事件流 SSE URL（EventSource 消费；事件名 world-event）
export const worldEventsStreamUrl = (projectId) => `/api/projects/${projectId}/world-events/stream`

// ==================== 场景（世界演化场景/地点） ====================
export const fetchScenes = (projectId) => http.get(`/api/projects/${projectId}/scenes`)
export const createScene = (projectId, data) => http.post(`/api/projects/${projectId}/scenes`, data)
export const updateScene = (id, data) => http.put(`/api/scenes/${id}`, data)
export const deleteScene = (id) => http.delete(`/api/scenes/${id}`)
// 场景 AI 自动填充：基于世界观+角色详情自动生成场景（耗时长，覆盖默认超时）
export const aiGenerateScenes = (projectId, data) => http.post(`/api/projects/${projectId}/scenes/ai-generate`, data, { timeout: 120000 })

// ==================== 世界演化（场景化演化） ====================
export const fetchEvolutions = (projectId) => http.get(`/api/projects/${projectId}/evolutions`)
// 开始演化：{mode? manual/ai, sceneId?, background?, characterIds?}（AI 模式含选角，耗时长）
export const startEvolution = (projectId, data) => http.post(`/api/projects/${projectId}/evolutions`, data, { timeout: 120000 })
export const fetchEvolutionDetail = (id) => http.get(`/api/evolutions/${id}`)
// 推进一轮（AI 编排角色言行/加入退场/收尾；可能触发归档）
export const advanceEvolution = (id) => http.post(`/api/evolutions/${id}/turn`, null, { timeout: 120000 })
// 手动加入/退场角色
export const joinEvolution = (id, characterId) => http.post(`/api/evolutions/${id}/join`, { characterId })
export const leaveEvolution = (id, characterId) => http.post(`/api/evolutions/${id}/leave`, { characterId })
// 结束演化并归档（事件入时间线 + 参与者角色级记忆隔离）
export const finishEvolution = (id) => http.post(`/api/evolutions/${id}/finish`, null, { timeout: 120000 })
// 删除演化会话（级联清理参与者与轮次消息；归档事件保留在时间线）
export const deleteEvolution = (id) => http.delete(`/api/evolutions/${id}`)

// ==================== 宏观社会演化 ====================
// 当前宏观状态摘要（核心指标 + 简报 + 全量五段 indicators + 民怨/季节/收成系数/模板）
export const fetchMacroState = (projectId) => http.get(`/api/projects/${projectId}/macro/state`)
// 宏观指标快照历史（params: tickType/fromDay/toDay/limit；曲线回看源）
export const fetchMacroSnapshots = (projectId, params) =>
  http.get(`/api/projects/${projectId}/macro/snapshot`, { params })
// 宏观事件列表（params: fromDay/toDay/severity/limit）
export const fetchMacroEvents = (projectId, params) =>
  http.get(`/api/projects/${projectId}/macro/events`, { params })
// NPC 行动入账流水
export const fetchMacroDeltas = (projectId, params) =>
  http.get(`/api/projects/${projectId}/macro/deltas`, { params })
// 初始化/重置宏观状态（body 可空：population/grainPriceBase/grainStock/...）
export const initializeMacro = (projectId, overrides) =>
  http.post(`/api/projects/${projectId}/macro/initialize`, overrides || {})
// 世界观模板
export const fetchMacroTemplate = (projectId) => http.get(`/api/projects/${projectId}/macro/template`)
// 手动覆盖世界观模板（body={templateId: ancient|xianxia|wuxia|modern|future}）
export const setMacroTemplate = (projectId, templateId) =>
  http.post(`/api/projects/${projectId}/macro/template`, { templateId })

// ==================== 中观协调 + 二阶涌现 ====================
// 中观组织列表
export const fetchMacroGroups = (projectId) => http.get(`/api/projects/${projectId}/macro/groups`)
// 二阶涌现事件列表
export const fetchMacroEmergence = (projectId, params) =>
  http.get(`/api/projects/${projectId}/macro/emergence`, { params })
// 行为多样性注入记录
export const fetchMacroDiversity = (projectId, params) =>
  http.get(`/api/projects/${projectId}/macro/diversity`, { params })

// ==================== 世界观地点表（AI 提取 + 手动维护，项目级） ====================
// 查询项目全部地点（排序稳定）
export const fetchWorldLocations = (projectId) => http.get(`/api/projects/${projectId}/world-locations`)
//
export const createWorldLocation = (projectId, data) => http.post(`/api/projects/${projectId}/world-locations`, data)
// 地点
export const updateWorldLocation = (projectId, id, data) => http.put(`/api/projects/${projectId}/world-locations/${id}`, data)
// 删除地点
export const deleteWorldLocation = (projectId, id) => http.delete(`/api/projects/${projectId}/world-locations/${id}`)
// 编辑模式全量保存（增删改查一体，按数组顺序整体替换）
export const batchSaveWorldLocations = (projectId, items) => http.put(`/api/projects/${projectId}/world-locations/batch`, items)
// AI 重新提取地点（基于已存地理设定文本，与现有合并追加；耗时长，覆盖默认超时）
export const extractWorldLocations = (projectId) => http.post(`/api/projects/${projectId}/world-locations/extract`, null, { timeout: 120000 })
