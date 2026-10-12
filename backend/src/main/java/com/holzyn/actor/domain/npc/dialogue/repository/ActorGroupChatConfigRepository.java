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

import com.holzyn.actor.domain.npc.dialogue.entity.ActorGroupChatConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 群聊配置仓库。
 * <p>职责：按用户查询/保存 actor_group_chat_config（每用户一条）。</p>
 */
public interface ActorGroupChatConfigRepository extends JpaRepository<ActorGroupChatConfig, Long> {

    /**
     * 按用户查询群聊配置（无记录时返回空）。
     *
     * @param userId 归属用户 ID
     *
     * @return 配置（可空）
     */
    Optional<ActorGroupChatConfig> findByUserId(Long userId);
}