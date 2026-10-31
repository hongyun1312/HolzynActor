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

// 通用功能域 API：健康检查 / 通知
import http from './http'

// ==================== 健康检查 ====================
export const fetchHealth = () => http.get('/api/health')

// ==================== 通知（顶栏铃铛数据源） ====================
// 说明：后端通知域尚未实现数据源，/api/notifications* 为占位接口（返回空列表/0，见
// backend NotificationController）——铃铛 UI 已接线、打开时刷新拉取；待通知域落地后自动有数据。
export const fetchNotifications = (params) => http.get('/api/notifications', { params }).catch(() => ({ list: [], total: 0 }))
export const fetchUnreadCount = () => http.get('/api/notifications/unread-count').catch(() => 0)
export const markNotificationRead = (id) => http.put(`/api/notifications/${id}/read`).catch(() => null)
export const markAllNotificationsRead = () => http.put('/api/notifications/read-all').catch(() => null)

// ==================== AI 运行观测（只读；顶栏 AI 运行状态抽屉数据源） ====================
/**
 * AI 调用记录分页查询（只读，零 AI 成本）。
 * <p>复用既有的调试域只读接口 `GET /api/debug/ai-calls`：本轮先用它驱动顶栏的
 * 「推理中 / 最近调用 / 失败数」快照；批次③ 会新增面向主前端的聚合只读接口
 * `GET /api/worlds/{projectId}/ai-runtime/status` 并切换到它（见设计规格 §8.2）。</p>
 */
export const fetchAiCalls = (params) => http.get('/api/debug/ai-calls', { params, timeout: 8000 })
