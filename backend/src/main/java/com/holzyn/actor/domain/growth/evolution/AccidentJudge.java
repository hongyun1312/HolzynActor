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

package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.ai.decision.DecisionQuestion;
import com.holzyn.actor.ai.decision.DecisionState;
import com.holzyn.actor.ai.decision.gate.DecisionGate;
import com.holzyn.actor.ai.decision.gate.DecisionScenes;
import com.holzyn.actor.ai.decision.gate.DecisionTemplates;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.agent.runtime.StimulusType;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 意外事件判定。
 * <p><b>定位</b>：世界里的「意外」不该全部由脚本写死——真人会扭伤、受凉、失手打翻东西，
 * 危险劳作/恶劣天气/虚弱疲劳时概率略高，但总体仍是低概率事件。决策模型 boolean 低概率采样
 * 让 NPC 偶发小意外，写进 L1 感知记忆 + 触发一次即时决策（WORLD_EVENT 刺激），影响其当下
 * 状态而不致死（与既有世界事件体系共存，不新增事件类型）。</p>
 * <p><b>开关</b>：{@code scenes.accident} <b>默认关</b>——仅调试开启时
 * 生效（boolean 场景 sample 采样天然低频，低概率项也会偶发选中）。默认关确保生产零成本。</p>
 * <p><b>执行模型</b>：异步虚拟线程执行、不进 tick 屏障；任何失败 fail-closed 零阻断。
 * 含 决策模型 调用，<b>不带 {@code @Transactional}</b>（AiCallOutsideTransactionTest 反射守护）。</p>
 */
@Slf4j
@Service
public class AccidentJudge {

    /** 意外事件 state 内的问题键（accident 场景：boolean） */
    public static final String QID_ACCIDENT = "accident";

    /** 异步执行器（虚拟线程每任务一线程，用完即弃） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 依赖（字段注入，构造器不变——治理纪律） ====================

    /** 决策模型 判定门 */
    @Autowired(required = false)
    private DecisionGate decisionGate;

    /** 角色主表（全 NPC 名单/名称/位置/当前行动） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 状态模块（需求值用于情境文本；null=状态未知） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** L1 工作记忆（意外留痕；null=跳过记忆写入） */
    @Autowired(required = false)
    private MemoryWorkingService memoryWorkingService;

    /** 实时决策（意外打断源；null=跳过打断） */
    @Autowired(required = false)
    private RealTimeDecisionService realTimeDecisionService;

    // ==================== 对外入口 ====================

    /**
     * 事件驱动的意外判定：事件发布时异步批量判定全 NPC 是否因此遭遇小意外。
     * <p>仅 importance≥2 的事件值得判定；accident 场景默认关（生产零成本），调试开启后
     * boolean 低概率采样偶发命中。命中 → 写 L1 感知记忆 + WORLD_EVENT 即时决策打断
     * （NPC 会因此反应：停下/慌神/求助），不致死。</p>
     *
     * @param projectId 项目 ID
     *
     * @param event 已发布的世界事件（可空=跳过）
     */
    public void onEvent(Long projectId, ActorEvent event) {
        if (projectId == null || event == null || event.getId() == null
                || event.getContent() == null || event.getContent().isBlank()) {
            return;
        }
        if (event.getImportance() == null || event.getImportance() < 2) {
            return; // 琐碎事件不值得意外判定
        }
        if (decisionGate == null || !decisionGate.isEnabled(
                DecisionScenes.ACCIDENT)) {
            return; // accident 场景未启用（默认关）→ 零成本跳过
        }
        executor.submit(() -> {
            try {
                sync(projectId, event);
            } catch (Exception e) {
                log.warn("[意外判定] 批量执行失败（零阻断）: project={} evt={} : {}",
                        projectId, event.getId(), e.getMessage());
            }
        });
    }

    // ==================== 内部实现 ====================

    /** 意外判定批量（同步体，异步线程内执行）。 */
    private void sync(Long projectId, ActorEvent event) {
        List<ActorCharacter> chars = aliveCharacters(projectId);
        if (chars.isEmpty()) {
            return;
        }
        String eventText = event.getContent().trim();
        Long gameSecond = event.getGameHour() == null ? null : event.getGameHour() * 3600L;
        Map<String, DecisionState> states = new LinkedHashMap<>();
        for (ActorCharacter ch : chars) {
            String npcRef = NpcRefs.of(ch.getId());
            states.put(npcRef, DecisionState.text(npcRef,
                    buildStateText(projectId, ch, eventText, gameSecond),
                    accidentQuestions()));
        }
        Map<String, DecisionGate.BooleanOutcome> results =
                decisionGate.booleanDecisions(projectId, DecisionScenes.ACCIDENT,
                        states, QID_ACCIDENT);
        int hits = 0;
        for (ActorCharacter ch : chars) {
            String npcRef = NpcRefs.of(ch.getId());
            DecisionGate.BooleanOutcome bo = results.get(npcRef);
            if (bo == null || !bo.adopted() || !bo.value()) {
                continue;
            }
            hits++;
            String why = "遭遇小意外（" + truncate(eventText, 40) + "）";
            if (memoryWorkingService != null) {
                try {
                    memoryWorkingService.writePerception(projectId, npcRef,
                            "出了点意外：" + why + "，人没事但吓了一跳", 2);
                } catch (Exception e) {
                    log.debug("[意外判定] 记忆写入失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                }
            }
            if (realTimeDecisionService != null) {
                try {
                    realTimeDecisionService.onStimulus(projectId, npcRef,
                            StimulusType.WORLD_EVENT, why, gameSecond);
                } catch (Exception e) {
                    log.debug("[意外判定] 即时决策打断失败（跳过）: ref={} : {}", npcRef, e.getMessage());
                }
            }
            log.debug("[意外判定] 命中：ref={} 意外={} pTrue={}", npcRef, why,
                    bo.pTrue() == null ? "?" : String.format("%.3f", bo.pTrue()));
        }
        if (hits > 0) {
            log.info("[意外判定] 事件 evt={}：{} 人遭遇小意外", event.getId(), hits);
        }
    }

    /** 意外判定问题表（boolean；qid=QID_ACCIDENT）。 */
    private static Map<String, DecisionQuestion> accidentQuestions() {
        Map<String, DecisionQuestion> q = DecisionState.newQuestions();
        q.put(QID_ACCIDENT, DecisionTemplates.accidentQuestion());
        return q;
    }

    /** 存活角色名单（排除 DECEASED 与软删除；普通+特殊全量）。 */
    private List<ActorCharacter> aliveCharacters(Long projectId) {
        if (characterRepository == null) {
            return List.of();
        }
        List<ActorCharacter> out = new ArrayList<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (ch == null || ch.getId() == null) {
                continue;
            }
            if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                continue;
            }
            out.add(ch);
        }
        return out;
    }

    /** 单 NPC 意外判定情境文本（自包含：名称/当前行动/地点/身体状态/事件背景）。 */
    private String buildStateText(Long projectId, ActorCharacter ch, String eventText, Long gameSecond) {
        StringBuilder sb = new StringBuilder();
        sb.append(ch.getName() == null || ch.getName().isBlank() ? "该角色" : ch.getName());
        sb.append("此刻").append(ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "正在忙自己的事" : truncate(ch.getCurrentActivity().trim(), 40));
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("，在").append(truncate(ch.getLocation().trim(), 30));
        }
        ActorNpcState st = null;
        if (npcStateService != null) {
            try {
                st = npcStateService.stateFor(projectId, NpcRefs.of(ch.getId()));
            } catch (Exception ignored) {
                // 状态读取失败按未知
            }
        }
        if (st != null) {
            List<String> flags = new ArrayList<>();
            if (st.getEnergy() != null && st.getEnergy() < 40) {
                flags.add("疲惫");
            }
            if (st.getHunger() != null && st.getHunger() < 40) {
                flags.add("饥饿");
            }
            if (st.getThirst() != null && st.getThirst() < 40) {
                flags.add("口渴");
            }
            if (st.getHealth() != null && st.getHealth() < 60) {
                flags.add("身体不适");
            }
            if (!flags.isEmpty()) {
                sb.append("，").append(String.join("、", flags));
            }
        }
        if (eventText != null && !eventText.isBlank()) {
            sb.append("。正在发生的事：").append(truncate(eventText, 80));
        }
        sb.append("。");
        return truncate(sb.toString(), 160);
    }

    /** 截断（null/空返回 null；超长截断）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
