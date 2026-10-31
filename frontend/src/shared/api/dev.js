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
 * 开发者模式控制台 API 层。
 *
 * <p><b>为什么单独一个文件而不是塞进既有 api/*</b>：开发者模式是「一个页面域」而不是「一个业务域」，
 * 它同时消费三类后端：</p>
 * <ul>
 *   <li>{@code /api/dev/**} —— 本轮新增（参数目录/审计/NPC 全息/总览自检/系统）；</li>
 *   <li>{@code /api/debug/**} —— 既有调试只读面（AI Trace / 日志 SSE / DB 浏览 / 漂移 / Prompt 指标）；</li>
 *   <li>既有业务只读接口（世界时钟 / 宏观 / 记忆 / 决策协调 / 评估）——直接复用 {@code @/shared/api} 里已导出的函数，
 *       本文件只为「调试面里没有、但开发者页需要」的那几个补薄封装。</li>
 * </ul>
 * <p>这样做的目的是让「哪些调用是调试专用的」在代码里一目了然：以后下线 debug-frontend 时，
 * 只需看本文件与 features/dev 目录。</p>
 */

import http from './http'

/* ==================== 1. /api/dev（本轮新增） ==================== */

/** 开发者总览（引擎/内容/成本/参数 + 最近异常） */
export const fetchDevOverview = (projectId) => http.get('/api/dev/overview', { params: { projectId } })

/** 前置条件自检（DB/迁移/世界挂载/AI 供应商/决策模型/目录可写） */
export const fetchDevHealth = (projectId) => http.get('/api/dev/health', { params: { projectId } })

/** 系统环境（版本/JDK/OS/内存/目录/迁移/覆盖文件） */
export const fetchDevSystem = () => http.get('/api/dev/system')

/**
 * 参数目录。
 *
 * @param {{domain?: string, keyword?: string}} params 过滤条件
 * @returns {Promise<{rows: Array, stats: Object}>} 参数行 + 统计
 */
export const fetchDevParams = (params = {}) => http.get('/api/dev/params', { params })

/** 参数目录统计（总数/已改/待重启/按域分布） */
export const fetchDevParamStats = () => http.get('/api/dev/params/stats')

/** 已改参数（覆盖行） */
export const fetchDevOverriddenParams = () => http.get('/api/dev/params/overridden')

/**
 * 写入单个参数。
 *
 * @param {{key: string, value: string, confirm?: boolean, page?: string, operator?: string}} body 载荷
 */
export const saveDevParam = (body) => http.put('/api/dev/params', body)

/**
 * 单键恢复默认。
 *
 * @param {{key: string, page?: string, operator?: string}} params 查询参数
 */
export const resetDevParam = (params) => http.delete('/api/dev/params', { params })

/**
 * 全部恢复默认（confirm 必须为 true）。
 *
 * @param {{confirm: boolean, page?: string, operator?: string}} body 载荷
 */
export const resetAllDevParams = (body) => http.post('/api/dev/params/reset-all', body)

/**
 * 审计分页。
 *
 * @param {{page?: number, size?: number, action?: string, target?: string}} params 查询参数
 */
export const fetchDevAudit = (params = {}) => http.get('/api/dev/audit', { params })

/**
 * 追加审计（重启后端 / 导出数据等前端动作留痕）。
 *
 * @param {object} body {action, target, oldValue?, newValue?, effect?, page?, operator?, note?}
 */
export const appendDevAudit = (body) => http.post('/api/dev/audit', body)

/** 角色列表（特殊型 + 普通型，含状态摘要） */
export const fetchDevNpcs = (projectId) => http.get(`/api/dev/worlds/${projectId}/npcs`)

/**
 * NPC 全息详情。
 *
 * @param {number} projectId 世界 ID
 * @param {string} ref 角色引用（npc:{id}）
 * @param {number} [gameSecond] 游戏时刻（空=取引擎时钟）
 */
export const fetchDevNpcHologram = (projectId, ref, gameSecond) =>
  http.get(`/api/dev/worlds/${projectId}/npc-hologram`, { params: { ref, gameSecond } })

/* ==================== 2. /api/debug（既有只读调试面） ==================== */

/** 调试总览 */
export const fetchDebugOverview = () => http.get('/api/debug/overview')

/** 世界引擎快照（时钟 + 引擎配置 + 角色状态 + 近期事件 + 近期 AI 调用） */
export const fetchEngineSnapshot = (projectId) => http.get('/api/debug/engine/snapshot', { params: { projectId } })

/** AgentRuntime 概览（脑力档/组件级状态/预算使用） */
export const fetchAgentRuntimeOverview = (projectId) =>
  http.get('/api/debug/agent/runtime-overview', { params: { projectId } })

/** 日志缓冲分页 */
export const fetchLogBuffer = (params = {}) => http.get('/api/debug/logs/buffer', { params })

/** AI 调用列表（分页） */
export const fetchAiCalls = (params = {}) => http.get('/api/debug/ai-calls', { params })

/** 单条 AI 调用详情（含 detailJson） */
export const fetchAiCallDetail = (id) => http.get(`/api/debug/ai-calls/${id}`)

/** 决策链路列表 */
export const fetchAiTraces = (params = {}) => http.get('/api/debug/ai-calls/traces', { params })

/** 单条决策链路详情 */
export const fetchAiTraceDetail = (traceId) => http.get(`/api/debug/ai-calls/trace/${traceId}`)

/** 某角色最近 N 次决策链路 */
export const fetchCharacterTraces = (characterId, limit = 10) =>
  http.get(`/api/debug/ai-calls/character/${characterId}/traces`, { params: { limit } })

/** 漂移度量 */
export const fetchDriftMetrics = (params = {}) => http.get('/api/debug/drift-metrics', { params })

/** 用量聚合 */
export const fetchUsageStats = (days = 30) => http.get('/api/debug/usage/stats', { params: { days } })

/** DB 白名单表清单 */
export const fetchDbTables = () => http.get('/api/debug/db/tables')

/** DB 白名单表分页浏览 */
export const fetchDbTable = (table, params = {}) => http.get(`/api/debug/db/${table}`, { params })

/* ---- 数据域：系统库 / 存档库分开浏览（开发者页专用，按范围显式指定目标库） ---- */

/**
 * 可浏览的库范围：系统库（目录库）+ 每个存档（独立世界库）。
 *
 * @returns {Promise<{system: object, archives: Array}>} 范围清单
 */
export const fetchDevDbScopes = () => http.get('/api/dev/db/scopes')

/**
 * 指定范围的表清单（行数 / 列数）。
 *
 * @param {{scope?: string, worldId?: number}} params scope=system|world
 * @returns {Promise<Array>} 表信息列表
 */
export const fetchDevDbTables = (params = {}) => http.get('/api/dev/db/tables', { params })

/**
 * 指定范围的表只读分页浏览。
 *
 * @param {string} table 表名
 * @param {{scope?: string, worldId?: number, page?: number, size?: number, keyword?: string}} params 参数
 * @returns {Promise<object>} {table,page,size,total,columns,items}
 */
export const fetchDevDbTable = (table, params = {}) => http.get(`/api/dev/db/${table}`, { params })

/**
 * 某存档的目录结构（存档形态：世界库 + 清单 + 资源目录）。
 *
 * @param {number} worldId 存档 ID
 * @returns {Promise<object>} 目录结构
 */
export const fetchDevDbFiles = (worldId) => http.get('/api/dev/db/files', { params: { worldId } })

/** 配置脱敏查看（供应商 + Prompt 模板清单） */
export const fetchDebugConfig = () => http.get('/api/debug/config')

/** Prompt 指标规格 */
export const fetchPromptSpecs = () => http.get('/api/debug/prompt-metrics/specs')

/** Prompt 指标统计 */
export const fetchPromptStats = () => http.get('/api/debug/prompt-metrics/stats')

/** Prompt 指标告警 */
export const fetchPromptAlerts = () => http.get('/api/debug/prompt-metrics/alerts')

/** 护栏状态 */
export const fetchGuardRails = () => http.get('/api/debug/guard-rails/status')

/** 场景路由生效表 */
export const fetchSceneRouting = () => http.get('/api/debug/scene-routing/effective')

/** 系统 schema 迁移状态 */
export const fetchSystemSchema = () => http.get('/api/system/schema')

/* ==================== 3. 调试面之外的既有只读接口补封装 ==================== */

/** 刺激日志（最近 N 条；projectId 可空=全部） */
export const fetchStimulusLog = (params = {}) => http.get('/api/realtime-stimulus/log', { params })

/** 刺激/护栏命中统计 */
export const fetchStimulusStats = (projectId) => http.get('/api/realtime-stimulus/stats', { params: { projectId } })

/** 决策模型 判定层状态（逐场景开关） */
export const fetchDecisionStatus = () => http.get('/api/debug/decision/gate/status')

/** 决策模型 判定统计（分布/top1Prob/熵/采纳） */
export const fetchDecisionGateStats = (max = 50) => http.get('/api/debug/decision/gate/stats', { params: { max } })

/** 认知 LOD + 微决策（N5） */
export const fetchN5Cognition = () => http.get('/api/debug/growth/n5')

/** 成长模块概览 */
export const fetchGrowthOverview = () => http.get('/api/debug/growth/overview')

/** 记忆健康度 */
export const fetchMemoryHealth = (projectId, characterRef) =>
  http.get(`/api/worlds/${projectId}/memory-health`, { params: { characterRef } })

/** 记忆注入预览 */
export const fetchInjectionPreview = (projectId, params = {}) =>
  http.get(`/api/worlds/${projectId}/memory-injection/preview`, { params })

/** 记忆注入路由预览 */
export const fetchInjectRoute = (projectId, params = {}) =>
  http.get(`/api/worlds/${projectId}/memories/inject-route`, { params })

/** 项目记忆概览 */
export const fetchWorldMemoryOverview = (projectId) => http.get(`/api/worlds/${projectId}/world-memory/overview`)

/** 项目记忆编年史 */
export const fetchChronicle = (projectId, params = {}) =>
  http.get(`/api/worlds/${projectId}/world-memory/chronicle`, { params })

/** 项目记忆实体状态 */
export const fetchWorldEntities = (projectId, params = {}) =>
  http.get(`/api/worlds/${projectId}/world-memory/entities`, { params })

/** 决策协调 · 前置推演 */
export const fetchForesight = (projectId) => http.get(`/api/worlds/${projectId}/decision/foresight`)

/** 决策协调 · 冲突 */
export const fetchDecisionConflicts = (projectId) => http.get(`/api/worlds/${projectId}/decision/conflicts`)

/** 决策协调 · 资源占用 */
export const fetchDecisionOccupancy = (projectId) => http.get(`/api/worlds/${projectId}/decision/occupancy`)

/** 世界引擎状态（tick/预算/重试） */
export const fetchWorldEngine = (projectId) => http.get(`/api/worlds/${projectId}/world-engine`)

/** 世界引擎决策统计 */
export const fetchDecisionStats = (projectId) => http.get(`/api/worlds/${projectId}/world-engine/decision-stats`)

/** 行为评估批跑列表 */
export const fetchEvalRuns = (projectId) => http.get(`/api/worlds/${projectId}/test/eval-runs`)

/** 评估批跑详情 */
export const fetchEvalRunDetail = (projectId, evalRunId) =>
  http.get(`/api/worlds/${projectId}/test/eval-runs/${evalRunId}`)

/** 测试会话列表 */
export const fetchTestRuns = (projectId) => http.get(`/api/worlds/${projectId}/test/runs`)

/** Unity 对接状态 */
export const fetchUnityStatus = (projectId) => http.get(`/api/worlds/${projectId}/unity/status`)

/** NPC 状态调参 · 全量可调参数（既有注册表） */
export const fetchNpcStateConfig = () => http.get('/api/npc-state-config')

/** NPC 状态调参 · 保存单键 */
export const saveNpcStateConfig = (key, value) =>
  http.put(`/api/npc-state-config/${encodeURIComponent(key)}`, { value })

/** NPC 状态调参 · 单键重置 */
export const resetNpcStateConfig = (key) => http.delete(`/api/npc-state-config/${encodeURIComponent(key)}`)

/* ==================== 4. SSE 地址（页面自行用 EventSource 订阅） ==================== */

/** 实时日志流（补发最近 500 条 + 实时） */
export const debugLogStreamUrl = (params = {}) => {
  const q = new URLSearchParams()
  if (params.level) q.set('level', params.level)
  if (params.keyword) q.set('keyword', params.keyword)
  const s = q.toString()
  return `/api/debug/logs/stream${s ? `?${s}` : ''}`
}

/** 调试事件流（行动事件 + 世界事件） */
export const debugEventStreamUrl = () => '/api/debug/events/stream'

/** 世界事件流（按世界） */
export const worldEventStreamUrl = (projectId) => `/api/worlds/${projectId}/world-events/stream`
