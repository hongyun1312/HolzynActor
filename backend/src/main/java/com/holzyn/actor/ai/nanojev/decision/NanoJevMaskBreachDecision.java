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
import com.holzyn.actor.domain.growth.mask.MaskBreachDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面具破绽检测的 NanoJev 实现。
 * <p><b>定位</b>：{@link MaskBreachDecision} 判定点的 NanoJev 判定辅助类——由
 * {@code ProgrammaticMaskBreachDetector} 内部优先委托，场景开关默认关（未过 A/B 评估）；
 * 开启且采纳时返回 NanoJev 破绽判定，未启用/升级/失败返回 null（回退程序化）。</p>
 * <p><b>判定形态</b>：boolean「此刻是否因触发物命中而破绽」——state=检测文本 +
 * 该角色触发物摘要（档案 ⑪ breaches / ⑨ traumas triggers / 酒精状态，由调用方程序化提取）；
 * 采纳且破绽 → 降幅取默认 0.3（NanoJev 不输出降幅，与程序化创伤触发默认降幅同口径）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class NanoJevMaskBreachDecision {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = NanoJevDecisionScenes.MASK_BREACH;

    /** state 内问题键 */
    public static final String QID = "breach";

    /** NanoJev 判定破绽后的默认抑制降幅（对齐程序化创伤触发物默认 0.3） */
    public static final double DEFAULT_DROP = 0.3;

    @Autowired(required = false)
    private NanoJevDecisionGate gate;

    /**
     * NanoJev 破绽检测。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用（npc:{id}）
     *
     * @param npcName 本人名（可空）
     *
     * @param contextText 检测文本（对话输入/行动文本；可空）
     *
     * @param triggerSummary 触发物摘要（档案 ⑪/⑨ 触发物 + 酒精状态；调用方提取；可空）
     *
     * @return 破绽结果（采纳时）；null=未启用/升级/失败/输入缺失（回退程序化）
     */
    public MaskBreachDecision.BreachResult detect(Long projectId, String npcRef, String npcName,
                                                  String contextText, String triggerSummary) {
        if (npcRef == null || gate == null || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(npcName == null || npcName.isBlank() ? "该角色" : npcName).append("此刻");
            if (triggerSummary != null && !triggerSummary.isBlank()) {
                sb.append("，其触发物/状态：").append(triggerSummary.trim());
            }
            sb.append("。当前情境/话语：")
                    .append(contextText == null || contextText.isBlank() ? "（无文本）" : contextText.trim());
            Map<String, com.holzyn.actor.ai.nanojev.NanoJevQuestion> questions = NanoJevState.newQuestions();
            questions.put(QID, NanoJevDecisionTemplates.maskBreachQuestion());
            NanoJevState state = NanoJevState.text("breach" + NpcRefs.idOf(npcRef), sb.toString(), questions);
            NanoJevDecisionGate.BooleanOutcome out = gate.booleanDecision(
                    projectId, NpcRefs.idOf(npcRef), SCENE, state, QID);
            if (out == null || !out.adopted()) {
                return null;
            }
            if (!out.value()) {
                log.info("[NanoJev判定] 面具破绽：{} 无破绽（熵={}）", npcRef,
                        out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
                return MaskBreachDecision.BreachResult.none();
            }
            log.info("[NanoJev判定] 面具破绽：{} 命中（降幅 {}，熵={}）", npcRef, DEFAULT_DROP,
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return MaskBreachDecision.BreachResult.hit("NanoJev 判定命中触发物", DEFAULT_DROP);
        } catch (Exception e) {
            log.warn("[NanoJev判定] 面具破绽判定异常（回退程序化）: {} : {}", npcRef, e.getMessage());
            return null;
        }
    }
}
