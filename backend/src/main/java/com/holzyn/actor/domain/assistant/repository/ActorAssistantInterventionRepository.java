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

import com.holzyn.actor.domain.assistant.entity.ActorAssistantIntervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 助手世界干预计划仓库（actor_assistant_intervention，M4）。
 * <p>职责：待确认/已执行干预的存取；按会话查询最近计划（ChatService 组装 confirm SSE 载荷）。</p>
 */
@Repository
public interface ActorAssistantInterventionRepository extends JpaRepository<ActorAssistantIntervention, Long> {

    /** 某会话的干预计划（按 id 倒序 = 最新在前）。 */
    List<ActorAssistantIntervention> findBySessionIdOrderByIdDesc(Long sessionId);

    /** 项目隔离取干预计划（不存在返回空）。 */
    Optional<ActorAssistantIntervention> findByIdAndProjectIdAndSessionId(Long id, Long projectId, Long sessionId);

    /** 某会话最近一条待确认计划。 */
    Optional<ActorAssistantIntervention> findTopBySessionIdAndStatusOrderByIdDesc(Long sessionId, String status);
}
