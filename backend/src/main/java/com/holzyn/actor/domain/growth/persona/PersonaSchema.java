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

package com.holzyn.actor.domain.growth.persona;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色统一档案 v2 · 契约常量与映射表。
 * <p>职责：把 {@code growth/persona_schema_v2.json} 与《角色档案 Schema 契约与分维来源》中的
 * 全部"表结构级知识"集中到一处常量——维键、维标题、分片码、分片标题、维→分片映射、
 * 场景→默认分片组合、默认分维来源声明。渲染器、校验器、落库分派、调试页统一从这里取，
 * <b>禁止在业务代码里散落字符串字面量</b>。</p>
 * <p><b>维（dim）与分片（shard）是两套编号，必须解耦</b>：</p>
 * <ul>
 * <li>维＝数据维度，共 11 个，键名为英文 snake_case；</li>
 * <li>分片＝Prompt 注入切片，共 11 个（S1~S11）+ 全量开关 SALL；</li>
 * <li>映射多对多：① identity → S1 + S11；S2 ← ④ personality + ⑤ values。</li>
 * </ul>
 */
public final class PersonaSchema {

    private PersonaSchema() {
    }

    /** 契约版本（v2；演进只允许增量新增可选字段，旧档案必须持续可读） */
    public static final int SCHEMA_VERSION = 2;

    /** 维键常量（11 个；与 JSON Schema 的 {@code dims} 子键一一对应）。 */
    public interface Dims {
        /** ① 身份与社会 */
        String IDENTITY = "identity";
        /** ② 外貌与体质 */
        String APPEARANCE = "appearance";
        /** ③ 生理基线（权威在 NPC 状态模块） */
        String PHYSIO = "physio";
        /** ④ 气质与人格 */
        String PERSONALITY = "personality";
        /** ⑤ 价值观与动机 */
        String VALUES = "values";
        /** ⑥ 能力与知识 */
        String ABILITY = "ability";
        /** ⑦ 语言与表达 */
        String VOICE = "voice";
        /** ⑧ 习惯与日常 */
        String HABITS = "habits";
        /** ⑨ 经历与创伤 */
        String EXPERIENCE = "experience";
        /** ⑩ 发育·遗传·谱系 */
        String DEVELOPMENT = "development";
        /** ⑪ 社会面具 */
        String MASK = "mask";
    }

    /** 全部维键（顺序＝①②③④⑤⑥⑦⑧⑨⑩⑪；渲染与前端展示统一按此顺序）。 */
    public static final List<String> ALL_DIMS = List.of(
            Dims.IDENTITY, Dims.APPEARANCE, Dims.PHYSIO, Dims.PERSONALITY, Dims.VALUES, Dims.ABILITY,
            Dims.VOICE, Dims.HABITS, Dims.EXPERIENCE, Dims.DEVELOPMENT, Dims.MASK);

    /** 维标题。 */
    public static final Map<String, String> DIM_TITLES = Map.ofEntries(
            Map.entry(Dims.IDENTITY, "身份与社会"),
            Map.entry(Dims.APPEARANCE, "外貌与体质"),
            Map.entry(Dims.PHYSIO, "生理基线"),
            Map.entry(Dims.PERSONALITY, "气质与人格"),
            Map.entry(Dims.VALUES, "价值观与动机"),
            Map.entry(Dims.ABILITY, "能力与知识"),
            Map.entry(Dims.VOICE, "语言与表达"),
            Map.entry(Dims.HABITS, "习惯与日常"),
            Map.entry(Dims.EXPERIENCE, "经历与创伤"),
            Map.entry(Dims.DEVELOPMENT, "发育·遗传·谱系"),
            Map.entry(Dims.MASK, "社会面具"));

    /** 分片码常量。 */
    public interface Shards {
        /** S1 身份锚定（姓名/别名/头衔/身份/当前处境） */
        String S1 = "S1";
        /** S2 人格与价值观 */
        String S2 = "S2";
        /** S3 语言指纹 */
        String S3 = "S3";
        /** S4 身体与生理（③ + ②的体质部分） */
        String S4 = "S4";
        /** S5 能力与知识 */
        String S5 = "S5";
        /** S6 习惯与日常 */
        String S6 = "S6";
        /** S7 经历与创伤 */
        String S7 = "S7";
        /** S8 面具 */
        String S8 = "S8";
        /** S9 遗传谱系 */
        String S9 = "S9";
        /** S10 外貌与体质（②的外貌部分） */
        String S10 = "S10";
        /** S11 社会身份与资源（①的资源部分） */
        String S11 = "S11";
        /** 全量注入（预留开关，默认关；仅调试/验证/单人场景） */
        String SALL = "SALL";
    }

    /** 全部分片码（渲染顺序；SALL 由渲染器展开为 S1~S11）。 */
    public static final List<String> ALL_SHARDS = List.of(
            Shards.S1, Shards.S2, Shards.S3, Shards.S4, Shards.S5, Shards.S6,
            Shards.S7, Shards.S8, Shards.S9, Shards.S10, Shards.S11);

    /** 分片标题。 */
    public static final Map<String, String> SHARD_TITLES = Map.ofEntries(
            Map.entry(Shards.S1, "身份锚定"),
            Map.entry(Shards.S2, "人格与价值观"),
            Map.entry(Shards.S3, "语言指纹"),
            Map.entry(Shards.S4, "身体与生理"),
            Map.entry(Shards.S5, "能力与知识"),
            Map.entry(Shards.S6, "习惯与日常"),
            Map.entry(Shards.S7, "经历与创伤"),
            Map.entry(Shards.S8, "社会面具"),
            Map.entry(Shards.S9, "遗传谱系"),
            Map.entry(Shards.S10, "外貌与体质"),
            Map.entry(Shards.S11, "社会身份与资源"));

    /** 维 → 参与分片。 */
    public static final Map<String, List<String>> DIM_SHARDS = Map.ofEntries(
            Map.entry(Dims.IDENTITY, List.of(Shards.S1, Shards.S11)),
            Map.entry(Dims.APPEARANCE, List.of(Shards.S10, Shards.S4)),
            Map.entry(Dims.PHYSIO, List.of(Shards.S4)),
            Map.entry(Dims.PERSONALITY, List.of(Shards.S2)),
            Map.entry(Dims.VALUES, List.of(Shards.S2)),
            Map.entry(Dims.ABILITY, List.of(Shards.S5)),
            Map.entry(Dims.VOICE, List.of(Shards.S3)),
            Map.entry(Dims.HABITS, List.of(Shards.S6)),
            Map.entry(Dims.EXPERIENCE, List.of(Shards.S7)),
            Map.entry(Dims.DEVELOPMENT, List.of(Shards.S9)),
            Map.entry(Dims.MASK, List.of(Shards.S8)));

    /**
     * 场景 → 默认分片组合。
     * <p>键为 Prompt 场景码；值为该场景默认注入的分片（不含 SALL）。</p>
     */
    public static final Map<String, List<String>> SCENE_SHARDS = Map.ofEntries(
            // 实时决策：身份+人格价值+身体生理+习惯+面具（约 650 token）
            Map.entry("action_gen", List.of(Shards.S1, Shards.S2, Shards.S4, Shards.S6, Shards.S8)),
            // 单聊：身份+人格价值+语言指纹+经历+面具+外貌（约 750 token）
            Map.entry("dialog_system", List.of(Shards.S1, Shards.S2, Shards.S3, Shards.S7, Shards.S8, Shards.S10)),
            // 群聊逐角色回复：身份+人格价值+语言指纹+经历+面具（约 630 token）
            Map.entry("group_reply", List.of(Shards.S1, Shards.S2, Shards.S3, Shards.S7, Shards.S8)),
            // 自主对话：与群聊同（无玩家在场，语言与经历最相关）
            Map.entry("autonomous_reply", List.of(Shards.S1, Shards.S2, Shards.S3, Shards.S7, Shards.S8)),
            // 计划：身份+人格价值+身体生理+能力知识+习惯（约 700 token）
            Map.entry("plan_day", List.of(Shards.S1, Shards.S2, Shards.S4, Shards.S5, Shards.S6)),
            Map.entry("plan_replan", List.of(Shards.S1, Shards.S2, Shards.S4, Shards.S5, Shards.S6)),
            // 可发生空间生成：身份+人格价值+身体生理+能力知识+面具（约 700 token）
            Map.entry("growth_space_generate", List.of(Shards.S1, Shards.S2, Shards.S4, Shards.S5, Shards.S8)),
            // 新生儿建档：遗传谱系为主（约 400 token）
            Map.entry("growth_newborn_persona", List.of(Shards.S9)),
            // 人群归属级批量决策：与实时决策同口径
            Map.entry("npc_crowd_decision", List.of(Shards.S1, Shards.S2, Shards.S4, Shards.S6, Shards.S8)));

    /** 未登记场景的回退分片组合（身份+人格价值；最小可用、信息密度最高）。 */
    public static final List<String> DEFAULT_SCENE_SHARDS = List.of(Shards.S1, Shards.S2);

    /**
     * 分片字符预算。
     * <p>作用：<b>信息密度取最优</b>——分片注入时按字段优先级累加，超出预算即舍弃低优先项，
     * 而不是把整份档案塞进 Prompt（Prompt 工程规范 §4.3 的合规要求，非可选优化）。</p>
     * <p>核对：决策场景 S1+S2+S4+S6+S8 = 129+323+242+194+161 = 1049 字符 ≈ 650 token，与 §5.8 表一致。</p>
     */
    public static final Map<String, Integer> SHARD_CHAR_BUDGET = Map.ofEntries(
            Map.entry(Shards.S1, 129),
            Map.entry(Shards.S2, 323),
            Map.entry(Shards.S3, 161),
            Map.entry(Shards.S4, 242),
            Map.entry(Shards.S5, 242),
            Map.entry(Shards.S6, 194),
            Map.entry(Shards.S7, 242),
            Map.entry(Shards.S8, 161),
            Map.entry(Shards.S9, 194),
            Map.entry(Shards.S10, 194),
            Map.entry(Shards.S11, 194));

    /**
     * 查询分片字符预算（未登记回退 240 字符）。
     *
     * @param shard 分片码
     *
     * @return 字符预算
     */
    public static int charBudgetOf(String shard) {
        return SHARD_CHAR_BUDGET.getOrDefault(shard, 240);
    }

    /** 生理取值的枚举约束（校验器用）。 */
    public static final Set<String> CHRONOTYPES = Set.of("early_bird", "intermediate", "night_owl");
    /** 代谢快慢枚举。 */
    public static final Set<String> METABOLISMS = Set.of("slow", "normal", "fast");
    /** 风险偏好枚举。 */
    public static final Set<String> RISK_PREFERENCES = Set.of("low", "medium", "high");
    /** 面具情境码。 */
    public static final List<String> MASK_CONTEXTS = List.of(
            "before_authority", "before_partner", "before_stranger", "before_peer",
            "before_child", "before_rival", "public_place", "private_place");

    /**
     * 查询某场景的默认分片组合（未登记回退 {@link #DEFAULT_SCENE_SHARDS}）。
     *
     * @param scene Prompt 场景码（如 action_gen）
     *
     * @return 分片码列表（不含 SALL）
     */
    public static List<String> shardsForScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return DEFAULT_SCENE_SHARDS;
        }
        return SCENE_SHARDS.getOrDefault(scene, DEFAULT_SCENE_SHARDS);
    }

    /**
     * 默认分维来源声明（11 键；与基准夹具 {@code persona_sample_shenyanqiu.json} 的 {@code _sources} 一致）。
     * <p>角色定制时可覆盖单条；这是"档案不独立于模块"的制度化表达。</p>
     *
     * @return 维键 → 来源声明（可变 Map，按维键插入顺序）
     */
    public static Map<String, PersonaProfile.PersonaSource> defaultSources() {
        Map<String, PersonaProfile.PersonaSource> m = new LinkedHashMap<>();
        m.put(Dims.IDENTITY, src("MIXED", "actor_profile",
                List.of("aliases", "social_class", "livelihood", "property", "reputation", "status_tags"),
                List.of("name", "gender", "race", "sub_race", "title", "affiliation", "occupation", "location"),
                List.of(Shards.S1, Shards.S11),
                List.of("persona_bootstrap_fill_a", "persona_bootstrap_fill_b", "character_card_gen", "ordinary_npc_gen", "manual"),
                List.of("action_gen", "dialog_system", "group_reply", "plan_day", "sandbox", "unity")));
        m.put(Dims.APPEARANCE, src("PERSONA", "",
                List.of("*"), List.of(),
                List.of(Shards.S10, Shards.S4),
                List.of("persona_bootstrap_fill_a", "persona_bootstrap_fill_b", "character_card_gen", "ordinary_npc_gen", "manual"),
                List.of("dialog_system", "group_reply", "growth_space_generate", "sandbox", "unity")));
        m.put(Dims.PHYSIO, src("MODULE", "actor_state",
                List.of(), List.of("*"),
                List.of(Shards.S4),
                List.of("persona_bootstrap_fill_a", "persona_bootstrap_fill_b", "stage_transition"),
                List.of("NpcStateService.decay", "NpcStateService.urgency", "action_gen", "plan_day", "growth_space_generate")));
        m.put(Dims.PERSONALITY, src("PERSONA", "",
                List.of("*"), List.of(),
                List.of(Shards.S2),
                List.of("persona_bootstrap_fill_a", "character_card_gen", "growth_persona_shift_eval"),
                List.of("action_gen", "dialog_system", "group_reply", "npc_emotion_eval", "actor_memory_core")));
        m.put(Dims.VALUES, src("MIXED", "actor_goal",
                List.of("value_weights", "core_fears", "core_desires", "moral_bottom_lines", "regrets"),
                List.of("life_goals", "current_goals"),
                List.of(Shards.S2),
                List.of("persona_bootstrap_fill_a", "character_card_gen", "growth_persona_shift_eval"),
                List.of("action_gen", "plan_day", "guard(stage_compliance)", "actor_memory_core")));
        m.put(Dims.ABILITY, src("MIXED", "actor_knowledge_doc",
                List.of("skills", "literacy", "languages", "talents", "cognitive_ceiling"),
                List.of("knowledge_domains", "knowledge_blind_spots", "secrets"),
                List.of(Shards.S5),
                List.of("persona_bootstrap_fill_b", "character_card_gen", "ordinary_npc_gen", "learning"),
                List.of("action_gen", "plan_day", "dialog_system", "guard(developmental_knowledge_bound)")));
        m.put(Dims.VOICE, src("PERSONA", "",
                List.of("*"), List.of(),
                List.of(Shards.S3),
                List.of("persona_bootstrap_fill_b", "character_card_gen", "ordinary_npc_gen", "manual"),
                List.of("dialog_system", "group_reply", "autonomous_reply")));
        m.put(Dims.HABITS, src("MIXED", "actor_state",
                List.of("sleep_habits", "quirks", "micro_movements", "food_preferences", "addictions", "ritual_behaviors"),
                List.of("schedule"),
                List.of(Shards.S6),
                List.of("persona_bootstrap_fill_b", "ordinary_npc_gen", "plan_day"),
                List.of("action_gen", "plan_day", "solitude", "micro_motion")));
        m.put(Dims.EXPERIENCE, src("MIXED", "actor_memory",
                List.of("origin", "key_events", "traumas", "honors", "unresolved_knots"),
                List.of("key_events(运行期增量)"),
                List.of(Shards.S7),
                List.of("persona_bootstrap_fill_b", "ordinary_npc_gen", "memory"),
                List.of("dialog_system", "group_reply", "npc_emotion_eval", "growth_mask")));
        // 治理批次 0 · 卡9：⑩ genome/epigenetic_marks 权威在 actor_growth_genome
        // ——由 fieldsPersona 移入 fieldsModule（建档 AI 输出不得死拷贝进 persona_json，detach 同口径剥离）
        m.put(Dims.DEVELOPMENT, src("MIXED", "actor_growth_genome",
                List.of(),
                List.of("genome", "epigenetic_marks", "stage", "stage_entered_game_second", "lineage", "capacity_curve", "death_risk"),
                List.of(Shards.S9),
                List.of("DevelopmentalStageService", "persona_bootstrap_fill_a", "reproduction"),
                List.of("guard(stage_compliance)", "growth_space_generate", "action_gen", "plan_day")));
        m.put(Dims.MASK, src("PERSONA", "actor_state",
                List.of("*"), List.of("expressed_pleasure", "expressed_arousal", "expressed_dominance"),
                List.of(Shards.S8),
                List.of("persona_bootstrap_fill_b", "manual", "growth_mask"),
                List.of("dialog_system", "group_reply", "action_gen", "guard(mask_compliance)")));
        return m;
    }

    /** 来源声明便捷构造（fieldsPersona 在前、fieldsModule 在后，与 {@link #defaultSources()} 调用顺序对应）。 */
    private static PersonaProfile.PersonaSource src(String kind, String module,
                                                   List<String> fieldsPersona, List<String> fieldsModule,
                                                   List<String> shards, List<String> writers, List<String> consumers) {
        PersonaProfile.PersonaSource s = new PersonaProfile.PersonaSource();
        s.setKind(kind);
        s.setModule(module);
        s.setFieldsPersona(fieldsPersona == null ? List.of() : fieldsPersona);
        s.setFieldsModule(fieldsModule == null ? List.of() : fieldsModule);
        s.setShards(shards == null ? List.of() : shards);
        s.setWriters(writers == null ? List.of() : writers);
        s.setConsumers(consumers == null ? List.of() : consumers);
        return s;
    }
}