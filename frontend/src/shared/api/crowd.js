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

// 普通型 NPC 功能域 API（分类体系重构后：单表 CRUD / 标准字段字典 / AI 生成 / 调度 / 环境摘要 / 关系）
import http from './http'
// 关系生成/入库复用角色功能域（scope=crowd 单普通 NPC / scope=project 全部；关系写 actor_character_relation）
import { generateCharacterRelations, batchSaveCharacterRelations } from './character'
// ：SSE 流统一客户端（fetch + ReadableStream 解析/错误分类统一）
import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'

// ===== 普通型 NPC CRUD / 筛选 / 排序 / 分页 =====
/**
 * 分页查询（全部字段可筛可排序；params 支持）：
 * gender/race/subRace/affiliation/occupation/location（精确）、keyword（模糊）、
 * ageMin/ageMax（年龄区间）、sortBy（id/name/age/race/subRace/affiliation/occupation/location）、
 * sortDir（asc/desc）、page、size。
 */
export const fetchOrdinaryNpcs = (projectId, params) => http.get(`/api/worlds/${projectId}/ordinary-npcs`, { params })
// 手动新增单个
export const createOrdinaryNpc = (projectId, data) => http.post(`/api/worlds/${projectId}/ordinary-npcs`, data)
// 单个
export const updateOrdinaryNpc = (id, data) => http.put(`/api/ordinary-npcs/${id}`, data)
// 删除单个
export const deleteOrdinaryNpc = (id) => http.delete(`/api/ordinary-npcs/${id}`)
// 批量删除（body: { ids: [...] }）
export const batchDeleteOrdinaryNpcs = (projectId, ids) => http.post(`/api/worlds/${projectId}/ordinary-npcs/batch-delete`, { ids })
//
export const fetchOrdinaryNpcStats = (projectId) => http.get(`/api/worlds/${projectId}/ordinary-npcs/stats`)

// ===== 标准字段数据（字段字典：race 种族[含次级种族] / affiliation 归属 / occupation 职业，含出处） =====
// 查询字段字典：{ race: [{field,level1,level2,source}], affiliation: [...], occupation: [...] }
export const fetchFieldDict = (projectId) => http.get(`/api/worlds/${projectId}/ordinary-npcs/field-dict`)
// AI 依据世界观一次性拟定全部字段字典 + 选出主/次分类字段（不落库，供预览确认；AI 长耗时接口勿设短超时）
export const generateFieldDict = (projectId) => http.post(`/api/worlds/${projectId}/ordinary-npcs/field-dict/generate`, null, { timeout: 180000 })
// 保存字段字典（预览确认后整体替换）+ 保存主/次分类字段（body: { primaryField, secondaryField, fields }）
export const saveFieldDict = (projectId, preview) => http.post(`/api/worlds/${projectId}/ordinary-npcs/field-dict`, preview, { timeout: 60000 })
// 手动新增一条字段字典（body: { field, level1, level2, source }）
export const addFieldDictEntry = (projectId, entry) => http.post(`/api/worlds/${projectId}/ordinary-npcs/field-dict/entry`, entry)
// 手动删除一条字段字典（race 二级删除需传 level2；其余字段 level2 传空）
export const deleteFieldDictEntry = (projectId, field, l1, l2) =>
  http.delete(`/api/worlds/${projectId}/ordinary-npcs/field-dict/${encodeURIComponent(field)}/${encodeURIComponent(l1)}/${l2 ? encodeURIComponent(l2) : 'undefined'}`)

// ===== AI 生成 / 入库 =====
/**
 * AI 分批生成普通型 NPC（：统一 streamFetch + ReadableStream 流式消费；无超时）。
 * 事件：start(count,batchSize) / npc(draft) / done(total,generated,failedBatches) / error(message)。
 * 生成结果仅作预览，确认后走 batchSaveOrdinaryNpcs 入库；字段从字段字典/地点表选取，不含关系。
 *
 * @param projectId 项目 ID
 *
 * @param count 生成数量（1~500）
 *
 * @param handlers 事件回调：{ onStart, onNpc, onDone, onError }
 */
export const generateOrdinaryNpcsStream = async (projectId, count, handlers = {}) => {
  const { promise } = streamFetch({
    url: `/api/worlds/${projectId}/ordinary-npcs/generate/stream`,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ count }),
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.START) handlers.onStart?.(data)
      else if (name === SSE_EVENTS.NPC) handlers.onNpc?.(data)
      else if (name === SSE_EVENTS.DONE) handlers.onDone?.(data || {})
    },
    onError: (err) => {
      const msg = (err && err.kind === 'event' && err.data && err.data.message)
        ? err.data.message : (err && err.message) || 'AI 生成失败'
      handlers.onError?.(msg)
    }
  })
  await promise
}

// 预览确认后批量入库（body: [draft...]）
export const batchSaveOrdinaryNpcs = (projectId, items) => http.post(`/api/worlds/${projectId}/ordinary-npcs/batch-save`, items)

// ===== 关系（普通 NPC 关系写 actor_character_relation，复用角色关系拓扑结构与流程） =====
/**
 * 为单个普通 NPC 生成关系预览（AI，不落库）→ 预览确认后 batchSaveCrowdRelations 入库。
 * 复用 shared/api/character.js 的 generateCharacterRelations（scope=crowd + crowdId）。
 */
export const generateCrowdRelations = (projectId, crowdId, mode = 'supplement') =>
  generateCharacterRelations(projectId, { scope: 'crowd', crowdId, mode })
/**
 * 为全部普通 NPC（全项目范围）生成关系预览（AI，不落库）→ 预览确认后入库。
 * scope=project 覆盖全项目关系拓扑（特殊 NPC + 普通 NPC + 幽灵），复用全局拓扑页同一路径。
 */
export const generateAllCrowdRelations = (projectId, mode = 'supplement') =>
  generateCharacterRelations(projectId, { scope: 'project', mode })
/**
 * 普通 NPC 关系批量入库（scope=crowd 按 crowdId 重建相关范围 / supplement 仅追加）。
 */
export const batchSaveCrowdRelations = (projectId, crowdId, mode, items) =>
  batchSaveCharacterRelations(projectId, { mode, crowdId, items })

// ===== 调度 / 环境 =====
// 程序化状态机调度（零 AI）
export const scheduleOrdinaryNpcs = (projectId) => http.post(`/api/worlds/${projectId}/ordinary-npcs/schedule`)
// 两级 AI 集体调度（项目级→归属级，多次 AI 调用，勿设短超时）
export const scheduleOrdinaryNpcsAi = (projectId) => http.post(`/api/worlds/${projectId}/ordinary-npcs/schedule-ai`, null, { timeout: 600000 })
// 环境摘要（对话背景板）
export const fetchOrdinaryNpcEnvSummary = (projectId) => http.get(`/api/worlds/${projectId}/ordinary-npcs/env-summary`)
// 项目级调度运行时信息（enabled/primaryField/secondaryField/lastScheduleAt/latestSummary）
export const fetchOrdinaryNpcRuntime = (projectId) => http.get(`/api/worlds/${projectId}/ordinary-npcs/runtime`)
// 项目级定时调度开关
export const setOrdinaryNpcScheduleEnabled = (projectId, enabled) => http.put(`/api/worlds/${projectId}/ordinary-npcs/schedule-enabled`, { enabled })
