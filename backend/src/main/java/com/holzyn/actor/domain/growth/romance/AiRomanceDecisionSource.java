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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 心动判定默认实现（Qwen AI 主 + 程序化兜底；决策模型 替换实现类即可，主流程零改动）。
 * <p><b>AI 判定</b>（scene=growth_romance_decision，SCHEMA_JSON 0.4/300）：输入角色与候选对象的
 * 互动证据 + 好感，输出 {@code {attracted:boolean, confidence:0-1, reason}}。</p>
 * <p><b>程序化兜底</b>（AI 失败/输出非法 → 不动心，零阻断——真实世界「没证据=没心动」；
 * {@code programmaticFallback=true} 且好感 ≥ 门槛时确定性 0.6 动心）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRomanceDecisionSource implements RomanceDecisionSource {

    private final AiDecisionSourceSupport support;

    /**
     * 决策模型 心动判定（默认关场景；开启且采纳时优先，失败/升级回退本 Qwen 实现；
     * 字段注入保持构造器不变，null=未装配[单测]直接走 Qwen）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.RomanceAttractionJudge decisionJudge;

    @Override
    public RomanceDecisions.AttractionDecision decide(RomanceDecisions.AttractionContext ctx) {
        if (ctx == null || ctx.npcRef() == null || ctx.partnerRef() == null) {
            return RomanceDecisions.AttractionDecision.of(false, 0.0, "参数缺失，视为不动心", "programmatic");
        }
        // ：决策模型 优先（逐场景开关默认关；采纳即用，否则回退 Qwen）
        if (decisionJudge != null) {
            try {
                RomanceDecisions.AttractionDecision nano = decisionJudge.decide(ctx);
                if (nano != null) {
                    return nano;
                }
            } catch (Exception e) {
                log.warn("[婚育] 决策模型 心动判定异常（回退 Qwen）: {} → {} : {}", ctx.npcName(), ctx.partnerName(),
                        e.getMessage());
            }
        }
        String content = support.call(ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, AiDecisionSourceSupport.ph(
                "npc_name", ctx.npcName(),
                "partner_name", ctx.partnerName(),
                "affinity", ctx.affinity(),
                "interaction_evidence", ctx.interactionEvidence() == null ? "（无近期互动记录）" : ctx.interactionEvidence()));
        if (content != null) {
            try {
                JsonNode node = parse(content);
                if (node != null && node.has("attracted")) {
                    boolean attracted = node.get("attracted").asBoolean(false);
                    double conf = node.has("confidence") ? Math.max(0, Math.min(1, node.get("confidence").asDouble(0.5)))
                            : 0.5;
                    String reason = node.has("reason") ? node.get("reason").asText("") : "";
                    log.info("[婚育] 心动判定（AI）：{} → {} attracted={} conf={} reason={}",
                            ctx.npcName(), ctx.partnerName(), attracted, conf, reason);
                    return RomanceDecisions.AttractionDecision.of(attracted, conf, reason, "ai");
                }
            } catch (Exception e) {
                log.warn("[婚育] 心动判定 AI 输出解析失败（程序化兜底）: {} → {} : {}", ctx.npcName(), ctx.partnerName(),
                        e.getMessage());
            }
        }
        return RomanceDecisions.AttractionDecision.of(false, 0.6, "AI 判定失败，程序化兜底：无证据不动心", "programmatic");
    }

    /** 解析 AI 输出（宽容：剥围栏 + 取 JSON + 解析失败返回 null）。 */
    private static JsonNode parse(String content) {
        String json = AiDecisionSourceSupport.extractJson(content);
        if (json == null) {
            return null;
        }
        try {
            return com.holzyn.actor.domain.growth.persona.PersonaJson.mapper().readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
