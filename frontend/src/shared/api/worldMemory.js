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

// 项目记忆功能域 API
import http from './http'

// 编年史查询（fromDay/toDay 游戏日区间、entityKey 关联实体、importance 重要度下限；page/size 分页）
export const fetchChronicles = (projectId, params) => http.get(`/api/projects/${projectId}/world-memory/chronicle`, { params })
// 编年史条目详情（含关联事件/实体）
export const fetchChronicleDetail = (projectId, chronicleId) => http.get(`/api/projects/${projectId}/world-memory/chronicle/${chronicleId}`)
// 手动补充编年史条目（{gameDay?, title, content 必填, importance?, involvedEntities?, gameHour?}）
export const addChronicle = (projectId, data) => http.post(`/api/projects/${projectId}/world-memory/chronicle`, data)
// 实体当前状态（type=location/person/faction 过滤；asOfGameDay 按时间点查询历史态；page/size 分页）
export const fetchEntities = (projectId, params) => http.get(`/api/projects/${projectId}/world-memory/entities`, { params })
// 单实体状态版本链（失效历史）
export const fetchEntityHistory = (projectId, entityKey) => http.get(`/api/projects/${projectId}/world-memory/entities/${encodeURIComponent(entityKey)}/history`)
// 手动更新实体状态（{entityKey 必填, currentState 必填, recentChange?, entityType?, name?, gameDay?}，触发失效更新）
export const updateEntity = (projectId, data) => http.post(`/api/projects/${projectId}/world-memory/entities`, data)
// 项目记忆概览（编年史条数/实体数/归档数/最新条目/近期实体变化）
export const fetchWorldMemoryOverview = (projectId) => http.get(`/api/projects/${projectId}/world-memory/overview`)
