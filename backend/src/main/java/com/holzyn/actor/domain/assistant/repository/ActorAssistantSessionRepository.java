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

package com.holzyn.actor.domain.assistant.repository;

import com.holzyn.actor.domain.assistant.entity.ActorAssistantSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 助手会话仓库。
 */
public interface ActorAssistantSessionRepository extends JpaRepository<ActorAssistantSession, Long> {

    /** 项目会话列表（按更新时间倒序）。 */
    List<ActorAssistantSession> findByProjectIdOrderByUpdatedAtDescIdDesc(Long projectId);

    /** 项目内单会话（项目隔离校验用）。 */
    Optional<ActorAssistantSession> findByIdAndProjectId(Long id, Long projectId);

    /** 项目会话数（测试/展示用）。 */
    long countByProjectId(Long projectId);
}
