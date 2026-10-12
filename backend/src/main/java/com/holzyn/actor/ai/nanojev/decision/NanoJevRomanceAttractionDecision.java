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

package com.holzyn.actor.ai.nanojev.decision;

import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.romance.RomanceDecisions;
import com.holzyn.actor.domain.growth.romance.RomanceDecisionSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 心动判定的 NanoJev 实现。
 * <p><b>定位</b>：{@link RomanceDecisionSource} 插拔点的 NanoJev 判定辅助类（N 轨道设计：
 * 「N 轨道只换实现类，主流程零改动」）——由 {@code AiRomanceDecisionSource} 内部优先委托，
 * 场景开关默认关（未过 A/B 评估，0.6B 语义上限风险未验证）；开启且采纳时返回 NanoJev
 * 判定结果，未启用/升级/失败返回 null（调用方回退 Qwen）。</p>
 * <p><b>判定形态</b>：boolean「是否动心」——state=角色与候选对象同村、好感、互动证据摘要；
 * 采纳时按分布采样（sample 默认；低权重也有机会=真人式犹豫）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class NanoJevRomanceAttractionDecision {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = NanoJevDecisionScenes.ROMANCE_ATTRACT;

    /** state 内问题键 */
    public static final String QID = "attract";

    @Autowired(required = false)
    private NanoJevDecisionGate gate;

    /**
     * NanoJev 心动判定。
     *
     * @param ctx 心动判定输入
     *
     * @return NanoJev 判定结果（采纳时）；null=未启用/升级/失败（调用方回退 Qwen）
     */
    public RomanceDecisions.AttractionDecision decide(RomanceDecisions.AttractionContext ctx) {
        if (ctx == null || ctx.npcRef() == null || ctx.partnerRef() == null || gate == null
                || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            String stateText = (ctx.npcName() == null ? "该角色" : ctx.npcName())
                    + "与" + (ctx.partnerName() == null ? "对方" : ctx.partnerName())
                    + "同村，好感" + ctx.affinity() + "。"
                    + "近期互动：" + (ctx.interactionEvidence() == null || ctx.interactionEvidence().isBlank()
                    ? "（无近期互动记录）" : ctx.interactionEvidence().trim());
            Map<String, com.holzyn.actor.ai.nanojev.NanoJevQuestion> questions = NanoJevState.newQuestions();
            questions.put(QID, NanoJevDecisionTemplates.romanceAttractQuestion());
            NanoJevState state = NanoJevState.text("romance" + NpcRefs.idOf(ctx.npcRef()), stateText, questions);
            NanoJevDecisionGate.BooleanOutcome out = gate.booleanDecision(
                    ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, state, QID);
            if (out == null || !out.adopted()) {
                return null;
            }
            double conf = out.top1Prob() == null ? 0.5 : out.top1Prob();
            log.info("[NanoJev判定] 心动：{} → {} attracted={} conf={} 熵={}",
                    ctx.npcName(), ctx.partnerName(), out.value(), Math.round(conf * 1000) / 1000.0,
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return RomanceDecisions.AttractionDecision.of(out.value(), conf, "NanoJev 判定（romance_attract）", "nanojev");
        } catch (Exception e) {
            log.warn("[NanoJev判定] 心动判定异常（回退 Qwen）: {} : {}", ctx.npcName(), e.getMessage());
            return null;
        }
    }
}
