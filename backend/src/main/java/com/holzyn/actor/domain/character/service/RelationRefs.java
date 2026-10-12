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

package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;

import java.util.Optional;

/**
 * 角色统一引用（characterRef）解析工具（阶段 D，domain/character/service）。
 * <p><b>重写</b>：统一身份改为单前缀 {@code npc:{actor_profile.id}} 后，
 * 本类从「special:{id} / crowd:{id} 双前缀 + 双表」语义重写为<b>统一单前缀单表</b>语义——
 * 端点引用一律 {@link NpcRefs#of(Long)}，类型不再由前缀编码。</p>
 * <p>职责：把「{@code npc:{id}} / 纯名称（幽灵）」统一解析为关系端点，
 * 供动态关系（learning 组件）、手动 CRUD、拓扑图共用——修 「关系 id=0 幽灵节点
 * 名称兜底歧义」：同一 name 的多个 NPC 以 {@code npc:{id}} 正式端点区分。</p>
 * <p>端点解析规则：</p>
 * <ul>
 * <li>{@code npc:{id}} → 正式端点（ref=统一引用 + id + 角色名快照）；</li>
 * <li>纯名称 → 按项目内 actor_profile 名称匹配
 * → 命中转正式 ref；都不命中 → 幽灵端点（ref=null，仅名称兜底）。</li>
 * </ul>
 * <p>端点匹配（{@link #endpointMatches}）：存量行以 from_character_id+from_name 兜底，
 * 新行以 from_ref 为正式键；正式端点优先 id 精确匹配（修），名称端点只匹配
 * 仍为幽灵（ref=null 且 id=0）的行，避免重名误匹配。</p>
 */
public final class RelationRefs {

    private RelationRefs() {
    }

    /**
     * 关系端点：解析后的统一表示。
     *
     * @param ref 正式 characterRef（{@code npc:{id}}；纯名称幽灵端点=null）
     *
     * @param id 该 NPC 的 {@code actor_profile.id}（正式端点恒有值；幽灵=null）
     *
     * @param name 端点名称快照（正式端点取实体名；幽灵=用户输入名）
     */
    public record Endpoint(String ref, Long id, String name) {
    }

    /**
     * 解析端点：支持 {@code npc:{id}} / 纯名称（纯名称按项目内 actor_profile 匹配，
     * 命中转正式 ref；都不命中为幽灵端点）。
     *
     * @param projectId 项目 ID（名称匹配的作用域）
     *
     * @param refOrName 端点引用或名称
     *
     * @param characterRepository actor_profile 仓库
     *
     * @return 解析后的端点
     */
    public static Endpoint resolve(Long projectId, String refOrName,
                                   ActorCharacterRepository characterRepository) {
        if (refOrName == null || refOrName.isBlank()) {
            return null;
        }
        String raw = refOrName.trim();
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（能否解析成 actor_profile 行）
        Long id = NpcRefs.idOf(raw);
        if (id != null) {
            String name = null;
            if (characterRepository != null) {
                name = characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
            }
            return new Endpoint(NpcRefs.of(id), id, name);
        }
        // 纯名称：按项目内 actor_profile 匹配
        if (projectId != null && characterRepository != null) {
            Optional<ActorCharacter> npc = characterRepository
                    .findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                    .filter(c -> raw.equalsIgnoreCase(c.getName() == null ? "" : c.getName().trim()))
                    .findFirst();
            if (npc.isPresent()) {
                ActorCharacter c = npc.get();
                return new Endpoint(NpcRefs.of(c.getId()), c.getId(), c.getName());
            }
        }
        // 都不命中 → 幽灵端点（ref=null，仅名称兜底）
        return new Endpoint(null, null, raw);
    }

    /**
     * 判断关系行端点是否命中目标端点（互动/匹配用，修）：
     * 正式端点优先 id 精确匹配，
     * 名称端点只匹配仍为幽灵的行。
     *
     * @param sideRef 关系行该侧存储的正式 ref（from_ref/to_ref，可空）
     *
     * @param sideId 关系行该侧存储的 NPC ID（from_character_id/to_character_id）
     *
     * @param sideName 关系行该侧存储的名称快照（from_name/to_name，可空）
     *
     * @param target 目标端点（解析结果）
     *
     * @return 是否命中
     */
    public static boolean endpointMatches(String sideRef, Long sideId, String sideName, Endpoint target) {
        if (target == null) {
            return false;
        }
        if (target.id() != null && target.id() > 0) {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理（id 精确匹配；ref 相等仅作存量行兜底）
            if (target.id().equals(sideId)) {
                return true;
            }
            return target.ref() != null && target.ref().equals(sideRef);
        }
        // 幽灵名称端点：仅匹配仍为幽灵（ref=null 且 id=0）的行，避免重名误匹配
        if (sideRef != null || (sideId != null && sideId > 0)) {
            return false;
        }
        return sideName != null && target.name() != null
                && sideName.trim().equalsIgnoreCase(target.name().trim());
    }

    /**
     * 把端点转成关系行存储。
     *
     * @param endpoint 解析后的端点
     *
     * @return 存储值三元组（characterId/name/ref）
     */
    public static Store storeOf(Endpoint endpoint) {
        if (endpoint == null) {
            return new Store(0L, null, null);
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（不再有"crowd 端点 id=null"特例）
        if (endpoint.ref() != null && endpoint.id() != null && endpoint.id() > 0) {
            return new Store(endpoint.id(),
                    endpoint.name() == null ? null : endpoint.name(), endpoint.ref());
        }
        return new Store(0L, endpoint.name(), null);
    }

    /** 端点存储值（characterId / name 快照 / 正式 ref） */
    public record Store(Long characterId, String name, String ref) {
    }
}