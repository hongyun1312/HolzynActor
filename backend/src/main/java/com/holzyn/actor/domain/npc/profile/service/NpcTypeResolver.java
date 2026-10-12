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

package com.holzyn.actor.domain.npc.profile.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 类型解析器。
 * <p><b>为什么需要它</b>： 之前"是不是普通型 NPC"可以直接从引用前缀看出来
 * （{@code crowd:{id}} vs {@code special:{id}}）；统一为单前缀 {@code npc:{id}} 之后，
 * <b>引用本身不再编码类型</b>——类型必须查 {@code actor_profile.type}。全系统约有 30 处
 * 这样的判断（记忆/感知/关系/沙盘/对话/计划等），若继续用 {@code startsWith("crowd:")}，
 * 这些分支会在新世界下<b>静默失效</b>（普通 NPC 被当成特殊型对待或反之），
 * 因此统一收敛到本解析器。</p>
 * <p><b>性能</b>：带项目内短缓存（{@code projectId:npcRef → type}），命中零查询；
 * 缓存有上限防长期运行膨胀；档案类型变更（极少）时由 {@link #evict} 主动失效。
 * 类型是"角色卡级别的稳定属性"，缓存带来的过期风险可忽略。</p>
 * <p><b>零阻断</b>：查不到（脏引用/已删除 NPC）一律返回 {@link NpcRefs#TYPE_SPECIAL}
 * ——与历史行为一致（历史 {@code specialIdOf} 对未知引用返回 null 后按特殊路径处理）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcTypeResolver {

    /** 缓存条目上限（防长期运行内存膨胀） */
    private static final int CACHE_LIMIT = 5000;

    /** 角色主表仓库 */
    private final ActorCharacterRepository characterRepository;

    /** 类型缓存（键 {@code projectId:npcRef}，值 special/common） */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 解析 NPC 类型。
     *
     * @param projectId 项目 ID（可空=只按引用解析，不做项目校验）
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}；兼容历史前缀）
     *
     * @return {@code special} / {@code common}；无法解析返回 {@code special}
     */
    public String typeOf(Long projectId, String npcRef) {
        Long id = NpcRefs.parseLenient(npcRef);
        if (id == null) {
            return NpcRefs.TYPE_SPECIAL;
        }
        String key = projectId + ":" + npcRef;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String type = NpcRefs.TYPE_SPECIAL;
        try {
            ActorCharacter ch = characterRepository.findById(id).orElse(null);
            if (ch != null && (projectId == null || projectId.equals(ch.getProjectId()))) {
                type = NpcRefs.normalizeType(ch.getType());
            }
        } catch (RuntimeException e) {
            log.debug("[NPC类型] 解析失败（回退 special）：ref={} : {}", npcRef, e.getMessage());
        }
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, type);
        return type;
    }

    /**
     * 是否普通型 NPC（取代历史 {@code npcRef.startsWith("crowd:")} 判断）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param npcRef NPC 引用
     *
     * @return true=普通型
     */
    public boolean isCommon(Long projectId, String npcRef) {
        return NpcRefs.TYPE_COMMON.equals(typeOf(projectId, npcRef));
    }

    /**
     * 是否特殊型 NPC。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param npcRef NPC 引用
     *
     * @return true=特殊型
     */
    public boolean isSpecial(Long projectId, String npcRef) {
        return !isCommon(projectId, npcRef);
    }

    /**
     * 失效某角色的类型缓存（档案类型变更/删除时调用）。
     */
    public void evict(Long profileId) {
        String ref = NpcRefs.of(profileId);
        if (ref != null) {
            cache.keySet().removeIf(k -> k.endsWith(":" + ref));
        }
    }

    /** 清空缓存（项目重建/测试用）。 */
    public void clear() {
        cache.clear();
    }
}