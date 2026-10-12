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

package com.holzyn.actor.domain.conversation.vo;

/**
 * 会话成员视图对象（B5 成员类型化）。
 * <p>职责：向前端暴露会话成员详情——成员对象 ID（special→actor_character.id / crowd→actor_ordinary_npc.id）、
 * 成员类型（special/crowd）与名称快照（crowd 必填，special 可空由角色表解析）。</p>
 */
public record ConversationMemberVO(
        Long characterId,
        String memberType,
        String memberName
) {
}
