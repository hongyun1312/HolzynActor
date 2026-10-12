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

package com.holzyn.actor.domain.npc.dialogue.repository;

import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 群聊成员仓库（actor_conversation_member）。
 * <p>职责：提供按会话查询成员角色的能力。</p>
 */
public interface ActorConversationMemberRepository extends JpaRepository<ActorConversationMember, Long> {

    /**
     * 查询某会话的全部成员。
     *
     * @param conversationId 会话 ID
     *
     * @return 成员列表
     */
    List<ActorConversationMember> findByConversationId(Long conversationId);

    /**
     * 查询某角色参与的全部会话成员关系（记忆抽取/手动补抽定位会话用）。
     *
     * @param characterId 角色 ID
     *
     * @return 成员关系列表
     */
    List<ActorConversationMember> findByCharacterId(Long characterId);

    /**
     * 删除某会话全部成员关系（会话删除时清理）。
     *
     * @param conversationId 会话 ID
     */
    void deleteByConversationId(Long conversationId);
}