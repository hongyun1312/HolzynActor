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

import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import java.time.LocalDateTime;

/**
 * 消息视图对象（MessageVO）。
 * <p>职责：向前端返回对话消息（含 SSE 状态与 token 用量）。</p>
 */
public record MessageVO(
        Long id,
        Long conversationId,
        Long characterId,
        String role,
        String type,
        String content,
        String status,
        Integer tokenIn,
        Integer tokenOut,
        LocalDateTime createdAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param m 消息实体
     *
     * @return VO 对象
     */
    public static MessageVO of(ActorMessage m) {
        return new MessageVO(m.getId(), m.getConversationId(), m.getCharacterId(), m.getRole(),
                m.getType(), m.getContent(), m.getStatus(), m.getTokenIn(), m.getTokenOut(), m.getCreatedAt());
    }
}