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

package com.holzyn.actor.domain.dialogue.repository;

import com.holzyn.actor.domain.dialogue.entity.ActorTalkWorldbook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 对谈世界书条目仓库（actor_talk_worldbook）。
 */
public interface ActorTalkWorldbookRepository extends JpaRepository<ActorTalkWorldbook, Long> {

    /** 会话世界书条目（按 priority 升序 + id 升序；未启用也列出，供管理视图）。 */
    List<ActorTalkWorldbook> findBySessionIdOrderByPriorityAscIdAsc(Long sessionId);

    /** 会话已启用条目（命中扫描用）。 */
    List<ActorTalkWorldbook> findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(Long sessionId);

    /** 会话内按关键词取条目（同会话关键词唯一，upsert 用）。 */
    Optional<ActorTalkWorldbook> findBySessionIdAndKeyword(Long sessionId, String keyword);
}
