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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可插拔决策策略路由器（P2-3 BrainStrategy）。
 * <p>职责：按角色类型与脑力档位<b>组链</b>（策略集合按 {@code applies} 过滤 +
 * {@code priority} 降序排序），<b>链式责任</b>逐条尝试（首个产出决策者胜出），
 * 每次尝试/命中写决策链路 trace（「哪个策略产出了这次决策」可观测）；单条策略
 * 执行失败只跳过该策略（隔离，不阻断链）。</p>
 * <p><b>链组成</b>（生产装配）：</p>
 * <ul>
 * <li>SPECIAL + programmatic 档 → 危险强制 → 程序化兜底（0 AI，生理仍生效）；</li>
 * <li>SPECIAL + full/light 档 → 危险强制 → 空间选择（决策模型 快通道）→
 * AI 深思（System 2）→ 程序化兜底；</li>
 * <li>COMMON → 危险强制 → 快选择（决策模型+AI 建议）→ 概率化（链空由调用方
 * 作息状态机兜底）。</li>
 * </ul>
 * <p><b>可插拔</b>：新增策略 = 实现 {@link BrainStrategy} 注册为 Spring Bean
 * 即自动入链（O6 发育阶段门控等未来模块的注入点）；链内顺序仅由 priority 决定。</p>
 * <p><b>旁路校验（自省-行为一致性校验·候选 A）</b>：链命中产出决策后，自动调用全部
 * 已注册的 {@link DecisionPostCheck} 校验器（字段注入 required=false 收集，不改造
 * 构造器；null=未装配[单测]跳过）——校验器不产出/不覆盖决策，只对已产出决策做
 * 旁路检查（如自省漂移检测），异常隔离不阻断主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrainStrategyRouter {

    /** 全部已注册策略（Spring 注入；priority 降序尝试）。 */
    private final List<BrainStrategy> strategies;

    /**
     * 决策旁路校验器集合（自省-行为一致性校验：链命中产出后调用；字段注入保持
     * 构造器不变，null=未装配[单测]跳过校验，行为与校验器接入前一致）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<DecisionPostCheck> postChecks = List.of();

    /**
     * 执行一次决策策略链。
     *
     * @param ctx 决策上下文（null/缺角色/缺项目 → null=调用方按链空处理）
     *
     * @return 链产物（含命中策略与步骤记录）；链空（无策略命中）= null
     */
    public DecisionOutcome decide(DecisionContext ctx) {
        if (ctx == null || ctx.character() == null || ctx.projectId() == null) {
            return null;
        }
        List<BrainStrategy> chain = strategies.stream()
                .filter(s -> s.applies(ctx))
                .sorted(Comparator.comparingInt(BrainStrategy::priority).reversed())
                .toList();
        List<String> steps = new ArrayList<>();
        for (BrainStrategy s : chain) {
            Optional<Map<String, Object>> d;
            try {
                d = s.decide(ctx);
            } catch (Exception e) {
                log.warn("[BrainStrategy] 策略执行失败（跳过该策略）: strategy={} ref={} : {}",
                        s.name(), NpcRefs.of(ctx.character().getId()), e.getMessage());
                steps.add(s.name() + "×异常");
                continue;
            }
            if (d != null && d.isPresent()) {
                steps.add(s.name() + "✓命中");
                Object action = d.get().get("action");
                AiCallContext.recordStep(ctx.projectId(), "agent_decision", "brain_strategy",
                        "决策策略链命中 " + s.name() + "（priority=" + s.priority() + "）"
                                + (action == null ? "" : "：「" + abbreviate(String.valueOf(action), 40) + "」"),
                        0);
                log.info("[BrainStrategy] 链命中 strategy={}（priority={}）: ref={} 步骤={}",
                        s.name(), s.priority(), NpcRefs.of(ctx.character().getId()), steps);
                // 旁路校验（自省-行为一致性校验）：链命中产出决策后调用全部校验器；
                // 校验器不产出/不覆盖决策，异常隔离（失败只记日志，绝不影响主流程）
                runPostChecks(ctx, d.get());
                return new DecisionOutcome(d.get(), s.name(), s.priority(), s.postProcess(), steps);
            }
            steps.add(s.name() + "×未命中");
        }
        log.info("[BrainStrategy] 链空（无策略命中）: ref={} kind={} 步骤={}",
                NpcRefs.of(ctx.character().getId()), ctx.kind(), steps);
        return null;
    }

    /**
     * 运行决策旁路校验器（链命中产出决策后调用）。
     * <p>每个校验器独立 try/catch：单个校验器失败只记日志跳过（隔离），
     * 绝不阻断主决策流程——旁路校验是「可观测的加分项」，不是「决策的闸门」。</p>
     */
    private void runPostChecks(DecisionContext ctx, Map<String, Object> decision) {
        if (postChecks == null || postChecks.isEmpty()) {
            return;
        }
        for (DecisionPostCheck check : postChecks) {
            try {
                check.check(ctx, decision);
            } catch (Exception e) {
                log.warn("[BrainStrategy] 旁路校验器执行失败（隔离）: check={} ref={} : {}",
                        check.name(), NpcRefs.of(ctx.character().getId()), e.getMessage());
            }
        }
    }

    /** 字符串截断（摘要展示用）。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
