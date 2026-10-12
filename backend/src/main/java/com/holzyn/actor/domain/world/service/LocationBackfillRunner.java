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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存量位置归一启动器（统一位置模型，domain/world/service）。
 * <p>职责：应用启动时（Hibernate ddl-auto=update 已建 location_id 列之后）对存量数据一次性兜底归一——
 * 遍历角色 / 普通 NPC / 会话，凡「有位置文本但 location_id 为空」的行按地点表匹配补 location_id；
 * 未命中保留自由文本兜底（不自动补入地点表）。幂等：已归一（location_id 非空）或无可归一（位置为空）跳过，
 * 重复启动为空操作；只增不删，不覆盖既有 location_id（地点表改名后旧引用保留快照语义）。</p>
 * <p>实现对齐 {@code BrainProfileBackfillRunner} 思路：ApplicationRunner + 失败不阻断启动
 * （异常仅告警放行，下次启动重试）。数量级（项目角色 + 普通 NPC + 会话总数）小，启动开销可忽略。</p>
 * <p>； 增量）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationBackfillRunner implements ApplicationRunner {

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final ActorConversationRepository conversationRepository;
    private final LocationNormalizationService locationNormalizationService;

    /**
     * 启动时执行存量位置归一（幂等）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int filled = backfill();
            log.info("[位置归一] 启动补行完成：{} 处存量位置已归一到地点表", filled);
        } catch (Exception e) {
            // 行失败不阻断启动（幂等，下次启动重试；仅记录）
            log.warn("[位置归一] 启动补行失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 遍历三表补齐 location_id（幂等；单表失败隔离，不阻断其他表）。
     *
     * @return 补行总数
     */
    @Transactional
    public int backfill() {
        int total = 0;
        try {
            total += backfillCharacters();
        } catch (Exception e) {
            log.warn("[位置归一] 角色补行失败（隔离继续）: {}", e.getMessage());
        }
        try {
            total += backfillNpcs();
        } catch (Exception e) {
            log.warn("[位置归一] 普通 NPC 补行失败（隔离继续）: {}", e.getMessage());
        }
        try {
            total += backfillConversations();
        } catch (Exception e) {
            log.warn("[位置归一] 会话补行失败（隔离继续）: {}", e.getMessage());
        }
        return total;
    }

    /** 角色表补 location_id */
    private int backfillCharacters() {
        List<ActorCharacter> chars = characterRepository.findByDeletedOrderByIdAsc(0);
        int n = 0;
        for (ActorCharacter c : chars) {
            if (c.getLocationId() == null && c.getLocation() != null && !c.getLocation().isBlank()) {
                Long id = locationNormalizationService.normalizedId(c.getProjectId(), c.getLocation());
                if (id != null) {
                    c.setLocationId(id);
                    characterRepository.save(c);
                    n++;
                }
            }
        }
        return n;
    }

    /** 普通 NPC 表补 location_id */
    private int backfillNpcs() {
        List<ActorCharacter> npcs = npcRepository.findAll();
        int n = 0;
        for (ActorCharacter e : npcs) {
            if (e.getLocationId() == null && e.getLocation() != null && !e.getLocation().isBlank()) {
                Long id = locationNormalizationService.normalizedId(e.getProjectId(), e.getLocation());
                if (id != null) {
                    e.setLocationId(id);
                    npcRepository.save(e);
                    n++;
                }
            }
        }
        return n;
    }

    /** 会话表补 location_id */
    private int backfillConversations() {
        List<ActorConversation> convs = conversationRepository.findAll();
        int n = 0;
        for (ActorConversation c : convs) {
            if (c.getLocationId() == null && c.getLocation() != null && !c.getLocation().isBlank()) {
                Long id = locationNormalizationService.normalizedId(c.getProjectId(), c.getLocation());
                if (id != null) {
                    c.setLocationId(id);
                    conversationRepository.save(c);
                    n++;
                }
            }
        }
        return n;
    }
}
