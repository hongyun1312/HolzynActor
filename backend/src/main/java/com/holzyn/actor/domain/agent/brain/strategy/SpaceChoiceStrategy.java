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

package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.agent.thinking.DecisionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 决策策略链·空间候选选择（System 1 快通道，priority=80，SPECIAL）。
 * <p>核心角色「此刻挑一个」的高频快判：空间 possible_actions + 通用三项
 * （+社交聚集/生理低值注入候选）由 决策模型 space_decision 亚秒选择
 * （40s→亚秒 ≈40×）——Qwen 只负责低频生成空间（语义质量），决策模型 做
 * 快选择（差异=真人随机波动）；判定失败/未启用/空间无效 → 链继续（AI 深思）。</p>
 * <p><b>行为兼容</b>：内部逻辑 = 既有 {@code DecisionService.trySpaceDecision}
 * 原样委托（含决策前空间保障），不新增/改变决策行为。</p>
 */
@Slf4j
@Component
public class SpaceChoiceStrategy implements BrainStrategy {

    /** 思考组件（空间候选选择委托；@Lazy 破 DecisionService→Router→本策略 环） */
    @Autowired(required = false)
    @Lazy
    private DecisionService decisionService;

    @Override
    public String name() {
        return "space_choice";
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        // 仅核心角色；programmatic 档不经快通道（0 AI 语义，与既有脑力门控一致）
        return ctx != null && NpcKind.SPECIAL == ctx.kind()
                && !"programmatic".equals(ctx.brainLevel());
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        if (decisionService == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> decision = decisionService.trySpaceDecision(
                    ctx.character(), ctx.situation());
            return decision == null ? Optional.empty() : Optional.of(decision);
        } catch (Exception e) {
            log.warn("[BrainStrategy] 空间候选选择失败（链继续）: ref={} : {}", ctx.character().getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
