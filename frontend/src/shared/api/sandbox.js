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
export const fetchSandboxOverview = (projectId) => http.get(`/api/worlds/${projectId}/sandbox/overview`)

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
export const sandboxStreamUrl = (projectId) => `/api/worlds/${projectId}/sandbox/stream`

// ==================== S2：干预（统一委托后端既有能力） ====================

/**
 * 干预-插话：向指定 NPC 说一句话（自动查找或创建该 NPC 的单聊会话，回复经沙盘流 npc:talk 推送）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{npcRef: string, content: string}} data 插话请求
 */
export const sandboxInterveneTalk = (projectId, data) =>
  http.post(`/api/worlds/${projectId}/sandbox/intervene/talk`, data, { timeout: 20000 })

/**
 * 干预-触发行动：手动触发特殊角色一轮思考决策 + 行动（AI 不可用时程序化兜底；人群不支持）。
 *
 * @param {number|string} projectId 项目 ID
 *
 * @param {{npcRef: string, reason?: string}} data 触发请求（npcRef 统一身份引用，形如 npc:{id}）
 */
export const sandboxInterveneAction = (projectId, data) =>
  http.post(`/api/worlds/${projectId}/sandbox/intervene/action`, data, { timeout: 30000 })

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
  http.post(`/api/worlds/${projectId}/sandbox/intervene/event`, data)

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
  http.post(`/api/worlds/${projectId}/sandbox/intervene/clock`, data, { timeout: 30000 })

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
  http.post(`/api/worlds/${projectId}/sandbox/weather`, { condition })

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
  http.get(`/api/worlds/${projectId}/npc-state/${encodeURIComponent(npcRef)}`)

/**
 * 项目全部 NPC 内部状态（调试/批量展示）。
 *
 * @param {number|string} projectId 项目 ID
 */
export const fetchAllNpcStates = (projectId) => http.get(`/api/worlds/${projectId}/npc-state`)

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
  http.get(`/api/worlds/${projectId}/npc-state/history`, {
    params: { npcRef, limit }
  })
