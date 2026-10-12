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

// 项目功能域 API：项目 CRUD / 世界观设定 / 角色卡生成 / .holzyn 导入导出
import http from './http'
// ：SSE 流统一客户端（fetch + ReadableStream 解析/错误分类统一）
import { streamFetch, SSE_EVENTS } from '@/shared/sse/sseClient'

// ==================== 项目/世界观 ====================
// 项目列表（分页参数 page/size 可选；画廊一次取 size=100 本地排序过滤）
export const fetchProjects = (params) => http.get('/api/projects', { params })
export const createProject = (data) => http.post('/api/projects', data)
export const fetchProject = (id) => http.get(`/api/projects/${id}`)
export const updateProject = (id, data) => http.put(`/api/projects/${id}`, data)
export const deleteProject = (id) => http.delete(`/api/projects/${id}`)
export const fetchWorldSetting = (projectId) => http.get(`/api/projects/${projectId}/world-setting`)
export const saveWorldSetting = (projectId, data) => http.post(`/api/projects/${projectId}/world-setting`, data)
export const generateCards = (projectId) => http.post(`/api/projects/${projectId}/generate-cards`, null, { timeout: 120000 })

// ==================== 文件导入建项目（上传解析/预览确认/AI补角色） ====================
// 上传文件解析（multipart，多文件 txt/md；不手动设 Content-Type 让 axios 自动带 boundary）
export const importParse = (files) => {
  const fd = new FormData()
  files.forEach((f) => fd.append(`files`, f))
  return http.post(`/api/projects/import/parse`, fd, { timeout: 0 }) // timeout 0 = 不限时
}
// SSE 流式解析（：统一 streamFetch；逐阶段推送 progress（done/total/label/chars），完成后 result，失败 error）
export const importParseStream = async (files, onProgress) => {
  const fd = new FormData()
  files.forEach((f) => fd.append(`files`, f))
  let lastErr = null
  const { promise } = streamFetch({
    url: `/api/projects/import/parse/stream`,
    method: 'POST',
    body: fd,
    onEvent: (name, data) => {
      if (name === SSE_EVENTS.PROGRESS) {
        try { onProgress && onProgress(data) } catch (_) { /* 忽略单条进度回调异常 */ }
      }
    },
    onError: (err) => { lastErr = err }
  })
  const result = await promise
  if (lastErr) throw lastErr
  if (!result) throw new Error('解析未返回结果')
  return result
}
// AI 自动生成符合世界观的角色档案
export const importGenerateCharacters = (data) => http.post(`/api/projects/import/characters/generate`, data, { timeout: 0 })
// 确认创建项目 + 世界观 + 角色
export const importConfirm = (data) => http.post(`/api/projects/import/confirm`, data, { timeout: 60000 })

// ==================== .holzyn 项目包导入/导出 ====================
// 导出 .holzyn 包（返回 blob；includeSensitive=是否含敏感数据；password=可选密码加密）
export const exportProjectPackage = (projectId, includeSensitive, password) => {
  const q = new URLSearchParams()
  if (includeSensitive) q.append('includeSensitive', 'true')
  if (password) q.append('password', password)
  return http.post(`/api/projects/${projectId}/export?${q.toString()}`, null, { responseType: 'blob', timeout: 60000 })
}
// 导入 .holzyn 包（multipart：file + password?）
export const importProjectPackage = (file, password) => {
  const fd = new FormData()
  fd.append('file', file)
  if (password) fd.append('password', password)
  return http.post('/api/projects/import/holzyn', fd, { timeout: 120000 })
}

// ==================== 世界引擎配置（运行模式三档 minimal/standard/ultimate） ====================
// 读取世界引擎配置 → {projectId, runMode, runModeLabel, budgetPerTick, maxStepHours}
export const fetchWorldEngineConfig = (projectId) => http.get(`/api/projects/${projectId}/world-engine`)
// 世界引擎配置（目前支持 run_mode）→ {"runMode":"minimal|standard|ultimate"}
export const updateWorldEngineConfig = (projectId, data) => http.put(`/api/projects/${projectId}/world-engine`, data)
