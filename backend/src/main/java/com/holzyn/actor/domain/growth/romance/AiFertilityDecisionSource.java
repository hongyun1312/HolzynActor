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

/**
 * 生育意愿判定默认实现（Qwen AI 主 + 程序化兜底；决策模型 替换实现类即可）。
 * <p><b>AI 判定</b>（scene=growth_fertility_decision，SCHEMA_JSON 0.4/300）：输入婚姻时长 +
 * 已有子女 + 年龄 + 人口，输出 {@code {want:boolean, reason}}。</p>
 * <p><b>程序化兜底</b>（AI 失败/非法 → 不想要，零阻断；{@code programmaticFallback=true} 且
 * 婚姻满 30 游戏日 → 想要，确定性 0.7）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFertilityDecisionSource implements FertilityDecisionSource {

    private final AiDecisionSourceSupport support;

    /**
     * 决策模型 生育意愿判定（默认关场景；开启且采纳时优先，失败/升级回退本 Qwen 实现；
     * 字段注入保持构造器不变，null=未装配[单测]直接走 Qwen）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.FertilityJudge decisionJudge;

    @Override
    public RomanceDecisions.FertilityDecision decide(RomanceDecisions.FertilityContext ctx) {
        if (ctx == null || ctx.npcRef() == null || ctx.spouseRef() == null) {
            return RomanceDecisions.FertilityDecision.of(false, "参数缺失，视为不想要", "programmatic");
        }
        // ：决策模型 优先（逐场景开关默认关；采纳即用，否则回退 Qwen）
        if (decisionJudge != null) {
            try {
                RomanceDecisions.FertilityDecision nano = decisionJudge.decide(ctx);
                if (nano != null) {
                    return nano;
                }
            } catch (Exception e) {
                log.warn("[婚育] 决策模型 生育意愿判定异常（回退 Qwen）: {} : {}", ctx.npcName(), e.getMessage());
            }
        }
        String content = support.call(ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, AiDecisionSourceSupport.ph(
                "npc_name", ctx.npcName(),
                "spouse_name", ctx.spouseName(),
                "marriage_days", ctx.marriageGameDays(),
                "existing_children", ctx.existingChildren(),
                "age_summary", ctx.ageSummary() == null ? "（年龄信息缺失）" : ctx.ageSummary(),
                "population", ctx.population()));
        if (content != null) {
            try {
                JsonNode node = parse(content);
                if (node != null && node.has("want")) {
                    boolean want = node.get("want").asBoolean(false);
                    String reason = node.has("reason") ? node.get("reason").asText("") : "";
                    log.info("[婚育] 生育意愿判定（AI）：{} want={} reason={}", ctx.npcName(), want, reason);
                    return RomanceDecisions.FertilityDecision.of(want, reason, "ai");
                }
            } catch (Exception e) {
                log.warn("[婚育] 生育意愿判定 AI 输出解析失败（程序化兜底）: {} : {}", ctx.npcName(), e.getMessage());
            }
        }
        return RomanceDecisions.FertilityDecision.of(false, "AI 判定失败，程序化兜底：暂不生育", "programmatic");
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
