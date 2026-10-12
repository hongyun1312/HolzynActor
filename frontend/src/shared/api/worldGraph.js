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

// 世界图谱只读查询域 API（六维单域 + 三种聚合视角，共 11 个端点）
// 说明：全部为纯只读 GET，不触发任何 AI 调用；后端 WorldGraphController 已存在，本文件只是前端接线。
import http from './http'

/** 图谱接口前缀 */
const base = (projectId) => `/api/projects/${projectId}/world-graph`

/**
 * 全局概览（时间 + 六维计数 + 宏观物资 + 最近事件 + 最近编年史）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object>} Overview
 */
export const fetchWorldGraphOverview = (projectId) => http.get(`${base(projectId)}/overview`)

/**
 * 地点列表。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<Array>} LocationNode[]
 */
export const fetchWorldGraphLocations = (projectId) => http.get(`${base(projectId)}/locations`)

/**
 * 时间节点（权威引擎位置）。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object>} TimeNode
 */
export const fetchWorldGraphTime = (projectId) => http.get(`${base(projectId)}/time`)

/**
 * 角色列表。
 *
 * @param {number|string} projectId 项目 ID
 * @param {object} [params] 过滤参数
 * @param {string} [params.type] special / common
 * @param {number} [params.locationId] 所在地点
 * @returns {Promise<Array>} CharacterNode[]
 */
export const fetchWorldGraphCharacters = (projectId, params) =>
  http.get(`${base(projectId)}/characters`, { params })

/**
 * 事件列表。
 *
 * @param {number|string} projectId 项目 ID
 * @param {object} [params] 过滤参数
 * @param {string} [params.kind] 事件类型（event/action/crowd/world/evolution…）
 * @param {number} [params.characterId] 参与角色
 * @param {number} [params.limit] 条数上限
 * @returns {Promise<Array>} EventNode[]
 */
export const fetchWorldGraphEvents = (projectId, params) =>
  http.get(`${base(projectId)}/events`, { params })

/**
 * 关系列表。
 *
 * @param {number|string} projectId 项目 ID
 * @param {object} [params] 过滤参数
 * @param {number} [params.characterId] 参与角色
 * @returns {Promise<Array>} RelationEdge[]
 */
export const fetchWorldGraphRelations = (projectId, params) =>
  http.get(`${base(projectId)}/relations`, { params })

/**
 * 势力列表。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<Array>} FactionNode[]
 */
export const fetchWorldGraphFactions = (projectId) => http.get(`${base(projectId)}/factions`)

/**
 * 宏观物资节点。
 *
 * @param {number|string} projectId 项目 ID
 * @returns {Promise<object>} MacroNode
 */
export const fetchWorldGraphMacro = (projectId) => http.get(`${base(projectId)}/macro`)

/**
 * 角色视角聚合（角色 + 位置 + 关系 + 所属组织 + 参与事件）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {number|string} id 角色 ID
 * @returns {Promise<object>} CharacterView
 */
export const fetchWorldGraphCharacterView = (projectId, id) =>
  http.get(`${base(projectId)}/characters/${id}/view`)

/**
 * 地点视角聚合（地点 + 在场角色 + 近期事件 + 关联实体状态）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {number|string} id 地点 ID
 * @returns {Promise<object>} LocationView
 */
export const fetchWorldGraphLocationView = (projectId, id) =>
  http.get(`${base(projectId)}/locations/${id}/view`)

/**
 * 势力视角聚合（势力 + 成员 + 组织间关系分）。
 *
 * @param {number|string} projectId 项目 ID
 * @param {number|string} id 势力 ID
 * @returns {Promise<object>} FactionView
 */
export const fetchWorldGraphFactionView = (projectId, id) =>
  http.get(`${base(projectId)}/factions/${id}/view`)
