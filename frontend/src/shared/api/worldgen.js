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

// 工作流世界观生成 API：创意发散生成任务 / 节点操作 / 6 维预览确认 / SSE 进度
// 后端 WorldGenController（/api/worldgen）：
// GET /workflows：可用工作流定义列表（build_v1 等）
// POST /tasks：创建并异步执行任务 {projectId, workflowCode, inputs} → {taskId}
// GET /tasks/{taskId}：任务状态/节点进度/Token 汇总（轮询刷新节点状态）
// GET /tasks/{taskId}/nodes/{nodeId}：节点产物详情（输入/输出/尝试历史）
// POST /tasks/{taskId}/nodes/{nodeId}/retry|skip：失败节点原地续跑（CAS expectedAttemptCount）
// GET /tasks/{taskId}/preview：6 维产物 → 分节/地点映射预览（不写库）
// POST /tasks/{taskId}/confirm：预览确认后落库（分节 + 地点表，幂等防覆盖）
// GET /tasks/{taskId}/stream：SSE 进度流（log/stage/result/error，含回放）
// ：统一走 shared/sse/sseClient（streamFetch GET 流型，对齐）。

import http from './http'
import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'

// ==================== 工作流定义 ====================
// 可用工作流模板列表（WorldGenWorkflowVO[]：id/code/name/version/nodeCount/enabled）
export const fetchWorldgenWorkflows = () => http.get('/api/worldgen/workflows')

// ==================== 任务 ====================
// 创建并异步执行世界观生成任务 → {taskId}
export const createWorldgenTask = (data) => http.post('/api/worldgen/tasks', data)
// 任务详情（节点进度 + 尝试历史 + Token 汇总；retry/skip 也返回任务 VO）
export const fetchWorldgenTask = (taskId) => http.get(`/api/worldgen/tasks/${taskId}`)
// 节点详情（输入/输出/尝试历史）
export const fetchWorldgenNode = (taskId, nodeId) => http.get(`/api/worldgen/tasks/${taskId}/nodes/${nodeId}`)

// ==================== 节点操作（失败节点原地续跑，CAS） ====================
// 重试本节点（expectedAttemptCount = 客户端看到的该节点当前尝试数，防并发双击）
export const retryWorldgenNode = (taskId, nodeId, expectedAttemptCount) =>
  http.post(`/api/worldgen/tasks/${taskId}/nodes/${nodeId}/retry`, { expectedAttemptCount })
// 跳过本节点（标记 skipped 后从下一节点继续）
export const skipWorldgenNode = (taskId, nodeId, expectedAttemptCount) =>
  http.post(`/api/worldgen/tasks/${taskId}/nodes/${nodeId}/skip`, { expectedAttemptCount })

// ==================== 6 维产物预览 / 确认落库 ====================
// 预览（不写库）：WorldGenPreviewVO{ taskId, projectId, workflowCode, dimensions[], sections{}, locations[] }
export const fetchWorldgenPreview = (taskId) => http.get(`/api/worldgen/tasks/${taskId}/preview`)
// 确认落库（幂等防覆盖）：{ taskId, projectId, sections{}, locationAdded, locationCandidates }
export const confirmWorldgenTask = (taskId) => http.post(`/api/worldgen/tasks/${taskId}/confirm`)

// ==================== SSE 进度流（生成型，GET） ====================
// 事件约定：log={level,message,time}（控制台行）、stage={name,index,total}（阶段进度）、
// result=任务结果（{taskId,status,nodeCount}）、error={message}（任务失败）。
// 返回 Promise<result>；error 事件抛 Error。断线不重连（生成型语义：防重复执行）。
export const worldgenStream = async (taskId, handlers = {}) => {
  let lastErr = null
  const { promise } = streamFetch({
    url: `/api/worldgen/tasks/${taskId}/stream`,
    method: 'GET',
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.LOG) handlers.onLog?.(data)
      else if (name === SSE_EVENTS.STAGE) handlers.onStage?.(data)
      else if (name === SSE_EVENTS.RESULT) handlers.onResult?.(data)
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  return result
}

// ==================== 完整项目流水线 SSE ====================
// key=build 任务 ID；后端 WorldGenPipelineService 编排「角色生成 → 角色落库 → 故事规划 →
// 世界初始化 6 步 → 世界诞生编年史」，事件约定与任务 SSE 一致（log/stage/result/error）。
// result={status:'succeeded', projectId, characterTaskId}；断线不重连（生成型语义）。
export const worldgenPipelineStream = async (buildTaskId, handlers = {}) => {
  let lastErr = null
  const { promise } = streamFetch({
    url: `/api/worldgen/pipelines/${buildTaskId}/stream`,
    method: 'GET',
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.LOG) handlers.onLog?.(data)
      else if (name === SSE_EVENTS.STAGE) handlers.onStage?.(data)
      else if (name === SSE_EVENTS.RESULT) handlers.onResult?.(data)
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  return result
}

// ==================== 故事规划档案 ====================
// 项目故事规划列表（版本链升序）：每版含 storyPlanJson/styleProfileJson JSON 原文 +
// storyPlanMd/styleProfileMd Markdown 渲染 + atmosphereSegment 世界氛围片段 + createdAt。
// 供世界详情页「故事规划展示区」（含历史版本切换）与「生成故事规划」按钮完成态刷新。
export const fetchStoryPlans = (projectId) => http.get(`/api/worldgen/worlds/${projectId}/story-plans`)
