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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import com.holzyn.actor.domain.world.fact.service.FactKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 事实认知写入口实现。
 *
 * <p>在场判定继续用 {@code actor_profile.location}（与本地点表同源、零额外依赖），
 * 与 FactPerceptionSubscriber 口径一致；后续接坐标精度时只换内部查询，方法签名不变。</p>
 */
@Slf4j
@Service
public class FactKnowledgeServiceImpl implements FactKnowledgeService {

    private final ActorWorldFactKnowledgeRepository knowledgeRepository;

    private final ActorCharacterRepository characterRepository;

    public FactKnowledgeServiceImpl(ActorWorldFactKnowledgeRepository knowledgeRepository,
                                    ActorCharacterRepository characterRepository) {
        this.knowledgeRepository = knowledgeRepository;
        this.characterRepository = characterRepository;
    }

    @Override
    @Transactional
    public void learn(Long projectId, Long factId, String knowerRef, String sourceType,
                      String sourceRef, int confidence, String distortionJson) {
        learnAt(projectId, factId, knowerRef, sourceType, sourceRef, confidence, distortionJson, null);
    }

    @Override
    @Transactional
    public void learnAt(Long projectId, Long factId, String knowerRef, String sourceType,
                        String sourceRef, int confidence, String distortionJson, Long gameSecond) {
        if (projectId == null || factId == null || knowerRef == null || knowerRef.isBlank()) {
            return;
        }
        String type = sourceType == null || sourceType.isBlank() ? "perceive" : sourceType.trim().toLowerCase();
        int conf = Math.max(0, Math.min(100, confidence));
        ActorWorldFactKnowledge row = knowledgeRepository
                .findByProjectIdAndFactIdAndKnowerRef(projectId, factId, knowerRef)
                .orElseGet(() -> {
                    ActorWorldFactKnowledge fresh = new ActorWorldFactKnowledge();
                    fresh.setProjectId(projectId);
                    fresh.setFactId(factId);
                    fresh.setKnowerRef(knowerRef);
                    fresh.setLearnedGameSecond(null);
                    return fresh;
                });
        row.setSourceType(type);
        row.setSourceRef(sourceRef);
        row.setConfidence(Math.max(conf, row.getConfidence() == null ? 0 : row.getConfidence()));
        row.setDistortionJson(distortionJson);
        if (gameSecond != null) {
            row.setLearnedGameSecond(gameSecond);
        }
        knowledgeRepository.save(row);
    }

    @Override
    @Transactional
    public int broadcastPerceive(Long projectId, ActorWorldFact fact, String locationName,
                                 String exceptRef, long gameSecond) {
        if (projectId == null || fact == null || fact.getId() == null
                || locationName == null || locationName.isBlank()) {
            return 0;
        }
        List<ActorCharacter> characters = characterRepository
                .findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        int learned = 0;
        for (ActorCharacter ch : characters) {
            if (ch == null || ch.getId() == null) {
                continue;
            }
            String ref = NpcRefs.of(ch.getId());
            if (ref.equals(exceptRef)) {
                continue;
            }
            if (!atLocation(ch, fact, locationName)) {
                continue;
            }
            learn(projectId, fact.getId(), ref, "perceive", null, 100, null);
            learned++;
        }
        if (learned > 0) {
            log.debug("[事实层] 在场广播 factKey={} 地点={} 新增认知 {} 人", fact.getFactKey(), locationName, learned);
        }
        return learned;
    }

    /**
     * 同地点判定：优先地点 ID（事实带 locationId 且角色有归一化地点），否则退回地点名。
     *
     * @param ch           角色
     *
     * @param fact         事实
     *
     * @param locationName 事实地点名
     *
     * @return true=在场
     */
    private boolean atLocation(ActorCharacter ch, ActorWorldFact fact, String locationName) {
        if (fact.getLocationId() != null && ch.getLocationId() != null
                && fact.getLocationId().equals(ch.getLocationId())) {
            return true;
        }
        return ch.getLocation() != null && locationName.equalsIgnoreCase(ch.getLocation().trim());
    }
}
