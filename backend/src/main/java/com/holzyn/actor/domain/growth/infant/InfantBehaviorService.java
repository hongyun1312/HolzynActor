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

package com.holzyn.actor.domain.growth.infant;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 婴儿行为程序化状态机。
 * <p><b>为什么零 AI</b>：INFANT 阶段硬门控本就禁止对话/抽象行为，而婴儿的真实行为
 * （睡/醒/啼哭/吃奶）是生理驱动的确定性循环——AI 不会比程序更真实，且省下算力给可观察行为。
 * 婴儿档案分阶段揭示走 {@link InfantRevealService}（AI 补全缺失维）。</p>
 * <p><b>状态机（每游戏小时推进一次，挂 WorldEngine 小时边界）</b>：</p>
 * <ul>
 * <li><b>需求衰减</b>：hunger/bladder 按配置速率衰减（婴儿代谢快，比成人更频繁啼哭）；</li>
 * <li><b>状态判定</b>：饥饿或膀胱跌破阈值 → {@code crying}（啼哭）；否则精力足 → {@code awake}、
 * 精力低 → {@code sleeping}；</li>
 * <li><b>啼哭刺激</b>：crying 时向在场父母发布世界事件（kind=infant_cry，importance=2，
 * sourceKey 按小时幂等去重）——父母在下一轮决策中会看到「婴儿在哭」并可能响应
 * （抱哄/哺乳由父母 AI 决策自然产生，不硬编码父母行为）；</li>
 * <li><b>喂养恢复</b>：crying 状态持续超过 2 小时时程序化「被母亲/父亲喂食」恢复 hunger
 * （防婴儿饿死；父母响应细节留给 AI 决策）；</li>
 * <li><b>位置跟随母亲</b>：每小时把婴儿 {@code actor_profile.location} 同步为母亲的所在地
 * （母亲去哪婴儿在哪——真实世界的携带）；</li>
 * <li><b>展示</b>：{@code current_activity} 按状态机文本刷新（襁褓中安睡/醒来咿呀/啼哭不止）。</li>
 * </ul>
 * <p><b>架构不变量</b>：纯程序化零 AI，公开方法无 {@code @Transactional}；失败隔离不阻断世界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfantBehaviorService {

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final LineageService lineageService;
    private final GrowthProperties growthProperties;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;

    /** 啼哭事件幂等键前缀（sourceKey=infant-cry:{childId}:{gameDay}:{hour}，小时级去重）。 */
    private static final String CRY_KEY_PREFIX = "growth-infant-cry:";

    /**
     * 婴儿状态机推进（挂 WorldEngine 小时边界；每游戏小时一次）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 处理的婴儿数
     */
    public int advanceInfants(Long projectId, Long gameSecond) {
        if (projectId == null || gameSecond == null || !growthProperties.getInfant().isEnabled()) {
            return 0;
        }
        int handled = 0;
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (!GrowthStage.INFANT.name().equalsIgnoreCase(ch.getStage())) {
                continue;
            }
            try {
                advanceOne(projectId, ch, gameSecond);
                handled++;
            } catch (Exception e) {
                log.warn("[婴儿] 状态机推进异常（隔离继续）: {} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
        return handled;
    }

    /** 单婴儿推进（需求衰减 → 状态判定 → 啼哭刺激 → 喂养恢复 → 位置跟随 → 展示刷新）。 */
    private void advanceOne(Long projectId, ActorCharacter child, Long gameSecond) {
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, child.npcRef()).orElse(null);
        if (state == null) {
            return;
        }
        // ① 需求衰减（婴儿代谢快：hunger 2.5/时、bladder 2.0/时；有小数余额结转更好，这里直接整点衰减）
        int hunger = state.getHunger() == null ? 60 : state.getHunger();
        int bladder = state.getBladder() == null ? 70 : state.getBladder();
        hunger = Math.max(0, hunger - (int) Math.round(growthProperties.getInfant().getHungerDecayPerHour()));
        bladder = Math.max(0, bladder - (int) Math.round(growthProperties.getInfant().getBladderDecayPerHour()));
        // ② 状态判定（啼哭 → 2 小时后程序化喂食恢复；否则按精力入睡/醒着）
        int cryHunger = growthProperties.getInfant().getCryHungerThreshold();
        int cryBladder = growthProperties.getInfant().getCryBladderThreshold();
        boolean crying = hunger < cryHunger || bladder < cryBladder;
        String activity;
        if (crying) {
            activity = "啼哭不止（" + (hunger < cryHunger ? "饿了" : "") + (hunger < cryHunger && bladder < cryBladder ? "且" : "")
                    + (bladder < cryBladder ? "尿了" : "") + "）";
            // ③ 啼哭刺激父母（小时级幂等去重；importance=2——低频不打扰，父母决策自然响应）
            cryStimulus(projectId, child, gameSecond);
            // ④ 程序化喂养恢复（啼哭持续即视为父母已照料——防婴儿需求持续恶化；父母 AI 决策
            // 仍会产出「抱哄/哺乳」行动，两条路径不冲突：一个管需求、一个管叙事）
            int restore = growthProperties.getInfant().getFeedingRestore();
            hunger = Math.min(100, hunger + restore);
            bladder = Math.min(100, bladder + restore);
        } else {
            int energy = state.getEnergy() == null ? 80 : state.getEnergy();
            activity = energy < 40 ? "襁褓中安睡" : "醒来咿呀学语，手脚乱蹬";
        }
        state.setHunger(hunger);
        state.setBladder(bladder);
        stateRepository.save(state);
        // ⑤ 位置跟随母亲（母亲 = 谱系母亲；无母亲行则不动）
        Optional<String> motherRef = lineageService.motherOf(projectId, child.npcRef());
        motherRef.flatMap(ref -> characterRepository.findById(NpcRefs.idOf(ref)))
                .ifPresent(mother -> {
                    if (mother.getLocation() != null && !mother.getLocation().equals(child.getLocation())) {
                        child.setLocation(mother.getLocation());
                        child.setLocationId(mother.getLocationId());
                    }
                });
        child.setCurrentActivity(activity);
        characterRepository.save(child);
    }

    /** 啼哭刺激（世界事件 kind=infant_cry；小时级幂等）。 */
    private void cryStimulus(Long projectId, ActorCharacter child, Long gameSecond) {
        try {
            List<String> parents = new ArrayList<>();
            lineageService.parentEdgesOf(projectId, child.npcRef()).forEach(e -> {
                if (e.getFromRef() != null) {
                    parents.add(e.getFromRef());
                }
            });
            if (parents.isEmpty()) {
                return;
            }
            long hour = gameSecond / 3600L;
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("infant_cry")
                            .source("growth")
                            .characterId(child.getId())
                            .characterRefs(parents)
                            .visibility("location")
                            .title("婴儿啼哭")
                            .content(child.getName() + "在" + (child.getLocation() == null ? "家中" : child.getLocation())
                                    + "啼哭不止，听动静是饿了（或尿了）。")
                            .importance(2)
                            .gameHour(hour)
                            .sourceKey(CRY_KEY_PREFIX + child.getId() + ":" + hour)
                            .build());
        } catch (Exception e) {
            log.warn("[婴儿] 啼哭刺激发布失败（隔离继续）: {} : {}", child.npcRef(), e.getMessage());
        }
    }
}
