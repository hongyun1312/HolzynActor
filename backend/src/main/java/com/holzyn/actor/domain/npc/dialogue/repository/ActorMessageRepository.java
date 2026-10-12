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

import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 消息仓库（actor_message）。
 * <p>职责：提供按会话查询消息历史与最新消息的能力。</p>
 */
public interface ActorMessageRepository extends JpaRepository<ActorMessage, Long> {

    /**
     * 查询某会话全部消息（按创建顺序升序）。
     *
     * @param conversationId 会话 ID
     *
     * @return 消息列表
     */
    List<ActorMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /**
     * 查询某会话最新一条消息。
     *
     * @param conversationId 会话 ID
     *
     * @return 最新消息（可能为空）
     */
    Optional<ActorMessage> findTopByConversationIdOrderByIdDesc(Long conversationId);

    /**
     * 删除某会话全部消息（会话删除时清理）。
     *
     * @param conversationId 会话 ID
     */
    void deleteByConversationId(Long conversationId);

    /**
     * 按类型 + 会话集合查询消息（项目级时间线聚合世界事件使用）。
     *
     * @param type 消息类型（event）
     *
     * @param conversationIds 会话 ID 集合
     *
     * @return 消息列表
     */
    List<ActorMessage> findByTypeAndConversationIdInOrderByIdAsc(String type, Collection<Long> conversationIds);

    /**
     * 按类型查询最近 N 条消息（调试前端事件流新连接补发世界事件用）。
     *
     * @param type 消息类型（event）
     *
     * @return 最近 N 条（时间倒序）
     */
    List<ActorMessage> findTop50ByTypeOrderByIdDesc(String type);

    /**
     * 按会话集合 + 时间窗查询消息。
     *
     * @param conversationIds 会话 ID 集合
     *
     * @param from 起始真实时刻（含）
     *
     * @return 消息列表（按 id 升序）
     */
    List<ActorMessage> findByConversationIdInAndCreatedAtGreaterThanEqualOrderByIdAsc(
            Collection<Long> conversationIds, LocalDateTime from);
}