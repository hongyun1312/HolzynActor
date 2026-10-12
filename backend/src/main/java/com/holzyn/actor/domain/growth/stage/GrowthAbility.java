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

package com.holzyn.actor.domain.growth.stage;

/**
 * 阶段能力码枚举。
 * <p>既有 12 码（参考文档）+ 本设计新增 8 码：</p>
 * <ul>
 * <li><b>既有</b>：dialogue_output / abstract_reasoning / long_term_planning / self_identity_reflection /
 * authority_challenge / emotional_regulation / romantic_relationship / marriage / reproduction /
 * caregiving / semantic_knowledge_write / stable_relationship_judgment；</li>
 * <li><b>新增</b>：heavy_labor（重体力）/ long_distance_travel（远行）/ night_watch（守夜）/ combat（争斗）/
 * financial_control（掌财）/ child_rearing（教养）/ skill_acquisition（习新技能）/ legacy_transfer（传承担当）。</li>
 * </ul>
 * <p>能力门控存在 {@code actor_growth_stage_gate} 表（{@code GrowthStageGateService} 表优先 + 代码 seed），
 * 本枚举只承载「码 ↔ 中文/说明」的静态字典，不承载门控结果。</p>
 */
public enum GrowthAbility {

    /** 语言输出（说话/对话） */
    DIALOGUE_OUTPUT("语言输出", "能说话、能参与对话"),
    /** 抽象推理（因果/假设/隐喻） */
    ABSTRACT_REASONING("抽象推理", "能进行因果推断、假设推演、隐喻理解"),
    /** 长程规划（超过 30 天的目标规划） */
    LONG_TERM_PLANNING("长程规划", "能规划 30 天以上的目标并坚持"),
    /** 自我身份反思（「我是谁/我想成为谁」） */
    SELF_IDENTITY_REFLECTION("自我反思", "能反思自身身份与人生意义"),
    /** 权威试探（挑战长辈/上位者） */
    AUTHORITY_CHALLENGE("权威试探", "敢质疑与试探长辈、上位者的决定"),
    /** 情绪调节（抑制冲动、管理情绪表达） */
    EMOTIONAL_REGULATION("情绪调节", "能控制情绪表达、不轻易失态"),
    /** 浪漫关系（对异性/他人萌生爱慕并表达） */
    ROMANTIC_RELATIONSHIP("浪漫关系", "能产生并表达爱慕之情"),
    /** 婚姻（缔结婚约） */
    MARRIAGE("婚姻", "能成婚、维系婚姻"),
    /** 生育（孕育后代） */
    REPRODUCTION("生育", "具备生育能力（生理门控）"),
    /** 照护（照料幼儿/病人/老人） */
    CAREGIVING("照护", "能照料幼儿、病人与老人"),
    /** 语义知识写入（把经历固化为知识记忆） */
    SEMANTIC_KNOWLEDGE_WRITE("知识固化", "能把经历提炼为可复用的知识"),
    /** 稳定关系判断（识别长期可靠/不可靠的关系） */
    STABLE_RELATIONSHIP_JUDGMENT("关系判断", "能判断长期关系的可靠与否"),
    /** 重体力（挑担、垦荒、伐木等） */
    HEAVY_LABOR("重体力", "能承担挑担、垦荒、伐木等重体力活"),
    /** 远行（连夜赶路、出远门） */
    LONG_DISTANCE_TRAVEL("远行", "能独自远行、连夜赶路"),
    /** 守夜（夜间巡逻、值守） */
    NIGHT_WATCH("守夜", "能夜间巡逻、值守、熬夜"),
    /** 争斗（搏斗/自卫/武艺） */
    COMBAT("争斗", "能搏斗、自卫、动武"),
    /** 掌财（管理财产、账目、收支） */
    FINANCIAL_CONTROL("掌财", "能管理财产账目、做收支决策"),
    /** 教养（养育教导子女） */
    CHILD_REARING("教养", "能养育并教导子女"),
    /** 习新技能（从头学习新技能） */
    SKILL_ACQUISITION("习新技能", "能从头学习一项新技能"),
    /** 传承担当（把经验/家业传承给后辈） */
    LEGACY_TRANSFER("传承担当", "能承担把经验家业传给后辈的责任");

    /** 中文名 */
    private final String label;
    /** 一句话说明（门控文案与调试展示用） */
    private final String description;

    GrowthAbility(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * 由能力码解析（大小写不敏感；未知码返回 null——调用方按零阻断降级）。
     *
     * @param code 能力码
     *
     * @return 能力或 null
     */
    public static GrowthAbility of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (GrowthAbility a : values()) {
            if (a.name().equalsIgnoreCase(code.trim())) {
                return a;
            }
        }
        return null;
    }
}
