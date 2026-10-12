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

import com.holzyn.actor.domain.agent.prompt.DecisionReActParse;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 决策策略链·程序化兜底（priority=0，SPECIAL）。
 * <p>链尾恒在（continue 当前行为，0 AI）：AI 不可用/快通道未命中/预算用尽时
 * 「继续手头的事」而非凭空生成新行动——真实的人不会因决策系统失效就切换行动；
 * 与既有 programmatic 档语义一致（{@code continueDecision}）。</p>
 * <p><b>不走调用方后处理</b>（{@code postProcess()=false}）：与既有
 * 「programmatic 档直接返回、不走护栏/前瞻/想法」行为对齐。</p>
 */
@Component
public class ProgrammaticFallbackStrategy implements BrainStrategy {

    @Override
    public String name() {
        return "programmatic_fallback";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        return ctx != null && NpcKind.SPECIAL == ctx.kind();
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        String activity = ctx.character() == null || ctx.character().getCurrentActivity() == null
                ? null : ctx.character().getCurrentActivity();
        Map<String, Object> decision = DecisionReActParse.continueDecision(activity);
        AiCallContext.recordStep(ctx.projectId(), "agent_decision", "thinking",
                "程序化兜底决策（continue 当前行为，0 AI）", 0);
        return Optional.of(decision);
    }

    @Override
    public boolean postProcess() {
        return false;
    }
}
