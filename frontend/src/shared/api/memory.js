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

// 记忆功能域 API：长期记忆（角色级/项目级）+ L1 工作记忆 + L3 核心记忆 + 归档
import http from './http'

// 记忆列表（characterId 可空：空=项目全部记忆；非空=该角色记忆；page/size 分页）
export const fetchMemories = (projectId, params) => http.get(`/api/projects/${projectId}/memories`, { params })
// 删除单条记忆（软删）
export const deleteMemory = (id) => http.delete(`/api/memories/${id}`)
// L2 记忆更新（版本链：新条 version+1 + supersedes_id=旧条，旧条软删+归档 superseded；
// 入参 {projectId 必填, content 必填, importance?}，返回新版本 MemoryVO）
export const updateMemory = (memoryId, data) => http.put(`/api/memories/${memoryId}`, data)
// L1 工作记忆列表（：draft 待复核草稿 / task 进行中任务 / context 上下文 / perception 感知快照；
// characterId/characterRef 可空：空=项目级工作项；status 可空：active/consolidated/expired）
export const fetchWorkingMemories = (projectId, params) => http.get(`/api/projects/${projectId}/working-memories`, { params })
// L3 核心记忆详情
export const fetchCoreMemories = (projectId, params) => http.get(`/api/projects/${projectId}/core-memories`, { params })
// 新建核心块
export const createCoreBlock = (projectId, data) => http.post(`/api/projects/${projectId}/core-memories`, data)
// 核心块
export const updateCoreBlock = (projectId, blockId, data) => http.put(`/api/projects/${projectId}/core-memories/${blockId}`, data)
// 删除核心块
export const deleteCoreBlock = (projectId, blockId) => http.delete(`/api/projects/${projectId}/core-memories/${blockId}`)

// ===== 归档（冷存储：触发/版本链/还原/检索/导出） =====
// 归档列表（characterRef/type/reason/keyword 可空过滤；page/size 分页）
export const fetchArchives = (projectId, params) => http.get(`/api/projects/${projectId}/archives`, { params })
// 批量手动归档
export const manualArchive = (projectId, data) => http.post(`/api/projects/${projectId}/archives/manual`, data)
// 还原归档条目
export const restoreArchive = (archiveId) => http.post(`/api/archives/${archiveId}/restore`)
// 手动触发超龄归档扫描
export const scanAgeArchive = (projectId) => http.post(`/api/projects/${projectId}/archives/scan-age`)
// 归档导出下载
export const exportArchives = (projectId, params) => http.get(`/api/projects/${projectId}/archives/export`, { params, responseType: 'blob' })
// 归档条目软删
export const deleteArchive = (archiveId) => http.delete(`/api/archives/${archiveId}`)

// ===== 检索注入与巩固收尾（分场景注入预览 / 记忆治理健康度 / 巩固编排） =====
// 分场景注入预览
export const fetchInjectionPreview = (projectId, params) => http.get(`/api/projects/${projectId}/memory-injection/preview`, { params })
// 记忆治理健康度
export const fetchMemoryHealth = (projectId, params) => http.get(`/api/projects/${projectId}/memory-health`, { params })
// 手动触发统一巩固编排
export const consolidateMemory = (data) => http.post('/api/memory/consolidate', data)

// ===== 记忆内核扩展（L1 动态模糊 + 跨层级巩固） =====
// L1 模糊状态列表（characterRef 可空=项目全部；status 可空=全部 active/distilled/archived）
export const fetchFuzzStates = (projectId, params) => http.get(`/api/projects/${projectId}/memories/fuzz`, { params })
// 单条模糊详情（fuzz_history 展开：原始 → 当前 → 模糊历史）
export const fetchFuzzDetail = (projectId, workingId) => http.get(`/api/projects/${projectId}/memories/fuzz/${workingId}`)
// 手动触发单条模糊（无视艾宾浩斯间隔立即执行一次；{memoryWorkingId}）
export const triggerFuzz = (projectId, data) => http.post(`/api/projects/${projectId}/memories/fuzz/trigger`, data)
// 记忆谱系
export const fetchMemoryLineage = (projectId, params) => http.get(`/api/projects/${projectId}/memories/lineage`, { params })
// 注入路由预览
export const fetchInjectRoutePreview = (projectId, params) => http.get(`/api/projects/${projectId}/memories/inject-route`, { params })
// 注入路由 trace 回放
export const fetchInjectRouteTrace = (projectId, params) => http.get(`/api/projects/${projectId}/memories/inject-route/trace`, { params })
