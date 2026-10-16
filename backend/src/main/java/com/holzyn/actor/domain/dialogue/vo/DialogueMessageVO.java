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

package com.holzyn.actor.domain.dialogue.vo;

import java.time.LocalDateTime;

/**
 * 对谈消息视图。
 *
 * @param id            消息 ID
 * @param sessionId     会话 ID
 * @param role          消息角色（user/assistant/group/system/event）
 * @param characterRef  发言角色引用（npc:{id}；user 消息 NULL）
 * @param characterName 发言角色名（assistant/group 附带）
 * @param content       正文
 * @param metaJson      元数据 JSON（swipe/来源/摘要标记）
 * @param seq           会话内序号
 * @param createdAt     创建时间
 */
public record DialogueMessageVO(Long id, Long sessionId, String role, String characterRef, String characterName,
                                String content, String metaJson, Integer seq, LocalDateTime createdAt) {
}
