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
import com.holzyn.actor.domain.growth.romance.FertilityDecisionSource;
import com.holzyn.actor.domain.growth.romance.RomanceDecisions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生育意愿判定的 NanoJev 实现。
 * <p><b>定位</b>：{@link FertilityDecisionSource} 插拔点的 NanoJev 判定辅助类——由
 * {@code AiFertilityDecisionSource} 内部优先委托，场景开关默认关；开启且采纳时返回
 * NanoJev 判定结果，未启用/升级/失败返回 null（调用方回退 Qwen）。</p>
 * <p><b>判定形态</b>：score 3 档意愿（0 意愿弱 / 1 一般 / 2 意愿强）——state=婚姻时长 +
 * 已有子女 + 年龄摘要 + 聚落人口；采纳时按分布采样档位；档位 ≥1 视为「想要」（保守：
 * 只有意愿弱[档 0]才明确不想要）。</p>
 * <p><b>架构不变量</b>：含 AI 调用（经判定门），公开方法不带 {@code @Transactional}
 * （AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Slf4j
@Component
public class NanoJevFertilityDecision {

    /** 场景码（逐场景开关键；默认关） */
    public static final String SCENE = NanoJevDecisionScenes.FERTILITY;

    /** state 内问题键 */
    public static final String QID = "fertility";

    /** 「想要」的档位下限（score 档位索引；0=意愿弱不想要，1/2=想要） */
    public static final int WANT_LEVEL_MIN = 1;

    @Autowired(required = false)
    private NanoJevDecisionGate gate;

    /**
     * NanoJev 生育意愿判定。
     *
     * @param ctx 生育意愿判定输入
     *
     * @return NanoJev 判定结果（采纳时）；null=未启用/升级/失败（调用方回退 Qwen）
     */
    public RomanceDecisions.FertilityDecision decide(RomanceDecisions.FertilityContext ctx) {
        if (ctx == null || ctx.npcRef() == null || gate == null || !gate.isEnabled(SCENE)) {
            return null;
        }
        try {
            String stateText = (ctx.npcName() == null ? "该角色" : ctx.npcName())
                    + "已结婚" + ctx.marriageGameDays() + "游戏日，已有子女" + ctx.existingChildren() + "个，"
                    + (ctx.ageSummary() == null || ctx.ageSummary().isBlank()
                    ? "年龄信息缺失" : ctx.ageSummary().trim())
                    + "，聚落人口" + ctx.population() + "。";
            Map<String, com.holzyn.actor.ai.nanojev.NanoJevQuestion> questions = NanoJevState.newQuestions();
            questions.put(QID, NanoJevDecisionTemplates.fertilityQuestion());
            NanoJevState state = NanoJevState.text("fertility" + NpcRefs.idOf(ctx.npcRef()), stateText, questions);
            NanoJevDecisionGate.ScoreOutcome out = gate.scoreDecision(
                    ctx.projectId(), NpcRefs.idOf(ctx.npcRef()), SCENE, state, QID);
            if (out == null || !out.adopted() || out.level() < 0) {
                return null;
            }
            boolean want = out.level() >= WANT_LEVEL_MIN;
            log.info("[NanoJev判定] 生育意愿：{} level={}（期望 {}) want={} 熵={}", ctx.npcName(), out.level(),
                    Math.round(out.expected() * 1000) / 1000.0, want,
                    out.entropy() == null ? "-" : Math.round(out.entropy() * 1000) / 1000.0);
            return RomanceDecisions.FertilityDecision.of(want,
                    "NanoJev 判定（fertility 档位 " + out.level() + "）", "nanojev");
        } catch (Exception e) {
            log.warn("[NanoJev判定] 生育意愿判定异常（回退 Qwen）: {} : {}", ctx.npcName(), e.getMessage());
            return null;
        }
    }
}
