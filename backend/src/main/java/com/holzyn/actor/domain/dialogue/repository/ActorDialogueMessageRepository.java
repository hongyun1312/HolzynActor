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

import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 对谈消息仓库。
 * <p><b>纪律</b>：多轮上下文窗口一律普通查询 + Java 侧 subList 截窗
 * （对齐 assistant 域 M5 抓修——Spring Data 派生 Top/Pageable limit 实测恒返回 1 条）。</p>
 */
public interface ActorDialogueMessageRepository extends JpaRepository<ActorDialogueMessage, Long> {

    /** 会话消息列表（时间正序=seq 序）。 */
    List<ActorDialogueMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 会话消息列表（时间倒序，供滚动窗口取最近 N 条后反序）。 */
    List<ActorDialogueMessage> findBySessionIdOrderByIdDesc(Long sessionId);

    /** 会话内单条消息（编辑/删除/换条/重写定位）。 */
    Optional<ActorDialogueMessage> findByIdAndSessionId(Long id, Long sessionId);

    /** 会话内 id 严格大于某值的消息（编辑后作废后续链）。 */
    List<ActorDialogueMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long id);

    /** 会话内 id 大于等于某值的消息（删除/重写截断，含目标本身）。 */
    List<ActorDialogueMessage> findBySessionIdAndIdGreaterThanEqualOrderByIdAsc(Long sessionId, Long id);

    /** 会话消息数（seq 分配用）。 */
    long countBySessionId(Long sessionId);

    /** 删除会话的全部消息（会话删除级联用）。 */
    void deleteBySessionId(Long sessionId);
}
