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

// 玩家档案 API（M3：玩家 persona + 玩家-角色关系设定 / 关系行）
// 语义：对谈模式以「玩家档案」身份与你对话（与特殊 NPC 档案同构、无记忆/状态/行动）；
// 关系设定（用户级，存玩家档案 relation_settings_json）+ 关系行（项目级，actor_talk_player_relation）。
import http from './http'

// 玩家档案（含 persona 六维 + 每角色关系设定）→ PlayerProfileVO
export const fetchPlayerProfile = () => http.get('/api/player-profile')

// 保存玩家档案（name/persona 选填；null 保持原值、空串清空；persona 按六维键合并）→ PlayerProfileVO
export const savePlayerProfile = (data) => http.put('/api/player-profile', data)

// 保存某角色关系设定（past/identity/attitude 选填）→ PlayerProfileVO
export const savePlayerRelationSetting = (characterRef, data) =>
  http.put(`/api/player-profile/relation-settings/${encodeURIComponent(characterRef)}`, data)

// 某项目全部玩家-角色关系行 → PlayerRelationVO[]
export const fetchPlayerRelations = (projectId) =>
  http.get(`/api/projects/${projectId}/player-profile/relations`)

// 保存某项目+角色关系行（affinity/trust/stage/note 选填）→ PlayerRelationVO
export const savePlayerRelation = (projectId, characterRef, data) =>
  http.put(`/api/projects/${projectId}/player-profile/relations/${encodeURIComponent(characterRef)}`, data)
