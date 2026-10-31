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

// 新建项目「工作流」API：文件解析工作流 / 世界初始化工作流（均为 SSE 流式，前端控制台实时显示后端日志）
// 事件约定：log={level,message,time}（一行后端日志）、stage={name,index,total}（阶段进度）、
// result=工作流结果 VO（parse→WorldParseResultVO / init→WorldInitResultVO）、error={message}
// ：统一走 shared/sse/sseClient.streamFetch（fetch + ReadableStream 解析/错误分类统一）。

import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'

/**
 * 文件解析工作流（SSE）：分段 →（可选扩写）→ 建项目落世界观表 → 知识库存储 → 角色分离入库。
 * 扩写由用户决定：expand=false（默认）分段后原样入库；expand=true 自动 AI 扩写不足 1500 字的分段。
 *
 * @param files 选中的原始 File 列表（txt/md）
 *
 * @param expand true=自动扩写不足 1500 字的分段 / false=不扩写（默认）
 *
 * @param handlers 事件回调：{ onLog(line), onStage(stage), onResult(result) }
 *
 * @return Promise<result>（result 事件载荷；遇 error 事件抛 Error）
 */
export const parseWorkflowStream = async (files, expand, handlers = {}) => {
  if (typeof expand === 'object' && expand !== null) {
    // 兼容旧调用（第二个参数直接传 handlers）
    handlers = expand
    expand = false
  }
  const fd = new FormData()
  files.forEach((f) => fd.append('files', f))
  fd.append('expand', expand ? 'true' : 'false')
  let lastErr = null
  const { promise } = streamFetch({
    url: '/api/worlds/import/workflow',
    method: 'POST',
    body: fd,
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.LOG) handlers.onLog?.(data)
      else if (name === SSE_EVENTS.STAGE) handlers.onStage?.(data)
      else if (name === SSE_EVENTS.RESULT) handlers.onResult?.(data)
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  if (!result) throw new Error('解析未返回结果')
  return result
}

/**
 * 世界初始化工作流（SSE）：地点 → 角色卡 → 字段字典+普通NPC → 关系拓扑 → 世界时间 → 知识向量化。
 *
 * @param projectId 项目 ID
 *
 * @param rebuild true=全量重建 / false=跳过已生成（幂等）
 *
 * @param handlers 事件回调：{ onLog(line), onStage(stage), onResult(result) }
 *
 * @return Promise<result>（result 事件载荷；遇 error 事件抛 Error）
 */
export const initWorkflowStream = async (projectId, rebuild, handlers = {}) => {
  let lastErr = null
  const { promise } = streamFetch({
    url: `/api/worlds/${projectId}/init/stream`,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rebuild: !!rebuild }),
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.LOG) handlers.onLog?.(data)
      else if (name === SSE_EVENTS.STAGE) handlers.onStage?.(data)
      else if (name === SSE_EVENTS.RESULT) handlers.onResult?.(data)
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  if (!result) throw new Error('世界初始化未返回结果')
  return result
}
