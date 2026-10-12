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
 * 决策策略链·AI 深思（System 2，priority=60，SPECIAL）。
 * <p>核心角色「值得深思时」的生成式决策：action_gen 模板 + ReAct 新契约解析 +
 * 重试≤2 + 用量/trace 记账；全部重试失败 → 返回空（链继续 → 程序化兜底），
 * 与既有「AI 失败程序化兜底（continue 当前行为）」语义一致。</p>
 * <p><b>行为兼容</b>：内部逻辑 = 既有 {@code DecisionService.deliberateDecision}
 * 原样委托，不新增/改变决策行为。</p>
 */
@Slf4j
@Component
public class DeliberateStrategy implements BrainStrategy {

    /** 思考组件（AI 深思委托；@Lazy 破 DecisionService→Router→本策略 环） */
    @Autowired(required = false)
    @Lazy
    private DecisionService decisionService;

    @Override
    public String name() {
        return "deliberate";
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        // 仅核心角色；programmatic 档不经 AI（0 AI 语义，与既有脑力门控一致）
        return ctx != null && NpcKind.SPECIAL == ctx.kind()
                && !"programmatic".equals(ctx.brainLevel());
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        if (decisionService == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> decision = decisionService.deliberateDecision(
                    ctx.character(), ctx.userId(), ctx.situation());
            return decision == null ? Optional.empty() : Optional.of(decision);
        } catch (Exception e) {
            log.warn("[BrainStrategy] AI 深思失败（链继续）: ref={} : {}", ctx.character().getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
