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
