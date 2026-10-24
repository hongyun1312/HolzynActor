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

// 本地个人账户 API（替代原登录/鉴权接口；本地单用户模式）
import http from './http'

/**
 * 获取本地账户（不存在时后端惰性创建空档案返回；含 onboarded 标记）。
 */
export const fetchAccount = () => http.get('/api/local-account')

/**
 * 保存/更新本地账户（首次向导提交与设置页编辑共用；各字段选填，onboarded=1 视为完成向导）。
 */
export const saveAccount = (data) => http.put('/api/local-account', data)

/**
 * 单独标记首次设置向导完成。
 */
export const markAccountOnboarded = () => http.post('/api/local-account/onboarded')

/**
 * 会话信息（替代原 /api/auth/me）：返回 { onboarded, account }。
 */
export const fetchAccountMe = () => http.get('/api/local-account/me')

/**
 * 标记新用户 7 步引导完成/跳过（跨重启持久化的唯一权威）。
 * <p>为什么需要后端：桌面端窗口 origin 含随机端口，每次启动都变，localStorage 按 origin 隔离，
 * 只存本地标记必然「完成后重启又出现」。</p>
 */
export const markFirstRunGuideCompleted = () => http.put('/api/local-account/first-run-guide', { completed: true })

/**
 * 重置新用户引导完成标记（教程中心「重看新手引导」）。
 */
export const resetFirstRunGuideCompleted = () => http.put('/api/local-account/first-run-guide', { completed: false })

/**
 * 标记「界面导览」已看过（跨重启持久化；否则桌面端端口变化会让 tourSeen 丢失、每次启动自动重播）。
 */
export const markLayoutTourSeen = () => http.put('/api/local-account/layout-tour', { seen: true })

/**
 * 重置「界面导览已看过」标记（重看导览时可显式重置）。
 */
export const resetLayoutTourSeen = () => http.put('/api/local-account/layout-tour', { seen: false })
