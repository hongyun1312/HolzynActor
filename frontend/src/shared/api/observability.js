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

// 观测域只读聚合 API（布局重构批次③ 的后端配套，纯只读、零 AI 成本）
import http from './http'

/**
 * AI 运行时状态（窗口内聚合）。
 *
 * <p>一次请求返回全部指标：总量/成功失败、Token 与缓存命中、时延均值与 P95、
 * 按场景与状态分布、最近调用与失败明细。替代批次① 用「AI 调用分页 + 失败分页」
 * 两次请求拼快照的做法（见设计规格 §8.2）。</p>
 *
 * @param {number|string} projectId 项目 ID
 * @param {number} [windowMinutes=30] 统计窗口（分钟，后端钳制 1~1440）
 * @returns {Promise<object>} 状态视图（trace 未开启时 traceEnabled=false）
 */
export const fetchAiRuntimeStatus = (projectId, windowMinutes = 30) =>
  http.get(`/api/worlds/${projectId}/ai-runtime/status`, {
    params: { windowMinutes },
    timeout: 8000
  })

/**
 * 全局搜索（服务端兜底）。
 *
 * <p>前端 fuse.js 索引负责「已加载列表」的即时模糊匹配；本接口覆盖未加载的分页数据，
 * 并按类型分组返回（角色/地点/事件/知识/项目）。</p>
 *
 * @param {number|string} projectId 项目 ID
 * @param {object} params 查询参数
 * @param {string} params.q 关键词（必填，空白由调用方拦下）
 * @param {string} [params.types] 类型过滤（逗号分隔；缺省全部）
 * @param {number} [params.limit] 总条数上限（默认 30，上限 100）
 * @returns {Promise<object>} 分组命中结果
 */
export const fetchGlobalSearch = (projectId, { q, types, limit } = {}) =>
  http.get(`/api/worlds/${projectId}/search`, {
    params: { q, types, limit },
    timeout: 8000
  })
