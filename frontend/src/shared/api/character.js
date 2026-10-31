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

// 角色功能域 API：NPC 角色 / 角色档案 / 行动
import http from './http'

// ==================== 角色/角色档案 ====================
// 说明：接口路径保留历史命名（card），语义已是"角色档案"——返回 CharacterCardVO
// （personaJson=11 维档案 JSON 原文、personaRenderText=服务端渲染好的全量 11 维中文文本、source/version/updatedAt）。
// systemPrompt 字段已随 删除（改为运行时按场景渲染分片），前端不得再读取。
export const fetchCharacters = (projectId) => http.get(`/api/worlds/${projectId}/characters`)
export const createCharacter = (projectId, data) => http.post(`/api/worlds/${projectId}/characters`, data)
export const updateCharacter = (id, data) => http.put(`/api/characters/${id}`, data)
export const deleteCharacter = (id) => http.delete(`/api/characters/${id}`)
export const generateCharacterCard = (characterId) => http.post(`/api/characters/${characterId}/generate-card`, null, { timeout: 60000 })
export const fetchCharacterCard = (characterId) => http.get(`/api/characters/${characterId}/card`)
export const fetchCharacterCardVersions = (characterId) => http.get(`/api/characters/${characterId}/card/versions`)
export const editCharacterCard = (characterId, data) => http.put(`/api/characters/${characterId}/card`, data)
// ：AI 补全缺失维（只填真空维，已覆盖维保留；无缺失零 AI 调用）
export const completeCharacterCard = (characterId) => http.post(`/api/characters/${characterId}/card/complete`, null, { timeout: 180000 })
// ：回滚到指定版本（保存为新版本 source=restored；版本号继续自增）
export const restoreCharacterCard = (characterId, version) => http.post(`/api/characters/${characterId}/card/restore`, { version }, { timeout: 60000 })

// ==================== 角色关系拓扑图 ====================
//
//
// 说明：普通人群重构后 crowd 节点 = 每个普通型 NPC 以具体人名独立成节点，crowdName 存其归属。
export const fetchCharacterRelations = (projectId) => http.get(`/api/worlds/${projectId}/character-relations`)
// AI 生成关系预览（scope=character|project，不落库）→ [{from,to,relationType,description}]
export const generateCharacterRelations = (projectId, data) => http.post(`/api/worlds/${projectId}/character-relations/generate`, data, { timeout: 120000 })
// 关系批量入库（预览确认后写入）→ {added, total}
export const batchSaveCharacterRelations = (projectId, data) => http.post(`/api/worlds/${projectId}/character-relations/batch`, data)

// ==================== 角色关系手动 CRUD + AI 精修（，用户） ====================
// 端点支持 characterRef（统一身份引用 npc:{id}，单主表后不再分 special/crowd，修 幽灵歧义）与名称兜底：
//
export const createCharacterRelation = (projectId, data) => http.post(`/api/worlds/${projectId}/character-relations`, data)
// 编辑 → PUT /api/character-relations/{id}（空字段保留原值）
export const updateCharacterRelation = (id, data) => http.put(`/api/character-relations/${id}`, data)
//
export const deleteCharacterRelation = (id) => http.delete(`/api/character-relations/${id}`)
// AI 精修 → POST /api/worlds/{projectId}/character-relations/{id}/ai-refine（好感/信任/状态交 AI 综合判断）
export const aiRefineCharacterRelation = (projectId, id) => http.post(`/api/worlds/${projectId}/character-relations/${id}/ai-refine`, null, { timeout: 60000 })

// ==================== 行动 ====================
export const fetchActions = (characterId) => http.get(`/api/characters/${characterId}/actions`)
export const triggerAction = (characterId, data) => http.post(`/api/characters/${characterId}/actions/trigger`, data)
export const fetchActionTimeline = (characterId) => http.get(`/api/characters/${characterId}/actions/timeline`)
// 项目级行动时间线（聚合决策/世界事件/执行日志 + 角色/状态/触发源/时间范围筛选）
export const fetchProjectActionTimeline = (projectId, params) => http.get(`/api/worlds/${projectId}/actions/timeline`, { params })
// 标记行动计划状态（done/cancelled/planned）
export const updateActionStatus = (planId, status) => http.put(`/api/actions/${planId}/status`, { status })
// SSE 行动事件流 URL（时间线实时刷新）
export const actionStreamUrl = () => `/api/actions/stream`

// ==================== 长期记忆（角色级抽取） ====================
// 手动触发角色记忆抽取（调试/补抽，返回本次新增条数）
export const extractCharacterMemories = (characterId) => http.post(`/api/characters/${characterId}/memories/extract`)

// ==================== 目标/计划（，agent/thinking 规划组件） ====================
// 目标列表（全部状态，新在前）→ ActorCharacterGoal[]
export const fetchGoals = (projectId, characterId) => http.get(`/api/worlds/${projectId}/characters/${characterId}/goals`)
// 手动创建目标 → {title, description?, priority?}
export const createGoal = (projectId, characterId, data) => http.post(`/api/worlds/${projectId}/characters/${characterId}/goals`, data)
// AI 生成目标（goal_gen，无 active 目标时生成，失败静默）→ 新建目标数
export const generateGoals = (projectId, characterId) => http.post(`/api/worlds/${projectId}/characters/${characterId}/goals/generate`, null, { timeout: 60000 })
// 目标（状态/标题/优先级；空字段保持原值）→ {status?, title?, priority?}
export const updateGoal = (goalId, data) => http.put(`/api/goals/${goalId}`, data)
// 删除目标（软删）
export const deleteGoal = (goalId) => http.delete(`/api/goals/${goalId}`)
// 角色「目标/计划」聚合（角色页 Tab 数据源）→ {goals, goalsSummary, planSummary, planToday}
export const fetchGoalPlan = (projectId, characterId) => http.get(`/api/worlds/${projectId}/characters/${characterId}/goal-plan`)

// ==================== 全生命周期阶段机（调试观测数据源，只读） ====================
//
//
// 概览 Tab「发育阶段」区块数据源；旧 jar 无端点时 catch 静默（前端显示 —）
export const fetchGrowthOverview = (projectId) => http.get('/api/debug/growth/overview', { params: { projectId } })

// ==================== 人格演化 + 社会面具（调试观测数据源，只读） ====================
//
// oldValue,newValue,delta,stageCap,source,aiReason,evidenceJson,createdAt}] }（含证据链，验收② 反查）
export const fetchGrowthShiftLogs = (projectId, npcRef) =>
  http.get('/api/debug/growth/shift-log', { params: { projectId, npcRef } })
//
//
// （真实/表达 PAD 分离，验收⑤ 观测）
export const fetchGrowthMaskStatus = (projectId, npcRef) =>
  http.get('/api/debug/growth/mask', { params: { projectId, npcRef } })
