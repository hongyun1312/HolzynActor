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

import java.util.List;

/**
 * 婚恋决策插拔点 · 接口族。
 * <p><b>设计意图</b>：婚育链路中三个「软判定」节点抽象为接口，默认实现走 Qwen（AI），
 * 程序化兜底保底；<b>N 轨道（NanoJev）只需换实现、不重改主流程</b>——与 的
 * {@code MaskContextSource} / {@code MaskBreachDecision} 同构。每个判定输入输出均设计为
 * <b>可序列化为 NanoJev {@code states + questions} 契约</b>（state=情境文本，question=choice/boolean），
 * 见各接口注释。</p>
 */
public final class RomanceDecisions {

    private RomanceDecisions() {
    }

    /** 心动判定输入（RomanceDecisionSource）。 */
    public record AttractionContext(
            Long projectId,
            String npcRef,
            String npcName,
            String partnerRef,
            String partnerName,
            /** 双方互动证据摘要（最近对话/共处/事件，截断文本） */
            String interactionEvidence,
            /** 好感度（-100~100，程序化预筛已保证 ≥ 门槛） */
            int affinity,
            /** 当前世界时刻（游戏秒） */
            Long gameSecond) {
    }

    /** 心动判定输出。 */
    public record AttractionDecision(
            boolean attracted,
            /** 把握度 0~1（AI 置信度或程序化确定性） */
            double confidence,
            String reason,
            
            String source) {
        public static AttractionDecision of(boolean attracted, double confidence, String reason, String source) {
            return new AttractionDecision(attracted, confidence, reason, source);
        }
    }

    /** 求婚判定输入（ProposalDecisionSource）。 */
    public record ProposalContext(
            Long projectId,
            String npcRef,
            String npcName,
            /** 候选对象（恋爱中的 ROMANTIC 对象）ref 列表 */
            List<String> candidateRefs,
            /** 候选对象摘要（姓名/好感/互动概况，截断文本） */
            String candidatesSummary,
            /** 恋爱时长（游戏日） */
            long romanceGameDays,
            Long gameSecond) {
    }

    /** 求婚判定输出。 */
    public record ProposalDecision(
            boolean propose,
            /** 求婚对象 ref（propose=true 时必填） */
            String targetRef,
            String reason,
            String source) {
        public static ProposalDecision of(boolean propose, String targetRef, String reason, String source) {
            return new ProposalDecision(propose, targetRef, reason, source);
        }
    }

    /** 生育意愿判定输入（FertilityDecisionSource）。 */
    public record FertilityContext(
            Long projectId,
            String npcRef,
            String npcName,
            String spouseRef,
            String spouseName,
            /** 婚姻时长（游戏日） */
            long marriageGameDays,
            /** 已有子女数 */
            int existingChildren,
            /** 双方年龄摘要 */
            String ageSummary,
            /** 所在聚落当前人口（人口上限门控用） */
            int population,
            Long gameSecond) {
    }

    /** 生育意愿判定输出。 */
    public record FertilityDecision(
            boolean want,
            String reason,
            String source) {
        public static FertilityDecision of(boolean want, String reason, String source) {
            return new FertilityDecision(want, reason, source);
        }
    }
}
