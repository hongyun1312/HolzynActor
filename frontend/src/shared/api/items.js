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

//
// 物品与装备（对接物品面板）
//
// 全部只读；唯一带副作用的是 provision（幂等补发开局供给，运维用）。
// 后端：domain/item/controller/ItemController。
//
import http from './http'

// 有物品的持有者清单（人/货架；左栏选择器）
export const fetchItemHolders = (projectId) =>
  http.get(`/api/worlds/${projectId}/items/holders`)

// 某持有者的背包 + 装备（holder=npc:1 / loc:65）
export const fetchItemHolder = (projectId, holder) =>
  http.get(`/api/worlds/${projectId}/items`, { params: { holder } })

// 本项目可见的物品定义（本项目 + 全局）
export const fetchItemDefs = (projectId) =>
  http.get(`/api/worlds/${projectId}/items/defs`)

// 最近物品流水（holder 可空=全世界；limit≤200）
export const fetchItemLedger = (projectId, params) =>
  http.get(`/api/worlds/${projectId}/items/ledger`, { params })

// 幂等补发开局供给（正常由「打开世界」自动触发）
export const provisionItems = (projectId) =>
  http.post(`/api/worlds/${projectId}/items/provision`)
