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

/**
 * 求婚判定插拔点。
 * <p><b>判定语义</b>：处于恋爱期（ROMANTIC 关系持续 ≥ 30 游戏日）的角色，是否向候选对象求婚。</p>
 * SCHEMA_JSON）；AI 失败 → 程序化兜底（恋爱期满且好感最高 → 求婚，确定性 0.7）。</p>
 * <p><b>决策模型 替换契约（N 轨道）</b>：序列化为 choice question——
 * {@code state: "恋爱{romanceGameDays}游戏日，候选：{candidatesSummary}"}，
 * {@code question: {type:"choice", criteria:{propose:"求婚", wait:"再等等", give_up:"结束这段感情"}}}，
 * 输出概率分布选 max。N 轨道只换实现类。</p>
 */
public interface ProposalDecisionSource {

    /** 场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    String SCENE = "growth_proposal_decision";

    /**
     * 判定是否求婚。
     *
     * @param ctx 求婚判定输入（含候选对象摘要与恋爱时长）
     *
     * @return 求婚判定结果；null=无法判定（调用方按「不推进」零阻断处理）
     */
    RomanceDecisions.ProposalDecision decide(RomanceDecisions.ProposalContext ctx);
}
