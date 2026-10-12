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

package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.genetic.OffspringGenerator;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 孕期服务。
 * <p><b>状态模型</b>（{@code actor_state} 孕期三列 + 冷却列）：</p>
 * <ul>
 * <li>{@code pregnancy_from_ref} = 胎儿父亲；{@code pregnancy_start_game_second} = 受孕时刻；
 * {@code pregnancy_due_game_second} = start + 280 游戏日；</li>
 * <li>{@code last_birth_game_second} = 最近分娩时刻（生育冷却 180 游戏日基准）；</li>
 * </ul>
 * <p><b>门控（调用方 MarriageService 执行，本类只落状态）</b>：母方适龄（YOUTH~MIDDLE）、
 * 双方已婚、未怀孕、冷却已过、人口未满。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PregnancyService {

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final GrowthProperties growthProperties;
    private final OffspringGenerator offspringGenerator;
    private final AgentMemoryService agentMemoryService;

    /**
     * 启动孕期（门控由调用方执行；本方法幂等：已怀孕/参数非法跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param motherRef 母亲 npcRef
     *
     * @param fatherRef 胎儿父亲 npcRef（可空）
     *
     * @param gameSecond 当前世界时刻
     *
     * @return true=已启动
     */
    public boolean startPregnancy(Long projectId, String motherRef, String fatherRef, Long gameSecond) {
        if (projectId == null || motherRef == null || gameSecond == null) {
            return false;
        }
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, motherRef).orElse(null);
        if (state == null) {
            return false;
        }
        if (state.getPregnancyFromRef() != null) {
            log.info("[孕期] 已怀孕，忽略重复启动：motherRef={}", motherRef);
            return false;
        }
        long pregnancyDays = growthProperties.getReproduction().getPregnancyGameDays();
        state.setPregnancyFromRef(fatherRef);
        state.setPregnancyStartGameSecond(gameSecond);
        state.setPregnancyDueGameSecond(gameSecond + pregnancyDays * 86400L);
        stateRepository.save(state);
        log.info("[孕期] 受孕：{} 怀上{}的孩子（预产期 +{} 游戏日）", motherRef,
                fatherRef == null ? "（未知）" : fatherRef, pregnancyDays);
        return true;
    }

    /**
     * 日检分娩：全部怀孕且到预产期的女性 → OffspringGenerator 分娩。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 本次出生数
     */
    public int checkBirths(Long projectId, Long gameSecond) {
        if (projectId == null || gameSecond == null
                || !growthProperties.getReproduction().isEnabled()) {
            return 0;
        }
        int births = 0;
        for (ActorNpcState state : stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                if (state.getPregnancyFromRef() == null || state.getPregnancyDueGameSecond() == null) {
                    continue;
                }
                if (state.getPregnancyDueGameSecond() > gameSecond) {
                    continue;
                }
                ActorCharacter child = offspringGenerator.createOffspring(projectId, state.getNpcRef(),
                        state.getPregnancyFromRef(), gameSecond);
                if (child != null) {
                    births++;
                }
            } catch (Exception e) {
                log.warn("[孕期] 分娩异常（隔离继续）: {} 原因={}", state.getNpcRef(), e.getMessage());
            }
        }
        if (births > 0) {
            log.info("[孕期] 日检分娩完成：projectId={} 出生 {} 人", projectId, births);
        }
        return births;
    }

    /** 是否怀孕（孕期状态非空）。 */
    public boolean isPregnant(Long projectId, String npcRef) {
        return stateRepository.findByProjectIdAndNpcRef(projectId, npcRef)
                .map(st -> st.getPregnancyFromRef() != null).orElse(false);
    }
}
