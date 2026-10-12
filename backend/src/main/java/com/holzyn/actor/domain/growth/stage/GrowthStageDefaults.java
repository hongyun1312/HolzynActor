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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段门控默认数据。
 * <p><b>形态</b>：建表 + 代码 seed——启动时若 {@code actor_growth_stage_gate} /
 * {@code actor_growth_stage_constraint} 为空，由 {@code GrowthGateService} 把本类数据写入；
 * 此后读取=表优先、代码兜底（热更新结果永远优先）。</p>
 * <p><b>能力门控设计口径</b>（对齐设计文档 §4.6 阶段表）：</p>
 * <ul>
 * <li>INFANT：全部禁止（无语言/无行走/无推理）；</li>
 * <li>TODDLER：语言启用（dialogue_output），其余禁止；</li>
 * <li>CHILD：具体推理（无 abstract_reasoning），可学习新技能/知识固化；</li>
 * <li>ADOLESCENT：抽象推理开启 + 权威试探 + 浪漫关系，但<b>无长程规划（时间视野 ≤30 天）</b>、无婚育掌财守夜；</li>
 * <li>YOUTH：全能力（除传承担当）；PRIME/MIDDLE：全能力；</li>
 * <li>ELDER：认知/表达保留，<b>禁止生育/重体力/远行/守夜/争斗/教养</b>；</li>
 * <li>ANCIENT：只保留表达/判断/传承，<b>禁止一切重体力/独行/守夜/掌财/习新技能</b>；</li>
 * <li>DECEASED：全部禁止（退出世界运行）。</li>
 * </ul>
 */
public final class GrowthStageDefaults {

    private GrowthStageDefaults() {
    }

    /**
     * 能力门控默认行（stage → ability → {allowed, promptText}）。
     * <p>规则：先全部禁止（INFANT 语义），再按阶段放开；每阶段只列「相对上一阶段新增的允许项」，
     * seed 时合并成完整清单。</p>
     */
    public static Map<String, Map<String, GateRow>> abilityGates() {
        Map<String, Map<String, GateRow>> m = new LinkedHashMap<>();
        // 每阶段的允许集（只列新增；seed 时把上级阶段集合并继承——成长是累积的，不存在「长大后失去说话能力」）
        m.put("TODDLER", Map.of(
                "DIALOGUE_OUTPUT", GateRow.allow("能用简短的词句说话、表达需求")));
        m.put("CHILD", Map.of(
                "SEMANTIC_KNOWLEDGE_WRITE", GateRow.allow("能把经历学到的知识记住并讲出来"),
                "SKILL_ACQUISITION", GateRow.allow("能学习识字、算数、农活等新技能")));
        m.put("ADOLESCENT", Map.of(
                "ABSTRACT_REASONING", GateRow.allow("能进行因果推断与假设推演"),
                "SELF_IDENTITY_REFLECTION", GateRow.allow("开始思考「我是谁、我想成为什么样的人」"),
                "AUTHORITY_CHALLENGE", GateRow.allow("会质疑、试探长辈与上位者的决定"),
                "EMOTIONAL_REGULATION", GateRow.allow("开始学着控制情绪，但尚不稳定"),
                "ROMANTIC_RELATIONSHIP", GateRow.allow("会对同龄异性萌生爱慕并尝试表达"),
                "CAREGIVING", GateRow.allow("能帮忙照看幼童与病人")));
        m.put("YOUTH", Map.of(
                "LONG_TERM_PLANNING", GateRow.allow("能规划数月至数年的目标并坚持"),
                "MARRIAGE", GateRow.allow("能成婚并维系婚姻"),
                "REPRODUCTION", GateRow.allow("具备生育能力"),
                "HEAVY_LABOR", GateRow.allow("能承担挑担、垦荒、伐木等重体力活"),
                "LONG_DISTANCE_TRAVEL", GateRow.allow("能独自远行、连夜赶路"),
                "NIGHT_WATCH", GateRow.allow("能夜间巡逻、值守"),
                "COMBAT", GateRow.allow("能搏斗、自卫、动武"),
                "FINANCIAL_CONTROL", GateRow.allow("能管理财产账目、做收支决策"),
                "CHILD_REARING", GateRow.allow("能养育并教导子女"),
                "STABLE_RELATIONSHIP_JUDGMENT", GateRow.allow("能判断长期关系的可靠与否")));
        m.put("PRIME", Map.of(
                "LEGACY_TRANSFER", GateRow.allow("能把经验家业传承给后辈")));
        // MIDDLE 相对 PRIME 无新增（全能力保留；体能下降由机能曲线与软约束表达）
        return m;
    }

    /** 门控行（allowed + promptText）。 */
    public record GateRow(boolean allowed, String promptText) {
        public static GateRow allow(String prompt) {
            return new GateRow(true, prompt);
        }

        public static GateRow deny(String prompt) {
            return new GateRow(false, prompt);
        }
    }

    /**
     * 阶段行为约束默认行（stage → list of {kind, content, promptText}）。
     * <p>HARD=绝对禁止（进 forbidden）；SOFT=受限提示（进 Prompt 注入段）。</p>
     */
    public static Map<String, List<ConstraintRow>> constraints() {
        Map<String, List<ConstraintRow>> m = new LinkedHashMap<>();
        m.put("INFANT", List.of(
                ConstraintRow.hard("不会说话、不会走路、不能自主进食固体食物、不能离开照护者独立行动",
                        "你是一名尚在襁褓的婴儿：不会说话、不会走路，只能哭闹、咿呀与伸手要抱，一切行动依赖照护者"),
                ConstraintRow.soft("对陌生人有怯生反应，饿了/困了会哭闹", "困倦与饥饿会直接以哭闹表达")));
        m.put("TODDLER", List.of(
                ConstraintRow.hard("不能独立远行、不能进行重体力、不能长时间独处",
                        "你是一个年幼孩童：不能独自出远门，也不能承担重体力活"),
                ConstraintRow.soft("语言稚嫩简短，自我中心，时间感只有「今天/现在」",
                        "你的话很短很直白，想到什么说什么，还不太理解「以后」")));
        m.put("CHILD", List.of(
                ConstraintRow.hard("不能婚育、不能掌财、不能守夜、不能独自远行",
                        "你是一个学龄孩童：婚育、掌财、守夜、独自远行都不属于你"),
                ConstraintRow.soft("具体推理为主，好动，重视同伴的看法，时间视野约一周",
                        "你想事情很具体，最在意玩伴和小伙伴们怎么看")));
        m.put("ADOLESCENT", List.of(
                ConstraintRow.hard("不能婚育（未及笄/束发）、不能掌财、不能守夜远行、不能承担重体力",
                        "你尚未成年：婚育、掌财、守夜、重体力均不允许"),
                ConstraintRow.soft("情绪起伏大、易冲动、重视同伴评价；时间视野不超过一个月",
                        "你情绪来得快去得也快，会一时冲动，也特别在意同龄人的眼光")));
        m.put("YOUTH", List.of(
                ConstraintRow.soft("体力充沛、敢闯敢冒险，正是闯荡与成家的年纪",
                        "你年轻力壮，精力旺盛，敢想敢干")));
        m.put("PRIME", List.of(
                ConstraintRow.soft("家业责任最重，行事稳重，体力仍保持高位",
                        "你正值壮年，是家里的顶梁柱，说话办事有分量")));
        m.put("MIDDLE", List.of(
                ConstraintRow.soft("体能开始下降，不宜长期高强度劳作；长程规划与经验是强项",
                        "你人到中年：重活开始吃力，但人情世故与长远打算比年轻时更稳")));
        m.put("ELDER", List.of(
                ConstraintRow.hard("不得进行重体力劳作（挑担/垦荒/伐木）、不得连夜赶路远行、不得守夜",
                        "你年事已高：重体力、连夜远行、守夜均在禁止之列"),
                ConstraintRow.soft("感官退化（视物不清/耳背），午间需小憩，把重活交给后辈",
                        "你眼睛花、耳朵背，中午要歇一觉，力气活都交给后生")));
        m.put("ANCIENT", List.of(
                ConstraintRow.hard("不得独自行远路、不得守夜劳作、不得独自涉水攀高、需人照护",
                        "你已届耄耋：不可独行远路、不可守夜劳作、不可涉水攀高，身边需有人照应"),
                ConstraintRow.soft("认知时而恍惚，久坐易乏，宜在村内就近活动",
                        "你有时犯迷糊，坐久了就乏，只能在村里近处走动")));
        m.put("DECEASED", List.of(
                ConstraintRow.hard("已不在人世：不能行动、不能说话、不能参与世界运行",
                        "你已经离世：不再行动、不再说话，只活在他人记忆与编年史中")));
        return m;
    }

    /** 约束行（kind + content + promptText）。 */
    public record ConstraintRow(String kind, String content, String promptText) {
        public static ConstraintRow hard(String content, String prompt) {
            return new ConstraintRow(GrowthStageConstraint.KIND_HARD, content, prompt);
        }

        public static ConstraintRow soft(String content, String prompt) {
            return new ConstraintRow(GrowthStageConstraint.KIND_SOFT, content, prompt);
        }
    }

    /** 由默认数据展开「阶段 → 允许能力码全清单」（成长累积：TODDLER 起逐级叠加）。 */
    public static Map<String, List<String>> allowedAbilityByStage() {
        Map<String, Map<String, GateRow>> gates = abilityGates();
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> acc = new ArrayList<>();
        for (GrowthStage stage : GrowthStage.values()) {
            if (stage == GrowthStage.INFANT) {
                out.put(stage.name(), List.of());
                continue;
            }
            if (stage == GrowthStage.DECEASED) {
                out.put(stage.name(), List.of());
                continue;
            }
            Map<String, GateRow> rows = gates.getOrDefault(stage.name(), Map.of());
            for (String ability : rows.keySet()) {
                if (!acc.contains(ability)) {
                    acc.add(ability);
                }
            }
            out.put(stage.name(), new ArrayList<>(acc));
        }
        return out;
    }
}
