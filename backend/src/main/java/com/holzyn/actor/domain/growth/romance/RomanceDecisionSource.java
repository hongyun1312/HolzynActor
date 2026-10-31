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
 * 心动判定插拔点。
 * <p><b>判定语义</b>：一个未婚适龄角色对某个候选对象（同村、异性、适龄、无血亲、无婚姻边、
 * 好感已程序化预筛达标）是否「动心」——产生浪漫兴趣。</p>
 * SCHEMA_JSON）；AI 失败/禁用 → 程序化兜底（好感 ≥ 门槛 → 动心，确定性 0.6）。</p>
 * <p><b>决策模型 替换契约（N 轨道）</b>：本判定可序列化为
 * {@code 决策模型 evaluate 端点} 的单个 boolean question——
 * {@code state: "角色{npcName}与{partnerName}同村，好感{affinity}，最近{interactionEvidence}"}，
 * {@code question: {type:"boolean", instructions:"{npcName}是否对{partnerName}产生浪漫兴趣？"}}，
 * 输出概率分布 P(attracted=true) ≥ 阈值 → 动心。N 轨道只换实现类，主流程零改动。</p>
 */
public interface RomanceDecisionSource {

    /** 场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    String SCENE = "growth_romance_decision";

    /**
     * 判定是否心动。
     *
     * @param ctx 心动判定输入（含互动证据与好感）
     *
     * @return 心动判定结果；null=无法判定（调用方按「不动心」零阻断处理）
     */
    RomanceDecisions.AttractionDecision decide(RomanceDecisions.AttractionContext ctx);
}
