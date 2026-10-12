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

import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话视图对象（ConversationVO）。
 * <p>职责：向前端返回会话信息（含参与成员详情 members，B5 成员类型化——special/crowd
 * 双类型 + 名称快照；characterIds 保留兼容旧前端=特殊角色 ID 列表）。</p>
 */
public record ConversationVO(
        Long id,
        Long projectId,
        Long userId,
        String mode,
        String title,
        String location,
        Long locationId,
        String gameTimeText,
        Integer worldEventEnabled,
        List<Long> characterIds,
        List<ConversationMemberVO> members,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String conversationType,
        String status,
        LocalDateTime playerParticipatedAt
) {
    /**
     * 从实体与成员详情列表构造 VO。
     *
     * @param c 会话实体
     *
     * @param members 参与成员详情列表（B5）
     *
     * @return VO 对象（characterIds=特殊成员 ID 列表，兼容旧前端）
     */
    public static ConversationVO of(ActorConversation c, List<ConversationMemberVO> members) {
        List<Long> ids = members == null ? List.of()
                : members.stream().map(ConversationMemberVO::characterId).toList();
        return new ConversationVO(c.getId(), c.getProjectId(), c.getUserId(), c.getMode(), c.getTitle(),
                c.getLocation(), c.getLocationId(), c.getGameTimeText(), c.getWorldEventEnabled(), ids, members,
                c.getLastMessageAt(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getConversationType(), c.getStatus(), c.getPlayerParticipatedAt());
    }
}
