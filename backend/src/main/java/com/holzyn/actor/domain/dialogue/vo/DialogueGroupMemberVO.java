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
 * 对谈群聊成员视图（M5）。
 *
 * @param id            成员行 ID
 * @param sessionId     群聊会话 ID
 * @param characterRef  成员角色引用（npc:{id}）
 * @param characterName 成员角色名
 * @param source        成员来源：present 在场 / manual 拉人
 * @param createdAt     加入时间
 */
public record DialogueGroupMemberVO(Long id, Long sessionId, String characterRef, String characterName,
                                    String source, LocalDateTime createdAt) {
}
