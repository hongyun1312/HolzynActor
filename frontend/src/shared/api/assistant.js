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

// 项目级 AI 助手 API（会话 CRUD / 消息 / SSE 流式对话 / 工具与 docs 索引调试）
// SSE 流式走 shared/sse/sseClient.streamFetch（fetch + ReadableStream，事件：meta/tool/token/done/error）。
import http from './http'
import { streamFetch } from '@/shared/sse/sseClient'

/** 会话接口前缀 */
const base = (projectId) => `/api/assistant/worlds/${projectId}`

/**
 * 项目会话列表（按更新时间倒序）。
 * @param {number|string} projectId 项目 ID
 */
export const listAssistantSessions = (projectId) => http.get(`${base(projectId)}/sessions`)

/**
 * 新建会话。
 * @param {number|string} projectId 项目 ID
 * @param {object} [body] { title?, defaultMode? }
 */
export const createAssistantSession = (projectId, body = {}) => http.post(`${base(projectId)}/sessions`, body)

/** 单会话。 */
export const getAssistantSession = (projectId, sessionId) => http.get(`${base(projectId)}/sessions/${sessionId}`)

/** 删除会话（级联消息）。 */
export const deleteAssistantSession = (projectId, sessionId) => http.delete(`${base(projectId)}/sessions/${sessionId}`)

/** 更新会话默认回答模式（narrative/fact）。 */
export const updateAssistantSessionMode = (projectId, sessionId, mode) =>
  http.post(`${base(projectId)}/sessions/${sessionId}/mode`, { mode })

/** 更新会话干预确认粒度（strict 严格 / standard 标准 / trust 信任）。 */
export const updateAssistantSessionConfirmMode = (projectId, sessionId, mode) =>
  http.post(`${base(projectId)}/sessions/${sessionId}/confirm-mode`, { mode })

/** 确认并执行待确认干预计划。 */
export const confirmAssistantIntervention = (projectId, sessionId, planId) =>
  http.post(`${base(projectId)}/sessions/${sessionId}/interventions/${planId}/confirm`, {})

/** 取消待确认干预计划。 */
export const cancelAssistantIntervention = (projectId, sessionId, planId) =>
  http.post(`${base(projectId)}/sessions/${sessionId}/interventions/${planId}/cancel`, {})

/** 修改后执行（M5）：修改待确认计划参数后直接执行。 */
export const reviseAssistantIntervention = (projectId, sessionId, planId, params) =>
  http.post(`${base(projectId)}/sessions/${sessionId}/interventions/${planId}/revise`, { params })

/** 会话消息列表（时间正序）。 */
export const listAssistantMessages = (projectId, sessionId) => http.get(`${base(projectId)}/sessions/${sessionId}/messages`)

/**
 * 一轮对话（SSE 流式）。
 * @param {number|string} projectId 项目 ID
 * @param {number|string} sessionId 会话 ID
 * @param {string} question 用户问题
 * @param {string} [mode] 逐轮模式覆盖（narrative/fact）
 * @param {object} handlers { onMeta, onTool, onToken, onDone, onError }
 * @returns {{ cancel: () => void }}
 */
export function chatAssistant(projectId, sessionId, question, mode, handlers = {}) {
  const { onMeta, onTool, onToken, onDone, onError } = handlers
  return streamFetch({
    url: `${base(projectId)}/sessions/${sessionId}/chat`,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, mode: mode || undefined }),
    onEvent: (name, data) => {
      if (name === 'meta') onMeta && onMeta(data)
      else if (name === 'tool') onTool && onTool(data)
      else if (name === 'token') onToken && onToken(data)
      else if (name === 'done') onDone && onDone(data)
    },
    onError: (err) => onError && onError(err)
    // 注意：不注册 streamFetch 的 onDone——终止事件(done)已由 onEvent 分发，
    // 若再注册 onDone 会在流结束后重复回调（M5 收尾抓修：onDone 双触发）。
  })
}

/** 工具清单（调试）。 */
export const listAssistantTools = () => http.get('/api/assistant/tools')

/** docs 索引状态。 */
export const getAssistantDocsIndexStatus = () => http.get('/api/assistant/docs-index/status')

/** docs 索引手动重建。 */
export const rebuildAssistantDocsIndex = (projectId) => http.post('/api/assistant/docs-index/rebuild', { projectId })
