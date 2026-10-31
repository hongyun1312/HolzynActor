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
 * 生育意愿判定插拔点。
 * <p><b>判定语义</b>：已婚适龄角色（YOUTH~MIDDLE、生育冷却已过、人口未达上限）是否想要孩子。</p>
 * SCHEMA_JSON）；AI 失败 → 程序化兜底（适龄 + 冷却过 + 人口未满 → 想要，确定性 0.7）。</p>
 * <p><b>决策模型 替换契约（N 轨道）</b>：序列化为 score/boolean question——
 * {@code state: "已婚{marriageGameDays}游戏日，已有子女{existingChildren}，{ageSummary}，聚落人口{population}"}，
 * {@code question: {type:"score", instructions:"{npcName}现在想要孩子的意愿（0-1）"}}，
 * 输出期望分数 ≥ 阈值 → 想要。N 轨道只换实现类。</p>
 */
public interface FertilityDecisionSource {

    /** 场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    String SCENE = "growth_fertility_decision";

    /**
     * 判定生育意愿。
     *
     * @param ctx 生育意愿判定输入
     *
     * @return 生育意愿结果；null=无法判定（调用方按「不想要」零阻断处理）
     */
    RomanceDecisions.FertilityDecision decide(RomanceDecisions.FertilityContext ctx);
}
