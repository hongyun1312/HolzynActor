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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色统一档案 v2 · 11 维数据模型。
 * <p>职责：承载 {@code persona_schema_v2.json} 定义的全部 11 个维度（dim）。每个维度一个静态内部类，
 * 字段顺序与 JSON Schema 定义一致（{@link JsonPropertyOrder} 保证序列化稳定，便于前缀缓存与版本对比）。</p>
 * <p><b>关键设计：</b></p>
 * <ul>
 * <li><b>维（data dim）与分片（prompt shard）解耦</b>：11 维是数据维度，S1~S11 是注入切片；
 * 一维可拆多片（schema 中 {@code identity} → S1 + S11），一片可跨维（S2 ← {@code personality} + {@code values}）。</li>
 * <li><b>本模型是「规范视图」而非「存储形态」</b>：AI 建档 / 开发者预设 / 前端展示 / Prompt 渲染
 * 看到的都是完整 11 维；落库时由 PersonaPersistService 按 {@code _sources} 分派——
 * {@code MODULE} 字段写入各模块权威位置（③生理→actor_state 参数、⑤目标→actor_goal、
 * ⑥知识→actor_knowledge_doc、⑧日程→计划模块、⑨经历→记忆/编年史、⑩谱系→actor_relation），
 * 只有 {@code PERSONA} 字段留在 {@code actor_profile.persona_json}。</li>
 * <li><b>来源为 MODULE / DER 的维度允许缺省</b>（如 {@link Physio}、{@link Development} 的多数字段）：
 * 渲染器经 {@link PersonaRenderContext} 从权威模块取数补齐，因此「11 维无空维」的校验点是
 * <b>渲染态</b>而非存储态。</li>
 * <li>未知字段一律忽略（{@link JsonIgnoreProperties}{@code (ignoreUnknown = true)}）：
 * 契约演进只允许增量新增可选字段，旧档案必须持续可读（基线世界档案被冻结，不得被后续轮次作废）。</li>
 * </ul>
 */
public final class PersonaDims {

    private PersonaDims() {
    }

    /** 公共注解组合说明：全部维度类统一 {@code @Data} + 忽略未知字段 + 不输出 null（渲染/序列化零噪音）。 */
    private static final String NOTE = "字段级定义见 backend/src/main/resources/growth/persona_schema_v2.json";

    //
    // ① identity 身份与社会（分片 S1 身份锚定 + S11 社会身份与资源）｜MIXED
    //

    /**
     * ① 身份与社会（分片 S1 + S11）｜来源 MIXED。
     * <p>结构列（姓名/性别/种族/次级种族/头衔/归属/职业/地点）权威在 {@code actor_profile}，
     * 本类只承载无归属字段：别名、阶层、谋生方式、财产、分级声望、身份标签。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"aliases", "identityTitle", "socialClass", "livelihood", "property", "reputation", "statusTags"})
    public static class Identity {
        /** 别名/绰号（如 沈老族长、砚秋公） */
        private List<String> aliases = new ArrayList<>();
        /** 身份头衔（与 actor_profile.title 同义，此处为渲染快照） */
        private String identityTitle;
        /** 阶层（如 村社首领（地方士绅下位））——当前无既有模块口径，落档案 */
        private String socialClass;
        /** 谋生方式（如 族田租入 + 村公所俸粮） */
        private String livelihood;
        /** 财产清单（房/田/粮/钱/传家物） */
        private Property property;
        /** 分级声望：键为圈层（village/neighboring/county…），值为 0-1 或自由描述 */
        private Map<String, Object> reputation = new LinkedHashMap<>();
        /** 身份标签（里正/族长/赐匾…） */
        private List<String> statusTags = new ArrayList<>();
    }

    /** ① 财产（落档案；宏观模块当前只有集体 indicatorsJson，无个体财产口径）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Property {
        /** 房宅描述 */
        private String house;
        /** 水田亩数 */
        private Double landMu;
        /** 存粮（石） */
        private Double grainShi;
        /** 现钱（贯） */
        private Double moneyGuan;
        /** 传家物/珍贵持有物 */
        private List<String> heirlooms = new ArrayList<>();
        /** 备注 */
        private String notes;
    }

    //
    // ② appearance 外貌与体质（分片 S10 外貌 / S4 体质）｜PERSONA（无既有模块）
    //

    /**
     * ② 外貌与体质（S10 + S4）｜来源 PERSONA。
     * <p>唯一"真无归属"的静态维：既有系统仅有 {@code avatar_url}（头像）与 {@code actor_state.health}（实时健康值），
     * 没有任何外貌/体质档案位。实时健康仍归状态模块，本维只承载静态特征与体质基线。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"heightCm", "build", "posture", "faceFeatures", "hair", "attireStyle", "voiceTimbre", "scent",
            "strength", "endurance", "agility", "balance", "senses", "illnesses", "disabilities",
            "constitutionWeaknesses", "appearanceNotes"})
    public static class Appearance {
        /** 身高（cm） */
        private Double heightCm;
        /** 体型（中等偏瘦/魁梧/丰腴…） */
        private String build;
        /** 体态（肩背微驼…） */
        private String posture;
        /** 相貌特征 */
        private String faceFeatures;
        /** 须发 */
        private String hair;
        /** 衣着风格 */
        private String attireStyle;
        /** 嗓音音色（对话与 Unity 语音表现的输入） */
        private String voiceTimbre;
        /** 体味 */
        private String scent;
        /** 力量 0-10 */
        private Integer strength;
        /** 耐力 0-10 */
        private Integer endurance;
        /** 敏捷 0-10 */
        private Integer agility;
        /** 平衡 0-10 */
        private Integer balance;
        /** 五感 0-10 */
        private Senses senses;
        /** 疾病（名/季节/严重度） */
        private List<Illness> illnesses = new ArrayList<>();
        /** 残障 */
        private List<String> disabilities = new ArrayList<>();
        /** 体质弱点 */
        private List<String> constitutionWeaknesses = new ArrayList<>();
        /** 外观补充描述 */
        private String appearanceNotes;
    }

    /** ② 五感。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Senses {
        private Integer vision;
        private Integer hearing;
        private Integer smell;
        private Integer taste;
        private Integer touch;
        /** 充说明（如：老花，近处需眯眼；右耳背） */
        private String notes;
    }

    /** ② 疾病条目。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Illness {
        /** 病名 */
        private String name;
        /** 发作季节/条件（秋冬加重 / 阴雨发作） */
        private String season;
        /** 严重度（轻/中/重） */
        private String severity;
    }

    //
    // ③ physio 生理基线（分片 S4）｜★MODULE：权威在 NPC 状态模块
    //

    /**
     * ③ 生理基线（S4）｜来源 <b>MODULE</b>：权威在 NPC 状态模块。
     * <p><b>本类不落 persona_json</b>：AI 建档产出本结构后，由 PersonaPersistService 写入
     * {@code actor_state} 的 per-NPC 生理参数覆盖（{@code physio_json}）与生效系数；
     * {@code NpcStateService} 的衰减/阈值计算改为「全局默认（NpcStateProperties）× 个体系数」；
     * 档案 ③ 维仅由渲染器从参数投影出描述文本（"不要独立出来"的直接落地）。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"chronotype", "sleepNeedHours", "sleepQuality", "mealCycleHours", "mealsPerDay", "metabolism",
            "painTolerance", "thirstTolerance", "coldTolerance", "heatTolerance", "fertilityState", "libido",
            "alcoholTolerance", "stimulants", "excretionRegularity", "sweatTendency", "physioNotes"})
    public static class Physio {
        /** 作息型：early_bird / intermediate / night_owl（驱动昼夜精力曲线相位） */
        private String chronotype;
        /** 睡眠需求（游戏小时/日；驱动精力恢复阈值） */
        private Double sleepNeedHours;
        /** 睡眠质量描述（浅眠多梦…） */
        private String sleepQuality;
        /** 饥饿周期（游戏小时/餐；驱动 hunger 衰减节律） */
        private Double mealCycleHours;
        /** 每日餐数 */
        private Integer mealsPerDay;
        /** 代谢快慢：slow / normal / fast（驱动 hunger/代谢系数） */
        private String metabolism;
        /** 耐痛阈值 0-10（健康侵蚀与面具破绽判定的输入） */
        private Integer painTolerance;
        /** 耐渴 0-10 */
        private Integer thirstTolerance;
        /** 耐寒 0-10（环境温度对健康的修正） */
        private Integer coldTolerance;
        /** 耐热 0-10 */
        private Integer heatTolerance;
        /** 生育状态 */
        private String fertilityState;
        /** 性/亲密状态 */
        private String libido;
        /** 酒量 */
        private String alcoholTolerance;
        /** 摄入习惯（茶/烟/酒…），键为品类 */
        private Map<String, String> stimulants = new LinkedHashMap<>();
        /** 排泄规律（晨起一次…） */
        private String excretionRegularity;
        /** 出汗倾向 */
        private String sweatTendency;
        /** 生理补充说明 */
        private String physioNotes;
    }

    //
    // ④ personality 气质与人格（分片 S2）｜PERSONA 权威
    //

    /**
     * ④ 气质与人格（S2）｜来源 <b>PERSONA 权威</b>。
     * <p>迁移自 {@code actor_profile_card.persona_json.personality}（OCEAN 1-5 整数 →
     * {@code (v-1)/4} 线性映射为 0-1 浮点）并扩展气质型/依恋/情绪阈值/恢复速率/冲动控制/认知风格/风险偏好/时间取向。
     * {@code actor_memory_core} 的 personality 标准块改为<b>从本维派生</b>（消灭双源）。</p>
     * <p>联网研究支撑：数值化人格 prompt 能让行为可辨识（Big Five 赋值与实测得分相关 r&gt;0.85），
     * 且"简洁 prompt 优于冗长 facet 罗列"——故本维以 0-1 数值为主、自由文本为辅。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"ocean", "temperamentType", "attachmentStyle", "emotionThresholds", "emotionRecovery",
            "impulseControl", "cognitiveStyle", "riskPreference", "timeOrientation", "traitsSummary"})
    public static class Personality {
        /** 大五人格（0-1 浮点） */
        private Ocean ocean;
        /** 气质型（黏液质偏抑郁…） */
        private String temperamentType;
        /** 依恋类型 */
        private String attachmentStyle;
        /** 情绪反应阈值（值越高越不易被激起） */
        private EmotionThresholds emotionThresholds;
        /** 情绪恢复速率（描述 + 半衰期系数）； 情绪衰减消费 */
        private String emotionRecovery;
        /** 冲动控制 0-10 */
        private Integer impulseControl;
        /** 认知风格（场依存/场独立…） */
        private String cognitiveStyle;
        /** 风险偏好：low / medium / high */
        private String riskPreference;
        /** 时间取向（当下/中远期…） */
        private String timeOrientation;
        /** 特质摘要（旧 personality.traits 迁移，自由文本，仅补充表达） */
        private String traitsSummary;
    }

    /** ④ 大五人格（0-1 浮点；旧卡 1-5 整数经 (v-1)/4 映射）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ocean {
        /** 开放性 */
        private Double openness;
        /** 尽责性 */
        private Double conscientiousness;
        /** 外向性 */
        private Double extraversion;
        /** 宜人性 */
        private Double agreeableness;
        /** 神经质 */
        private Double neuroticism;
    }

    /** ④ 情绪反应阈值（0-1，越高越不易被激起）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmotionThresholds {
        private Double anger;
        private Double fear;
        private Double joy;
        private Double sadness;
    }

    //
    // ⑤ values 价值观与动机（分片 S2）｜MIXED：目标 → actor_goal
    //

    /**
     * ⑤ 价值观与动机（S2）｜来源 MIXED。
     * <p>价值权重/核心恐惧欲望/道德底线/遗憾 → 落档案（可量化，硬门控红线条目来源）；
     * {@code lifeGoals} / {@code currentGoals} 的权威在 {@code actor_goal}（本处为预设初值与渲染快照）。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"valueWeights", "coreFears", "coreDesires", "moralBottomLines", "lifeGoals", "currentGoals", "regrets"})
    public static class Values {
        /** 可量化价值权重 0-1（键为世界观相关价值名：宗族/乡邻生计/名节/承诺/秩序/私利/信仰…） */
        private Map<String, Double> valueWeights = new LinkedHashMap<>();
        /** 核心恐惧 */
        private List<String> coreFears = new ArrayList<>();
        /** 核心欲望 */
        private List<String> coreDesires = new ArrayList<>();
        /** 道德底线 */
        private List<String> moralBottomLines = new ArrayList<>();
        /** 长期目标（权威在 actor_goal priority=2） */
        private List<String> lifeGoals = new ArrayList<>();
        /** 中期目标（权威在 actor_goal priority=4） */
        private List<String> currentGoals = new ArrayList<>();
        /** 人生遗憾 */
        private List<String> regrets = new ArrayList<>();
    }

    //
    // ⑥ ability 能力与知识（分片 S5）｜MIXED：知识 → 知识模块
    //

    /**
     * ⑥ 能力与知识（S5）｜来源 MIXED。
     * <p>知识领域/知识盲区/知情秘密 → 权威在 {@code actor_knowledge_doc} + RAG 向量库；
     * 技能熟练度/识字/语言/天赋 → 落档案（预设），运行期增长由 learning 模块回写
     * ；
     * {@code cognitiveCeiling} 由 阶段门控派生（本处仅为 AI 生成意图，非权威）。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"skills", "knowledgeDomains", "knowledgeBlindSpots", "literacy", "languages", "talents",
            "cognitiveCeiling", "selfEfficacy", "selfComment", "secrets"})
    public static class Ability {
        /** 技能熟练度 0-10（键为技能名） */
        private Map<String, Double> skills = new LinkedHashMap<>();
        /** 知识领域（权威在知识模块） */
        private List<String> knowledgeDomains = new ArrayList<>();
        /** 知识盲区 */
        private List<String> knowledgeBlindSpots = new ArrayList<>();
        /** 识字程度 */
        private String literacy;
        /** 语言（名 + 熟练度描述） */
        private List<Language> languages = new ArrayList<>();
        /** 天赋倾向 */
        private List<String> talents = new ArrayList<>();
        /** 认知上限 */
        private Map<String, Object> cognitiveCeiling = new LinkedHashMap<>();
        /**
         * 能力自评（self-efficacy，能力自评卡 P1 ①-2）：角色对自身各领域能力的主观评估——
         * 键为领域（对齐 skills 键名/知识域/生活领域），值 1-5 整数（1=很弱…5=很强）。
         * <p>与 skills 的关系：skills 是"客观熟练度"，selfEfficacy 是"角色自己怎么看自己"——
         * 允许与 skills 有偏差（自信/谦逊/高估/低估），正是真人感来源；随经历/反思动态微调。</p>
         */
        private Map<String, Integer> selfEfficacy = new LinkedHashMap<>();
        /** 自评一句话：角色对自己整体能力的看法（≤40 字；随反思微调） */
        private String selfComment;
        /** 知情秘密（旧知识卡 knowledge.secrets 迁移；<b>默认不参与注入</b>，仅作内部驱动力） */
        private List<String> secrets = new ArrayList<>();
    }

    /** ⑥ 语言条目。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Language {
        /** 语言/方言名 */
        private String name;
        /** 熟练度描述（母语/会听说时夹土音…） */
        private String level;
    }

    //
    // ⑦ voice 语言与表达（分片 S3）｜PERSONA
    //

    /**
     * ⑦ 语言与表达（S3）｜来源 <b>PERSONA</b>（"声音指纹"）。
     * <p>迁移自 {@code persona_json.speechStyle}（language/tone/vocabulary/taboos/catchphrases）并扩展
     * 句长/语速/音高/称呼/自称/方言/沉默倾向/修辞/书面口头差异。纯新增扩展，无模块冲突。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"sentenceLength", "register", "speechRate", "pitch", "addressHabits", "catchphrases", "dialect",
            "tabooTopics", "silenceTendency", "rhetoricPreference", "writtenVsSpoken", "vocabularyKeys"})
    public static class Voice {
        /** 句长倾向（偏长 20-40 字…） */
        private String sentenceLength;
        /** 用词雅俗（半文半白…） */
        private String register;
        /** 语速 */
        private String speechRate;
        /** 音高 */
        private String pitch;
        /** 称呼习惯：键为 to_junior/to_peer/self_reference… */
        private Map<String, String> addressHabits = new LinkedHashMap<>();
        /** 口头禅 */
        private List<String> catchphrases = new ArrayList<>();
        /** 方言腔调 */
        private String dialect;
        /** 忌讳话题 */
        private List<String> tabooTopics = new ArrayList<>();
        /** 沉默倾向 */
        private String silenceTendency;
        /** 修辞偏好 */
        private String rhetoricPreference;
        /** 书面/口头差异 */
        private String writtenVsSpoken;
        /** 特征词表（旧 speechStyle.vocabulary 迁移） */
        private List<String> vocabularyKeys = new ArrayList<>();
    }

    //
    // ⑧ habits 习惯与日常（分片 S6）｜MIXED：日程 → 计划模块
    //

    /**
     * ⑧ 习惯与日常（S6）｜来源 MIXED。
     * <p>日程/作息 → 权威在计划模块（{@code plan_day}/{@code plan_replan}）与 {@code actor_state} 作息状态机
     * 癖好/小动作/饮食偏好/嗜好/仪式行为 → 落档案。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"schedule", "sleepHabits", "quirks", "microMovements", "foodPreferences", "addictions", "ritualBehaviors"})
    public static class Habits {
        /** 典型日程（投影展示；权威在计划模块） */
        private List<String> schedule = new ArrayList<>();
        /** 作息习惯（睡前看族谱…） */
        private List<String> sleepHabits = new ArrayList<>();
        /** 癖好（思考时摩挲印信…） */
        private List<String> quirks = new ArrayList<>();
        /** 小动作（捋须/负手踱步…） */
        private List<String> microMovements = new ArrayList<>();
        /** 饮食偏好 */
        private String foodPreferences;
        /** 嗜好（浓茶日三盏…） */
        private List<String> addictions = new ArrayList<>();
        /** 仪式性行为（初一十五上香…） */
        private List<String> ritualBehaviors = new ArrayList<>();
    }

    //
    // ⑨ experience 经历与创伤（分片 S7）｜MIXED：经历 → 记忆/编年史
    //

    /**
     * ⑨ 经历与创伤（S7）｜来源 MIXED。
     * <p>关键经历 → 初值落档案，运行期增量由记忆模块（{@code actor_memory} L2/L3）与编年史
     * <b>创伤事件 + 触发物 → 落档案</b>：触发物是情绪突变与 面具破绽的<b>确定性输入</b>，
     * 避免 AI 凭空决定"他为什么突然失态"。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"origin", "keyEvents", "traumas", "honors", "unresolvedKnots"})
    public static class Experience {
        /** 出身 */
        private String origin;
        /** 关键经历（带年龄/游戏时刻；source 标记来源） */
        private List<KeyEvent> keyEvents = new ArrayList<>();
        /** 创伤事件 + 触发物 */
        private List<Trauma> traumas = new ArrayList<>();
        /** 荣誉 */
        private List<String> honors = new ArrayList<>();
        /** 未解心结 */
        private List<String> unresolvedKnots = new ArrayList<>();
    }

    /** ⑨ 关键经历条目。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyEvent {
        /** 发生年龄（游戏年） */
        private Integer age;
        /** 发生时刻（游戏秒；运行期增量由记忆/编年史填充） */
        private Long gameSecond;
        /** 事件内容 */
        private String what;
        /** 来源：preset（预设）/ memory（记忆派生）/ chronicle（编年史派生） */
        private String source;
    }

    /** ⑨ 创伤条目（触发物为确定性输入）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trauma {
        /** 创伤事件 */
        private String event;
        /** 发生年龄 */
        private Integer age;
        /** 触发物（孩童咳嗽声/药炉气味…） */
        private List<String> triggers = new ArrayList<>();
    }

    //
    // ⑩ development 发育·遗传·谱系（分片 S9）｜MIXED
    //

    /**
     * ⑩ 发育·遗传·谱系（S9）｜来源 MIXED。
     * <p>阶段/出生时刻 → 权威为 {@code actor_profile.stage}/{@code birth_game_second} 列（由
     * DevelopmentalStageService 推进）；谱系 → 权威在 {@code actor_relation}（扩展 relation_type，
     * 不新建谱系表）；机能曲线/死亡风险 → 程序化派生（DER）；
     * 基因位点/表观修饰 → 落档案。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"stage", "stageEnteredGameSecond", "capacityCurve", "deathRisk", "genome", "epigeneticMarks", "lineage"})
    public static class Development {
        /** 当前阶段码（投影；权威在 actor_profile.stage） */
        private String stage;
        /** 阶段进入时刻（游戏秒；投影） */
        private Long stageEnteredGameSecond;
        /** 身体机能曲线（DER：峰值年龄 + 当前机能比） */
        private CapacityCurve capacityCurve;
        /** 死亡风险（DER：基线年率） */
        private DeathRisk deathRisk;
        /** 基因位点 */
        private Map<String, String> genome = new LinkedHashMap<>();
        /** 表观修饰（孕期父母重大事件印记…） */
        private List<String> epigeneticMarks = new ArrayList<>();
        /** 谱系（投影展示；权威在 actor_relation） */
        private Map<String, Object> lineage = new LinkedHashMap<>();
    }

    /** ⑩ 机能曲线（DER）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityCurve {
        /** 机能峰值年龄（游戏年，通常 25-35） */
        private Double peakAge;
        /** 当前机能比 0-1 */
        private Double currentRatio;
    }

    /** ⑩ 死亡风险（DER）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeathRisk {
        /** 基线年死亡率 */
        private Double baselineAnnual;
    }

    //
    // ⑪ mask 社会面具（分片 S8）｜PERSONA（新维度）
    //

    /**
     * ⑪ 社会面具（S8）｜来源 <b>PERSONA</b>（新维度，无既有归属）。
     * <p>消费方是情绪；
     * 多情境叠加取<b>最大抑制强度</b>；{@code breaches} 是"人会在最不该失态的时候失态"的确定性来源。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"contextSuppression", "allowedExpressions", "forbiddenExpressions", "breaches"})
    public static class Mask {
        /** 情境码 → 抑制强度 0-1（before_authority/before_partner/before_stranger/before_peer/before_child/before_rival/public_place/private_place） */
        private Map<String, Double> contextSuppression = new LinkedHashMap<>();
        /** 各情境允许的表达 */
        private Map<String, List<String>> allowedExpressions = new LinkedHashMap<>();
        /** 各情境禁止的表达 */
        private Map<String, List<String>> forbiddenExpressions = new LinkedHashMap<>();
        /** 面具破绽：触发物 → 抑制强度临时增量（负值=抑制下降） */
        private List<Breach> breaches = new ArrayList<>();
    }

    /** ⑪ 面具破绽条目。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Breach {
        /** 触发物（饮酒/被提及次子…） */
        private String trigger;
        /** 抑制强度增量（-1~1，负值=下降） */
        private Double suppressionDelta;
    }
}