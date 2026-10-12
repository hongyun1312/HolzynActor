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

// 遗传婚育谱系 API
// 数据源：LineageService.familyTree / ActorGrowthGenomeRepository（调试控制器代理）
import http from './http'

/**
 * 三代家族树（以本人为中心）。
 */
export const fetchLineageTree = (projectId, npcRef) =>
  http.get(`/api/debug/growth/lineage`, { params: { projectId, npcRef } })

/**
 * 基因档案（15 位点 + derived_traits + 表观印记 + 父母引用）。
 */
export const fetchGenome = (projectId, npcRef) =>
  http.get(`/api/debug/growth/genome`, { params: { projectId, npcRef } })
