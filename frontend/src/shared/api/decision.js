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

// 决策与协调 API：前瞻推演 / 决策 state delta / 冲突检测与资源占用 / ToM 档位
import http from './http'

// 前瞻推演记录列表（npcRef 可空=全项目；limit 截断）
export const fetchForesightRecords = (projectId, params) => http.get(`/api/projects/${projectId}/decision/foresight`, { params })
// 前瞻触发+段落预览（不开会话不落库；{stimulusText?, importance?, urgent?, type?}）
export const previewForesight = (projectId, params) => http.get(`/api/projects/${projectId}/decision/foresight/preview`, { params })
// 决策 state delta 回看（npcRef 可空=全项目）
export const fetchStateDeltas = (projectId, params) => http.get(`/api/projects/${projectId}/decision/state-deltas`, { params })
// 冲突检测记录列表（npcRef 可空=全项目）
export const fetchConflictRecords = (projectId, params) => http.get(`/api/projects/${projectId}/decision/conflicts`, { params })
// 资源占用登记列表（全项目）
export const fetchOccupancy = (projectId, params) => http.get(`/api/projects/${projectId}/decision/occupancy`, { params })
// ToM 推断深度档位预览（{personaJson?}）
export const fetchTomPreview = (projectId, params) => http.get(`/api/projects/${projectId}/decision/tom`, { params })
