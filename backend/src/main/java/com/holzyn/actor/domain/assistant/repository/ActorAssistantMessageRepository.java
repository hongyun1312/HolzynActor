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

import com.holzyn.actor.domain.assistant.entity.ActorAssistantMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 助手消息仓库。
 */
public interface ActorAssistantMessageRepository extends JpaRepository<ActorAssistantMessage, Long> {

    /** 会话消息列表（按 id 升序 = 时间正序）。 */
    List<ActorAssistantMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /**
     * 会话消息列表（按 id 倒序；供上下文窗口，Java 侧截取）。
     * <p><b>M5 收尾抓修</b>：旧签名 {@code findTopNBySessionIdOrderByIdDesc(sessionId, n)} 与
     * {@code findTopBySessionIdOrderByIdDesc(sessionId, Pageable)} 的 limit 语义在实测中均未生效
     * （恒返回 1 条）→ 多轮上下文丢失（模型看不到上一条回答）。
     * 改回普通全量查询 + Java 侧 subList 截取（单会话消息量有界，100% 确定性）。</p>
     */
    List<ActorAssistantMessage> findBySessionIdOrderByIdDesc(Long sessionId);

    /** 会话消息数（测试/展示用）。 */
    long countBySessionId(Long sessionId);

    /** 删除会话全部消息（会话删除联动）。 */
    void deleteBySessionId(Long sessionId);
}
