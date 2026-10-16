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

// 对谈模式 API（M1 后端 /api/projects/{id}/dialogue/*，M2 前端单聊闭环接入）
// 语义：与「浮生」主模式同级的静止世界聊天（世界冻结于 frozen_game_second，零世界推进）。
// SSE 端点（chat/regenerate）为 POST 流式，走 streamFetch（EventSource 仅支持 GET）。
import http from './http'

// ==================== 会话 CRUD ====================
// 新建对谈会话（创建即冻结：运行中自动暂停 + 冻结时刻快照）→ DialogueSessionVO
export const createDialogueSession = (projectId, data) => http.post(`/api/projects/${projectId}/dialogue/sessions`, data)
// 项目对谈会话列表（按更新时间倒序；keyword 可空=全量，M6 支持按标题/角色名/地点名搜索）→ DialogueSessionVO[]
export const fetchDialogueSessions = (projectId, keyword) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`)
// 单会话 → DialogueSessionVO
export const fetchDialogueSession = (projectId, sessionId) => http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}`)
// 重命名会话 → DialogueSessionVO
export const renameDialogueSession = (projectId, sessionId, title) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/rename`, { title })
// 关闭会话（status=closed；历史保留可查阅）→ { closed }
export const closeDialogueSession = (projectId, sessionId) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/close`)

// ==================== 消息 ====================
// 会话消息列表（seq 正序）→ DialogueMessageVO[]
export const fetchDialogueMessages = (projectId, sessionId) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/messages`)
// 编辑玩家消息（作废其后所有消息）→ DialogueMessageVO
export const editDialogueMessage = (projectId, sessionId, messageId, content) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/messages/${messageId}/edit`, { content })
// 删除消息（含其后所有消息）→ { deleted }
export const deleteDialogueMessage = (projectId, sessionId, messageId) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/messages/${messageId}/delete`)
// 换一条（swipe：轮换到下一候选回复）→ DialogueMessageVO
export const swipeDialogueMessage = (projectId, sessionId, messageId) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/messages/${messageId}/swipe`)

// ==================== 开场白 / 流式对话 ====================
// AI 动态开场白（新会话首句；成功落库为 assistant 消息）→ { content }
export const generateDialogueGreeting = (projectId, sessionId) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/greeting`, null, { timeout: 60000 })

/**
 * 一轮对谈 SSE URL（POST + JSON body，EventSource 不可用 → streamFetch 消费）。
 * 事件契约：meta(sessionId/characterRef) → token(delta)* → done(messageId/content/characterRef/characterName/seq) | error(message)。
 */
export const dialogueChatUrl = (projectId, sessionId) => `/api/projects/${projectId}/dialogue/sessions/${sessionId}/chat`

/**
 * 重写一条角色回复 SSE URL（POST + JSON body；body.messageId 可空=取会话最后一条角色回复）。
 * 事件契约同 chat；新回复 metaJson.swipes 携带旧回复供「换一条」轮换。
 */
export const dialogueRegenerateUrl = (projectId, sessionId) => `/api/projects/${projectId}/dialogue/sessions/${sessionId}/regenerate`

// ==================== 世界书（M4：会话级关键词触发条目） ====================
// 会话世界书条目列表（含停用；按 priority 升序）→ DialogueWorldbookVO[]
export const fetchDialogueWorldbook = (projectId, sessionId) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/worldbook`)
// 新增/更新世界书条目（同会话同关键词唯一：已存在 → 更新）→ DialogueWorldbookVO
export const createDialogueWorldbook = (projectId, sessionId, data) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/worldbook`, data)
// 更新已存在条目（按条目 ID，归属校验）→ DialogueWorldbookVO
export const updateDialogueWorldbook = (projectId, sessionId, entryId, data) =>
  http.put(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/worldbook/${entryId}`, data)
// 删除世界书条目 → { deleted }
export const deleteDialogueWorldbook = (projectId, sessionId, entryId) =>
  http.delete(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/worldbook/${entryId}`)

// ==================== 上下文配置 / 用量（M4） ====================
// 上下文配置（作者注释 + 滚动窗口摘要水位 + 世界书条目数）→ DialogueContextConfigVO
export const fetchDialogueContextConfig = (projectId, sessionId) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/context-config`)
// 更新作者注释配置（authorNote/authorNoteDepth/authorNoteFrequency；空串=清除）→ DialogueContextConfigVO
export const saveDialogueAuthorNote = (projectId, sessionId, data) =>
  http.put(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/context-config`, data)
// 上下文用量估算（消息/段字符 + 估算 token）→ DialogueUsageVO
export const fetchDialogueUsage = (projectId, sessionId) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/usage`)

// ==================== 群聊（M5：场景化成员 / 换场 / 拉人） ====================
// 群聊成员列表（present 在场在前、manual 拉人在后）→ DialogueGroupMemberVO[]
export const fetchDialogueGroupMembers = (projectId, sessionId) =>
  http.get(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/group/members`)
// 拉人（临时加入，manual 标记；换场保留）→ DialogueGroupMemberVO[]
export const inviteDialogueGroupMember = (projectId, sessionId, characterRef) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/group/members`, { characterRef })
// 移除成员（在场或拉人均可）→ { removed }
export const removeDialogueGroupMember = (projectId, sessionId, characterRef) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/group/members/remove`, { characterRef })
// 换场（切地点重算在场成员；manual 拉人保留）→ { session, members }
export const changeDialogueGroupLocation = (projectId, sessionId, locationId) =>
  http.put(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/group/location`, { locationId })

/**
 * 一轮群聊 SSE URL（POST + JSON body，streamFetch 消费）。
 * 事件契约：meta(type=group/total) → 每成员 group-member(index/characterRef/characterName) →
 * token(delta/characterRef)* → group-member-done(messageId/content/characterRef/characterName/seq)
 * ｜ error(memberRef/message) → done(replied/skipped/failed/aborted)。
 */
export const dialogueGroupChatUrl = (projectId, sessionId) => `/api/projects/${projectId}/dialogue/sessions/${sessionId}/group/chat`

// ==================== 沉淀（M6：确认卡片 preview + 应用 apply） ====================
// 沉淀确认卡片：AI 抽取每位对谈角色的关系 delta + NPC 记忆要点（只读不落库，供确认展示）→ DialogueConsolidationVO
export const fetchDialogueConsolidationPreview = (projectId, sessionId) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/consolidate/preview`, null, { timeout: 60000 })
// 应用沉淀（用户确认后；候选由卡片原样回传）→ DialogueConsolidationResultVO
export const applyDialogueConsolidation = (projectId, sessionId, characters) =>
  http.post(`/api/projects/${projectId}/dialogue/sessions/${sessionId}/consolidate/apply`, { characters })
