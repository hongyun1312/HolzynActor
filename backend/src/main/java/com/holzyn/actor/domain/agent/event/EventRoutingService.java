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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件路由服务（事件按地点/角色路由，domain/agent/event）。
 * <p>职责：把世界事件路由到「在场/相关」会话——修 事件注入目标粗糙
 * （旧 WorldEngine 用「最近更新的第一个会话」findFirst，与地点/角色无关）：
 * 按事件的 location_id/character_ids/visibility 匹配会话，替换「最近会话」逻辑。</p>
 * <p>匹配规则（优先级降序）：</p>
 * <ol>
 * <li><b>角色路由</b>：事件带 character_ids（private/当事人）→ 匹配含任一该角色的会话
 * （统一 npc:{id} 成员）；</li>
 * <li><b>地点路由</b>：事件带 location_id（归一）→ 匹配「会话所在地=该地点」或
 * 「任一成员当前所在地（归一 location_id）=该地点」的会话（城西事件只进城西在场角色）；</li>
 * <li><b>地点名兜底</b>：无 location_id 但有地点名 → 按会话所在地名称匹配；</li>
 * <li>无匹配 → 返回 null（调用方仅广播不注入，避免事件进无关会话）。</li>
 * </ol>
 * 同一优先级内按会话更新时间倒序取最新。匹配与路由全部可单测（成员位置解析走 归一化 location_id）。</p>
 * <p>； 增量）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventRoutingService {

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;

    /**
     * 事件 → 目标会话（在场/相关；无匹配返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 事件发生地点 ID（归一，可空）
     *
     * @param locationName 事件发生地点名快照（locationId 缺失时兜底匹配，可空）
     *
     * @param characterIds 事件当事人角色引用列表（统一 npc:{id}，可空/空）
     *
     * @return 匹配的会话（按更新时间倒序取最新）；无匹配返回 null
     */
    public ActorConversation findTarget(Long projectId, Long userId, Long locationId,
                                        String locationName, List<String> characterIds) {
        return findTarget(projectId, userId, locationId, locationName, characterIds, null);
    }

    /**
     * 事件 → 目标会话（排除指定会话版）。
     * <p>excludeConversationId 用于「源会话」排除——手动注入后源会话刚刷新 lastMessageAt，
     * 按更新时间倒序会优先命中源会话自身，路由到「其他在场会话」时须排除。</p>
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @param locationId 事件发生地点 ID（归一，可空）
     *
     * @param locationName 事件发生地点名快照（可空）
     *
     * @param characterIds 事件当事人角色引用列表（可空/空）
     *
     * @param excludeConversationId 排除的会话 ID（可空=不排除）
     *
     * @return 匹配的会话（按更新时间倒序取最新，跳过被排除会话）；无匹配返回 null
     */
    public ActorConversation findTarget(Long projectId, Long userId, Long locationId,
                                        String locationName, List<String> characterIds, Long excludeConversationId) {
        if (projectId == null || userId == null) {
            return null;
        }
        try {
            List<ActorConversation> conversations =
                    conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId);
            if (conversations.isEmpty()) {
                return null;
            }
            // ① 角色路由（当事人 → 含该角色的会话）
            if (characterIds != null && !characterIds.isEmpty()) {
                for (ActorConversation conv : conversations) {
                    if (excluded(conv, excludeConversationId)) {
                        continue;
                    }
                    if (conversationContainsRef(conv.getId(), characterIds)) {
                        return conv;
                    }
                }
            }
            // ② 地点路由（location_id：会话所在地或任一成员所在地=该地点）
            if (locationId != null) {
                for (ActorConversation conv : conversations) {
                    if (excluded(conv, excludeConversationId)) {
                        continue;
                    }
                    if (Long.valueOf(locationId).equals(conv.getLocationId())) {
                        return conv;
                    }
                    if (conversationHasMemberAtLocation(conv.getId(), locationId)) {
                        return conv;
                    }
                }
            }
            // ③ 地点名兜底（无 location_id 时按名称匹配会话所在地）
            if (locationId == null && locationName != null && !locationName.isBlank()) {
                String name = locationName.trim();
                for (ActorConversation conv : conversations) {
                    if (excluded(conv, excludeConversationId)) {
                        continue;
                    }
                    if (conv.getLocation() != null && conv.getLocation().trim().equals(name)) {
                        return conv;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            // 路由失败不阻断发布（返回 null=仅广播，由调用方降级）
            log.warn("[事件路由] 目标会话匹配失败（仅广播不注入）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 会话是否被排除（id 相等即排除） */
    private boolean excluded(ActorConversation conv, Long excludeConversationId) {
        return excludeConversationId != null && conv != null
                && Long.valueOf(excludeConversationId).equals(conv.getId());
    }

    /** 会话是否含任一给定角色引用（统一 npc:{id} 成员） */
    private boolean conversationContainsRef(Long conversationId, List<String> refs) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
        for (ActorConversationMember m : members) {
            String ref = memberRef(m);
            if (ref != null && refs.contains(ref)) {
                return true;
            }
        }
        return false;
    }

    /** 会话是否含任一「当前所在地（归一 location_id）= 目标地点」的成员 */
    private boolean conversationHasMemberAtLocation(Long conversationId, Long locationId) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
        for (ActorConversationMember m : members) {
            Long locId = memberLocationId(m);
            if (locId != null && Long.valueOf(locationId).equals(locId)) {
                return true;
            }
        }
        return false;
    }

    /** 成员 → 统一引用 */
    private String memberRef(ActorConversationMember m) {
        if (m == null || m.getCharacterId() == null) {
            return null;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（单表 actor_profile.id → 统一引用）
        return NpcRefs.of(m.getCharacterId());
    }

    /** 成员 → 当前所在地归一 location_id */
    private Long memberLocationId(ActorConversationMember m) {
        if (m == null || m.getCharacterId() == null) {
            return null;
        }
        try {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理（不再按 member_type 分表取行）
            return characterRepository.findById(m.getCharacterId())
                    .map(ActorCharacter::getLocationId).orElse(null);
        } catch (Exception e) {
            log.warn("[事件路由] 成员位置解析失败: member={} : {}", m.getId(), e.getMessage());
            return null;
        }
    }
}
