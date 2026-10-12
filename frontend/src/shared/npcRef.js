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

/**
 * NPC 统一身份引用工具。
 * <p><b>背景</b>： 之前全系统有两套身份前缀——{@code special:{id}}（特殊型）与
 * {@code crowd:{id}}（普通型）。单主表（{@code actor_profile}）之后两套 id 空间合一，
 * 后端统一为<b>单前缀</b>{@code npc:{actor_profile.id}}（见 {@code common/NpcRefs}），
 * 且服务端解析一律走 {@code NpcRefs.idOf}/{@code isNpcRef}——<b>不再识别旧前缀</b>
 * （例：记忆列表 {@code AgentMemoryService.list} 用 {@code isNpcRef} 判定，传
 * {@code special:5} 会退化成"项目全部记忆"）。故前端必须按 {@code npc:{id}} 拼装。</p>
 * <p><b>纪律</b>：任何地方都不要手写 {@code 'npc:' + id} 字面量，一律走本模块——
 * 与后端 {@code NpcRefs} 保持同源口径（唯一拼装点）。</p>
 */

/** 统一前缀（与后端 NpcRefs.PREFIX 一致） */
export const NPC_REF_PREFIX = 'npc:'

/**
 * 生成统一身份引用。
 *
 * @returns {string|null} `npc:{id}`；入参非法返回 null
 */
export function npcRef(id) {
  const n = Number(id)
  return Number.isFinite(n) && n > 0 ? `${NPC_REF_PREFIX}${n}` : null
}

/**
 * 解析统一身份引用为 actor_profile.id。
 *
 * @param {string} ref 引用（可空）
 *
 * @returns {number|null} 主键；非统一前缀/非法返回 null
 */
export function npcIdOf(ref) {
  if (!ref || typeof ref !== 'string') return null
  const s = ref.trim()
  if (!s.startsWith(NPC_REF_PREFIX)) return null
  const n = Number(s.slice(NPC_REF_PREFIX.length))
  return Number.isFinite(n) && n > 0 ? n : null
}

/**
 * 是否普通型 NPC 成员类型。
 * <p>历史值 {@code crowd} 已由后端 {@code NpcRefs.normalizeType} 归一为 {@code common}，
 * 但会话成员等旧数据仍可能带 {@code crowd}，故两者都算普通型。</p>
 *
 * @param {string} type 成员/类型值
 *
 * @returns {boolean} true=普通型
 */
export function isCommonMemberType(type) {
  return type === 'common' || type === 'crowd'
}