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

package com.holzyn.actor.ai.decision.gate;

import com.holzyn.actor.ai.decision.DecisionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 打断仲裁/冲突协商的 决策模型 实现。
 * <p><b>定位</b>： 冲突协商（scene=negotiate，proceed/wait/yield/redirect 四态）的
 * 决策模型 判定辅助类——由 {@code ActionCoordinatorService.negotiate} 内部优先委托，
 * 场景开关默认关（未过 A/B 评估）；开启且采纳时返回四态之一，未启用/升级/失败返回
 * null（回退 Qwen 协商/确定性兜底）。</p>
 * <p><b>判定形态</b>：choice 四态——state=我方意图/紧要程度 + 对方意图/紧要程度 + 目标资源；
 * 采纳时按分布采样（低权重态也有机会=真人式犹豫与让步）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class NegotiateJudge {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = DecisionScenes.NEGOTIATE;

    /** state 内问题键 */
    public static final String QID = "negotiate";

    /** choice 四态候选键（与 ActionCoordinatorService 协商输出同口径） */
    public static final String CHOICE_PROCEED = "proceed";
    public static final String CHOICE_WAIT = "wait";
    public static final String CHOICE_YIELD = "yield";
    public static final String CHOICE_REDIRECT = "redirect";

    @Autowired(required = false)
    private DecisionGate gate;

    /**
     * 决策模型 冲突仲裁判定。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（trace 归属；可空）
     *
     * @param stateText 冲突情境文本（我方/对方意图与紧要程度 + 目标资源）
     *
     * @return 四态之一（proceed/wait/yield/redirect）；null=未启用/升级/失败（回退 Qwen/确定性）
     */
    public String decide(Long projectId, Long characterId, String stateText) {
        if (stateText == null || stateText.isBlank() || gate == null || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> questions = DecisionState.newQuestions();
            questions.put(QID, DecisionTemplates.negotiateQuestion());
            DecisionState state = DecisionState.text("nego" + (characterId == null ? 0 : characterId),
                    stateText.trim(), questions);
            DecisionGate.ChoiceOutcome out = gate.choiceDecision(
                    projectId, characterId, SCENE, state, QID);
            if (out == null || !out.adopted() || out.chosen() == null) {
                return null;
            }
            log.info("[决策模型判定] 冲突仲裁：characterId={} → {}（熵={}）", characterId, out.chosen(),
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return out.chosen();
        } catch (Exception e) {
            log.warn("[决策模型判定] 冲突仲裁判定异常（回退 Qwen/确定性）: {}", e.getMessage());
            return null;
        }
    }
}
