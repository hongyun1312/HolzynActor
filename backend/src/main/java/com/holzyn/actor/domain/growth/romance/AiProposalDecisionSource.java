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

import java.util.List;

/**
 * 求婚判定默认实现（Qwen AI 主 + 程序化兜底；NanoJev 替换实现类即可）。
 * <p><b>AI 判定</b>（scene=growth_proposal_decision，SCHEMA_JSON 0.4/300）：输入恋爱时长 +
 * 候选对象摘要，输出 {@code {propose:boolean, target_ref, reason}}。</p>
 * <p><b>程序化兜底</b>（AI 失败/非法 → 不推进，零阻断；{@code programmaticFallback=true} 时
 * 恋爱满 30 游戏日 → 向列表首位候选求婚，确定性 0.7）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProposalDecisionSource implements ProposalDecisionSource {

    private final AiDecisionSourceSupport support;

    /**
     * NanoJev 求婚判定（默认关场景；开启且采纳时优先，失败/升级回退本 Qwen 实现；
     * 字段注入保持构造器不变，null=未装配[单测]直接走 Qwen）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.nanojev.decision.NanoJevProposalDecision nanoJevDecision;

    @Override
    public RomanceDecisions.ProposalDecision decide(RomanceDecisions.ProposalContext ctx) {
        if (ctx == null || ctx.npcRef() == null) {
            return RomanceDecisions.ProposalDecision.of(false, null, "参数缺失，视为不推进", "programmatic");
        }
        // ：NanoJev 优先（逐场景开关默认关；采纳即用，否则回退 Qwen）
        if (nanoJevDecision != null) {
            try {
                RomanceDecisions.ProposalDecision nano = nanoJevDecision.decide(ctx);
                if (nano != null) {
                    return nano;
                }
            } catch (Exception e) {
                log.warn("[婚育] NanoJev 求婚判定异常（回退 Qwen）: {} : {}", ctx.npcName(), e.getMessage());
            }
        }
        String content = support.call(ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, AiDecisionSourceSupport.ph(
                "npc_name", ctx.npcName(),
                "romance_days", ctx.romanceGameDays(),
                "candidates", ctx.candidatesSummary() == null ? "（无候选摘要）" : ctx.candidatesSummary()));
        if (content != null) {
            try {
                JsonNode node = parse(content);
                if (node != null && node.has("propose")) {
                    boolean propose = node.get("propose").asBoolean(false);
                    String targetRef = node.has("target_ref") ? node.get("target_ref").asText(null) : null;
                    String reason = node.has("reason") ? node.get("reason").asText("") : "";
                    // 程序化校验：目标必须在候选内（AI 编造对象 → 视为不推进，护栏）
                    if (propose && (targetRef == null || !ctx.candidateRefs().contains(targetRef))) {
                        log.warn("[婚育] 求婚目标不在候选内（护栏拦截，视为不推进）: {} → {}", ctx.npcName(), targetRef);
                        return RomanceDecisions.ProposalDecision.of(false, null, "AI 输出对象不在候选内", "programmatic");
                    }
                    log.info("[婚育] 求婚判定（AI）：{} → {} propose={} reason={}", ctx.npcName(), targetRef, propose, reason);
                    return RomanceDecisions.ProposalDecision.of(propose, targetRef, reason, "ai");
                }
            } catch (Exception e) {
                log.warn("[婚育] 求婚判定 AI 输出解析失败（程序化兜底）: {} : {}", ctx.npcName(), e.getMessage());
            }
        }
        return RomanceDecisions.ProposalDecision.of(false, null, "AI 判定失败，程序化兜底：暂不推进", "programmatic");
    }

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
