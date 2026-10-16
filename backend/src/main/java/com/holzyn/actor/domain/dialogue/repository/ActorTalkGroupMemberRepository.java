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

import com.holzyn.actor.domain.dialogue.entity.ActorTalkGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 对谈群聊成员仓库（actor_talk_group_member）。
 * <p>唯一写入口：{@code DialogueGroupService}（M5，零 AI 短事务）。</p>
 */
public interface ActorTalkGroupMemberRepository extends JpaRepository<ActorTalkGroupMember, Long> {

    /** 会话成员列表（present 在前、按加入序；列表展示/编排共用）。 */
    List<ActorTalkGroupMember> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 会话内指定角色成员（幂等/重复校验用）。 */
    Optional<ActorTalkGroupMember> findBySessionIdAndCharacterRef(Long sessionId, String characterRef);

    /** 删除会话内全部在场成员（换场重算 present 用；manual 保留）。 */
    void deleteBySessionIdAndSource(Long sessionId, String source);

    /** 删除会话内全部成员（会话关闭/清理用）。 */
    void deleteBySessionId(Long sessionId);
}
