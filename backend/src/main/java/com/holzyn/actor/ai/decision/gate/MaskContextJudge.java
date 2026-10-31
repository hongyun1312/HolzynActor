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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 面具情境解析的 决策模型 实现。
 * <p><b>定位</b>：{@code MaskContextSource} 判定点的 决策模型 判定辅助类——由
 * {@code ProgrammaticMaskContextResolver} 内部优先委托，场景开关默认关（未过 A/B 评估）；
 * 开启且采纳时返回 决策模型 判定的<b>对象情境码</b>，未启用/升级/失败返回 null（回退程序化）。</p>
 * <p><b>判定形态</b>：choice 8 情境码——但<b>场合码（public/private）是确定性事实</b>
 * （地点词命中），仍由程序化判断；决策模型 只判「面对谁」（6 个 before_* 对象码）。
 * partnerRefs 空/null（独处/玩家会话）→ 不调 决策模型（无对象可判），返回 null 走程序化。
 * 多人在场：逐人判定。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class MaskContextJudge {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = DecisionScenes.MASK_CONTEXT;

    /** state 内问题键 */
    public static final String QID = "context";

    /** 对象情境码（决策模型 候选；场合码由程序化判定） */
    public static final List<String> OBJECT_CODES = List.of(
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_AUTHORITY,
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_PARTNER,
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_STRANGER,
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_PEER,
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_CHILD,
            com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_RIVAL);

    @Autowired(required = false)
    private DecisionGate gate;

    /**
     * 决策模型 对象情境码判定（面对谁）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用（npc:{id}）
     *
     * @param npcName 本人名（可空）
     *
     * @param partnerText 对方描述（身份/关系摘要；调用方程序化解析的判定输入）
     *
     * @return 采纳的对象情境码；null=未启用/升级/失败/输入缺失（回退程序化）
     */
    public String decide(Long projectId, String npcRef, String npcName, String partnerText) {
        if (npcRef == null || partnerText == null || partnerText.isBlank() || gate == null
                || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            // 候选：6 对象码（键=情境码，描述自包含）
            Map<String, String> candidates = new java.util.LinkedHashMap<>();
            for (String code : OBJECT_CODES) {
                candidates.put(code, labelOf(code));
            }
            String stateText = (npcName == null || npcName.isBlank() ? "该角色" : npcName)
                    + "此刻面对：" + partnerText.trim();
            Map<String, com.holzyn.actor.ai.decision.DecisionQuestion> questions = DecisionState.newQuestions();
            // 候选=6 对象码（场合码由程序化判定——避免 决策模型 选场合码导致对象信息丢失）
            questions.put(QID, com.holzyn.actor.ai.decision.DecisionQuestion.choice(
                    "根据情境判断该角色此刻面对的对象最贴切的情境码（选一个）："
                            + "对方身份与关系按字面描述判断。", candidates));
            DecisionState state = DecisionState.text("maskctx" + NpcRefs.idOf(npcRef), stateText, questions);
            DecisionGate.ChoiceOutcome out = gate.choiceDecision(
                    projectId, NpcRefs.idOf(npcRef), SCENE, state, QID);
            if (out == null || !out.adopted() || out.chosen() == null) {
                return null;
            }
            log.info("[决策模型判定] 面具情境：{} 面对 {} → {}（熵={}）", npcRef, partnerText, out.chosen(),
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return out.chosen();
        } catch (Exception e) {
            log.warn("[决策模型判定] 面具情境判定异常（回退程序化）: {} : {}", npcRef, e.getMessage());
            return null;
        }
    }

    /** 对象情境码中文名（候选描述；与 MaskContextCode.labelOf 同口径）。 */
    private static String labelOf(String code) {
        return switch (code) {
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_AUTHORITY -> "面对权威（村长/族长/师父/上官等）";
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_PARTNER -> "面对伴侣（配偶/恋人，最放松）";
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_STRANGER -> "面对陌生人/外来者（不熟、需提防）";
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_PEER -> "面对同伴（同村同辈、相熟邻里）";
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_CHILD -> "面对子女/晚辈（需要端着长辈的样子）";
            case com.holzyn.actor.domain.growth.mask.MaskContextCode.BEFORE_RIVAL -> "面对仇怨对象（有过节、心里有疙瘩）";
            default -> code;
        };
    }
}
