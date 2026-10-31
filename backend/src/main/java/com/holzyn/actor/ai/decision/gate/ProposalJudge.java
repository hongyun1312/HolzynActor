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
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.romance.ProposalDecisionSource;
import com.holzyn.actor.domain.growth.romance.RomanceDecisions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 求婚判定的 决策模型 实现。
 * <p><b>定位</b>：{@link ProposalDecisionSource} 插拔点的 决策模型 判定辅助类——由
 * {@code AiProposalDecisionSource} 内部优先委托，场景开关默认关；开启且采纳时返回
 * 决策模型 判定结果，未启用/升级/失败返回 null（调用方回退 Qwen）。</p>
 * <p><b>判定形态</b>：choice（propose 求婚 / wait 再等等 / give_up 结束）——state=恋爱时长 +
 * 候选对象摘要；采纳时按分布采样。propose 的目标取候选列表首位（恋爱中唯一 ROMANTIC 对象）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class ProposalJudge {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = DecisionScenes.PROPOSAL;

    /** state 内问题键 */
    public static final String QID = "proposal";

    /** choice 候选键 */
    public static final String CHOICE_PROPOSE = "propose";
    public static final String CHOICE_WAIT = "wait";
    public static final String CHOICE_GIVE_UP = "give_up";

    @Autowired(required = false)
    private DecisionGate gate;

    /**
     * 决策模型 求婚判定。
     *
     * @param ctx 求婚判定输入
     *
     * @return 决策模型 判定结果（采纳时）；null=未启用/升级/失败（调用方回退 Qwen）
     */
    public RomanceDecisions.ProposalDecision decide(RomanceDecisions.ProposalContext ctx) {
        if (ctx == null || ctx.npcRef() == null || gate == null || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            String stateText = (ctx.npcName() == null ? "该角色" : ctx.npcName())
                    + "已恋爱" + ctx.romanceGameDays() + "游戏日。"
                    + "候选对象：" + (ctx.candidatesSummary() == null || ctx.candidatesSummary().isBlank()
                    ? "（无候选摘要）" : ctx.candidatesSummary().trim());
            Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> questions = DecisionState.newQuestions();
            questions.put(QID, DecisionTemplates.proposalQuestion());
            DecisionState state = DecisionState.text("proposal" + NpcRefs.idOf(ctx.npcRef()), stateText, questions);
            DecisionGate.ChoiceOutcome out = gate.choiceDecision(
                    ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, state, QID);
            if (out == null || !out.adopted() || out.chosen() == null || out.chosen().isBlank()) {
                return null;
            }
            boolean propose = CHOICE_PROPOSE.equals(out.chosen());
            // 目标：propose 时取候选列表首位（恋爱中唯一 ROMANTIC 对象；候选空=程序化校验不通过）
            String targetRef = propose && ctx.candidateRefs() != null && !ctx.candidateRefs().isEmpty()
                    ? ctx.candidateRefs().get(0) : null;
            log.info("[决策模型判定] 求婚：{} chosen={}（恋爱 {} 日）熵={}", ctx.npcName(), out.chosen(),
                    ctx.romanceGameDays(), out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return RomanceDecisions.ProposalDecision.of(propose, targetRef,
                    "决策模型 判定（proposal：" + out.chosen() + "）", "decision");
        } catch (Exception e) {
            log.warn("[决策模型判定] 求婚判定异常（回退 Qwen）: {} : {}", ctx.npcName(), e.getMessage());
            return null;
        }
    }
}
