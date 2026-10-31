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

// 共享 HTTP 客户端：axios 实例 + 统一响应解包（R 格式 { code, message, data }）
import axios from 'axios'

const http = axios.create({ baseURL: '/', timeout: 10000 })
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) return body.data
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (err) => {
    // HTTP 非 2xx：后端统一返回 { code, message, data }，但 axios 只会给出
    // "Request failed with status code 409" 这类英文状态码文案。这里把后端的人话
    // 换进 err.message（如「已有其它世界挂载…（WORLD_BUSY）」），同时保留
    // err.response / err.status，既有读 e.response.data.message 的调用点不受影响。
    const body = err && err.response && err.response.data
    if (body && typeof body === 'object' && typeof body.message === 'string' && body.message) {
      err.message = body.message
      if (body.code != null) err.bizCode = body.code
    }
    return Promise.reject(err)
  }
)

export default http
