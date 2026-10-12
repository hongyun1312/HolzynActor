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

package com.holzyn.actor.domain.growth.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增长系统指标。
 * <p><b>计数项</b>：</p>
 * <ul>
 * <li>空间生成：{@code spaceAiOk}（AI 成功）/ {@code spaceAiFail}（AI 失败→程序化兜底）/
 * （格式合规率分母，接入 Prompt 指标口径）；</li>
 * <li>空间内行动率：{@code decisionInSpace} / {@code decisionOutside}（决策落点是否在
 * possible_actions 内——只统计不强制， 「软引导 + 可配硬约束」）；</li>
 * <li>护栏拦截：{@code stageComplianceIntercepts} / {@code spaceComplianceIntercepts} /
 * {@code knowledgeBoundIntercepts}（三条护栏各自的拦截次数，验收「拦截数→0」的观测口径）。</li>
 * </ul>
 * <p>计数器只增不改、线程安全；快照 {@link #snapshot()} 供调试接口
 * {@code /api/debug/growth/metrics} 与测试断言。进程内累计（重启清零），与「空间内行动率按运行窗口
 * 统计」口径一致。</p>
 */
@Slf4j
@Service
public class GrowthMetricsService {

    private final AtomicLong spaceAiOk = new AtomicLong();
    private final AtomicLong spaceAiFail = new AtomicLong();
    private final AtomicLong spaceProgrammatic = new AtomicLong();
    private final AtomicLong spaceJsonOk = new AtomicLong();
    private final AtomicLong spaceJsonFail = new AtomicLong();
    private final AtomicLong decisionInSpace = new AtomicLong();
    private final AtomicLong decisionOutside = new AtomicLong();
    private final AtomicLong stageComplianceIntercepts = new AtomicLong();
    private final AtomicLong spaceComplianceIntercepts = new AtomicLong();
    private final AtomicLong knowledgeBoundIntercepts = new AtomicLong();
    private final AtomicLong maskComplianceIntercepts = new AtomicLong();
    private final AtomicLong shiftAssessOk = new AtomicLong();
    private final AtomicLong shiftAssessFail = new AtomicLong();
    private final AtomicLong shiftEntries = new AtomicLong();
    private final AtomicLong maskBreachHits = new AtomicLong();
    private final AtomicLong maskExpressedComputes = new AtomicLong();
    private final Map<String, AtomicLong> triggerCounts = new LinkedHashMap<>();

    // ==================== 记录 ====================

    /** 空间 AI 生成成功。 */
    public void recordSpaceAiOk() {
        spaceAiOk.incrementAndGet();
    }

    /** 空间 AI 生成失败（→ 程序化兜底）。 */
    public void recordSpaceAiFail() {
        spaceAiFail.incrementAndGet();
    }

    /** 空间程序化生成（dormant 档 / 失败兜底）。 */
    public void recordSpaceProgrammatic() {
        spaceProgrammatic.incrementAndGet();
    }

    /** 空间 JSON 解析成功（格式合规率分子）。 */
    public void recordSpaceJsonOk() {
        spaceJsonOk.incrementAndGet();
    }

    /** 空间 JSON 解析失败（格式合规率分母）。 */
    public void recordSpaceJsonFail() {
        spaceJsonFail.incrementAndGet();
    }

    /** 决策落在空间候选内。 */
    public void recordDecisionInSpace() {
        decisionInSpace.incrementAndGet();
    }

    /** 决策落在空间候选外（软引导语义：允许，仅统计）。 */
    public void recordDecisionOutside() {
        decisionOutside.incrementAndGet();
    }

    /** stage_compliance 护栏拦截。 */
    public void recordStageComplianceIntercept() {
        stageComplianceIntercepts.incrementAndGet();
    }

    /** space_compliance 护栏拦截（决策越界）或候选剔除（生成期）。 */
    public void recordSpaceComplianceIntercept() {
        spaceComplianceIntercepts.incrementAndGet();
    }

    /** developmental_knowledge_bound 护栏拦截。 */
    public void recordKnowledgeBoundIntercept() {
        knowledgeBoundIntercepts.incrementAndGet();
    }

    /** mask_compliance 护栏拦截。 */
    public void recordMaskComplianceIntercept() {
        maskComplianceIntercepts.incrementAndGet();
    }

    /** 人格偏移评估成功。 */
    public void recordShiftAssessOk() {
        shiftAssessOk.incrementAndGet();
    }

    /** 人格偏移评估失败。 */
    public void recordShiftAssessFail() {
        shiftAssessFail.incrementAndGet();
    }

    /** 人格偏移条目落账。 */
    public void recordShiftEntry() {
        shiftEntries.incrementAndGet();
    }

    /** 面具破绽命中。 */
    public void recordMaskBreachHit() {
        maskBreachHits.incrementAndGet();
    }

    /** 表达 PAD 计算次数。 */
    public void recordMaskExpressedCompute() {
        maskExpressedComputes.incrementAndGet();
    }

    /** 触发码计数（重算来源分布）。 */
    public void recordTrigger(GrowthSpaceTriggerCode code) {
        triggerCounts.computeIfAbsent(code.name(), k -> new AtomicLong()).incrementAndGet();
    }

    /** 触发码类型（避免与 space 包枚举耦合的轻量枚举镜像）。 */
    public enum GrowthSpaceTriggerCode {
        STAGE_TRANSITION, PHYSIO_BREACH, EMOTION_SHIFT, MAJOR_EVENT, PARTICIPANT_CHANGE,
        GOAL_INVALID, RELATION_JUMP, ENV_SHOCK, LOD_PROMOTE, PERIODIC, MANUAL;

        public static GrowthSpaceTriggerCode of(String code) {
            for (GrowthSpaceTriggerCode t : values()) {
                if (t.name().equalsIgnoreCase(code)) {
                    return t;
                }
            }
            return PERIODIC;
        }
    }

    // ==================== 快照 ====================

    /** 指标快照（调试接口 / 测试断言用）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spaceAiOk", spaceAiOk.get());
        m.put("spaceAiFail", spaceAiFail.get());
        m.put("spaceProgrammatic", spaceProgrammatic.get());
        m.put("spaceJsonOk", spaceJsonOk.get());
        m.put("spaceJsonFail", spaceJsonFail.get());
        long jsonTotal = spaceJsonOk.get() + spaceJsonFail.get();
        m.put("spaceComplianceRate", jsonTotal == 0 ? null : Math.round(spaceJsonOk.get() * 1000.0 / jsonTotal) / 10.0);
        m.put("decisionInSpace", decisionInSpace.get());
        m.put("decisionOutside", decisionOutside.get());
        long decisionTotal = decisionInSpace.get() + decisionOutside.get();
        m.put("inSpaceRate", decisionTotal == 0 ? null
                : Math.round(decisionInSpace.get() * 1000.0 / decisionTotal) / 10.0);
        m.put("decisionTotal", decisionTotal);
        m.put("stageComplianceIntercepts", stageComplianceIntercepts.get());
        m.put("spaceComplianceIntercepts", spaceComplianceIntercepts.get());
        m.put("knowledgeBoundIntercepts", knowledgeBoundIntercepts.get());
        m.put("maskComplianceIntercepts", maskComplianceIntercepts.get());
        m.put("shiftAssessOk", shiftAssessOk.get());
        m.put("shiftAssessFail", shiftAssessFail.get());
        m.put("shiftEntries", shiftEntries.get());
        m.put("maskBreachHits", maskBreachHits.get());
        m.put("maskExpressedComputes", maskExpressedComputes.get());
        Map<String, Long> triggers = new LinkedHashMap<>();
        triggerCounts.forEach((k, v) -> triggers.put(k, v.get()));
        m.put("triggerCounts", triggers);
        return m;
    }
}
