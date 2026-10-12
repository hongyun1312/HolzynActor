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

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案契约校验器。
 * <p>职责：对 11 维规范视图做<b>结构性校验</b>（维度键、取值范围、枚举、必填核心字段）并输出<b>11 维覆盖率报告</b>。
 * 与 {@code growth/persona_schema_v2.json} 是"双胞胎"：JSON Schema 面向 AI 输出契约与外部工具，
 * 本类面向运行期（零依赖、可单测、可给 AI 输出做降级判定）。</p>
 * <p><b>零阻断原则</b>：校验失败<b>不抛异常、不阻断世界运行</b>——建档流程按"保留已得维度 + 记 partial"
 * 处理（真实度优先于完美数据）；仅把错误交给调用方决定重试或降级。</p>
 * <p><b>覆盖率的三态口径</b>（这是"11 维无空维"验收点的判定方式）：</p>
 * <ul>
 * <li>{@code stored}——档案内已存储该维（PERSONA 维的正常形态）；</li>
 * <li>{@code projected}——档案缺省，但权威模块可投影（MODULE/DER 维的正常形态，如 ③ 生理基线在
 * {@code actor_state}、⑩ 谱系在 {@code actor_relation}）；</li>
 * <li>{@code missing}——两边都没有：<b>真正的空维</b>， 验收不允许。</li>
 * </ul>
 */
@Component
public class PersonaSchemaValidator {

    /** 检查 0-10 量程的字段数上限保护（防止异常数据导致报告过长） */
    private static final int MAX_REPORTED_ERRORS = 50;

    /**
     * 校验档案（不含模块投影；只看档案自身存了什么）。
     *
     * @param profile 11 维档案（可空）
     *
     * @return 校验结果（profile 为空 → 全部维 missing + 一条 error）
     */
    public PersonaValidationResult validate(PersonaProfile profile) {
        return validate(profile, null);
    }

    /**
     * 校验档案并统计覆盖率（投影上下文参与判定 MODULE/DER 维是否"可补齐"）。
     *
     * @param profile 11 维档案（可空）
     *
     * @param ctx 模块投影上下文（可空；为空时 MODULE/DER 维一律按 missing 计）
     *
     * @return 校验结果
     */
    public PersonaValidationResult validate(PersonaProfile profile, PersonaRenderContext ctx) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> coverage = new LinkedHashMap<>();

        if (profile == null) {
            errors.add("档案为空（profile=null）");
            for (String dim : PersonaSchema.ALL_DIMS) {
                coverage.put(dim, PersonaValidationResult.MISSING);
            }
            return new PersonaValidationResult(errors, warnings, coverage);
        }

        // ---------- 顶层契约 ----------
        Integer sv = profile.getSchemaVersion();
        if (sv == null || sv != PersonaSchema.SCHEMA_VERSION) {
            errors.add("schema_version 非法：期望 " + PersonaSchema.SCHEMA_VERSION + "，实际 " + sv);
        }
        Map<String, PersonaProfile.PersonaSource> sources = profile.getSources();
        if (sources == null || sources.isEmpty()) {
            warnings.add("缺少 _sources 分维来源声明（落库分派与写入权限将回退默认声明）");
        } else {
            for (String dim : PersonaSchema.ALL_DIMS) {
                if (!sources.containsKey(dim)) {
                    warnings.add("_sources 缺少维度声明：" + dim);
                }
            }
        }

        // ---------- 各维取值校验 ----------
        validateIdentity(profile, errors, warnings);
        validateAppearance(profile, errors, warnings);
        validatePhysio(profile, errors, warnings);
        validatePersonality(profile, errors, warnings);
        validateValues(profile, errors, warnings);
        validateAbility(profile, errors, warnings);
        validateVoice(profile, errors, warnings);
        validateHabits(profile, errors, warnings);
        validateExperience(profile, errors, warnings);
        validateDevelopment(profile, errors, warnings);
        validateMask(profile, errors, warnings);

        // ---------- 11 维覆盖率（档案存储 ∪ 模块投影）----------
        for (String dim : PersonaSchema.ALL_DIMS) {
            boolean stored = isDimStored(profile, dim);
            boolean projected = !stored && isDimProjectable(ctx, dim);
            coverage.put(dim, stored ? PersonaValidationResult.STORED
                    : (projected ? PersonaValidationResult.PROJECTED : PersonaValidationResult.MISSING));
        }

        if (errors.size() > MAX_REPORTED_ERRORS) {
            errors = new ArrayList<>(errors.subList(0, MAX_REPORTED_ERRORS));
            errors.add("…（错误过多已截断）");
        }
        return new PersonaValidationResult(errors, warnings, coverage);
    }

    //
    // 维覆盖判定
    //

    /**
     * 档案内是否"有内容地"存储了该维（对象非 null 即视为已存储；字段级留空由渲染器跳过，不判空维）。
     *
     * @param profile 档案
     *
     * @param dimKey 维键
     *
     * @return true=已存储
     */
    private boolean isDimStored(PersonaProfile profile, String dimKey) {
        return profile.getDims() != null && profile.getDims().has(dimKey);
    }

    /**
     * 该维能否由既有模块投影补齐（三态判定的关键：MODULE/DER 维"档案缺省"不等于空维）。
     * <p><b>公开口径</b>：AI 补全缺失维 / 建档幂等跳过的"已覆盖"判定复用本方法——
     * 与覆盖率统计的解耦版本（不依赖档案存储态，只看模块能不能投影）。</p>
     *
     * @param ctx 投影上下文（可空）
     *
     * @param dimKey 维键
     *
     * @return true=可投影
     */
    public boolean isDimProjectable(PersonaRenderContext ctx, String dimKey) {
        if (ctx == null) {
            return false;
        }
        return switch (dimKey) {
            // ① 身份与社会：结构列（姓名/性别/种族/头衔/归属/职业/地点）任一存在即可投影
            case PersonaSchema.Dims.IDENTITY -> notBlank(ctx.getName()) || notBlank(ctx.getTitle())
                    || notBlank(ctx.getAffiliation()) || notBlank(ctx.getOccupation());
            // ② 外貌与体质：无既有模块，不可投影（必须落档案）
            case PersonaSchema.Dims.APPEARANCE -> false;
            // ③ 生理基线：权威在 NPC 状态模块（PersonaProjectionService 读 actor_state.physio_json 投影）
            case PersonaSchema.Dims.PHYSIO -> ctx.getPhysio() != null;
            // ④ 气质与人格：无既有模块（旧角色卡已废弃），必须落档案
            case PersonaSchema.Dims.PERSONALITY -> false;
            // ⑤ 价值观与动机：actor_goal 投影
            case PersonaSchema.Dims.VALUES -> notEmpty(ctx.getLifeGoals()) || notEmpty(ctx.getCurrentGoals());
            // ⑥ 能力与知识：actor_knowledge_doc 投影
            case PersonaSchema.Dims.ABILITY -> notEmpty(ctx.getKnowledgeDomains())
                    || notEmpty(ctx.getKnowledgeBlindSpots());
            // ⑦ 语言与表达：无既有模块，必须落档案
            case PersonaSchema.Dims.VOICE -> false;
            // ⑧ 习惯与日常：计划模块投影
            case PersonaSchema.Dims.HABITS -> notEmpty(ctx.getSchedule());
            // ⑨ 经历与创伤：记忆/编年史投影（阶段②接入）
            case PersonaSchema.Dims.EXPERIENCE -> false;
            // ⑩ 发育·遗传·谱系：主表列 + actor_relation 投影
            case PersonaSchema.Dims.DEVELOPMENT -> notBlank(ctx.getStageLabel()) || notBlank(ctx.getBirthLabel())
                    || (ctx.getLineage() != null && !ctx.getLineage().isEmpty());
            // ⑪ 社会面具：无既有模块（表达 PAD 是消费方而非来源），必须落档案
            case PersonaSchema.Dims.MASK -> false;
            default -> false;
        };
    }

    //
    // 各维字段级校验
    //

    /** ① 身份与社会：无强约束，仅检查声望取值域（数值须 0-1）。 */
    private void validateIdentity(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Identity id = p.getDims().getIdentity();
        if (id == null) {
            return;
        }
        if (id.getReputation() != null) {
            id.getReputation().forEach((k, v) -> {
                if (v instanceof Number n && (n.doubleValue() < 0 || n.doubleValue() > 1)) {
                    warnings.add("identity.reputation." + k + " 超出 0-1：" + n);
                }
            });
        }
        if (id.getProperty() != null) {
            checkNonNegative(errors, "identity.property.landMu", id.getProperty().getLandMu());
            checkNonNegative(errors, "identity.property.grainShi", id.getProperty().getGrainShi());
            checkNonNegative(errors, "identity.property.moneyGuan", id.getProperty().getMoneyGuan());
        }
    }

    /** ② 外貌与体质：体质/五感 0-10；身高为正。 */
    private void validateAppearance(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null) {
            return;
        }
        checkRange(errors, "appearance.strength", a.getStrength(), 0, 10);
        checkRange(errors, "appearance.endurance", a.getEndurance(), 0, 10);
        checkRange(errors, "appearance.agility", a.getAgility(), 0, 10);
        checkRange(errors, "appearance.balance", a.getBalance(), 0, 10);
        if (a.getHeightCm() != null && (a.getHeightCm() <= 0 || a.getHeightCm() > 300)) {
            errors.add("appearance.heightCm 超出合理范围（0-300）：" + a.getHeightCm());
        }
        PersonaDims.Senses s = a.getSenses();
        if (s != null) {
            checkRange(errors, "appearance.senses.vision", s.getVision(), 0, 10);
            checkRange(errors, "appearance.senses.hearing", s.getHearing(), 0, 10);
            checkRange(errors, "appearance.senses.smell", s.getSmell(), 0, 10);
            checkRange(errors, "appearance.senses.taste", s.getTaste(), 0, 10);
            checkRange(errors, "appearance.senses.touch", s.getTouch(), 0, 10);
        }
    }

    /** ③ 生理基线：枚举 + 0-10 耐受阈值 + 睡眠/餐数合理域。 */
    private void validatePhysio(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Physio ph = p.getDims().getPhysio();
        if (ph == null) {
            return;
        }
        checkEnum(warnings, "physio.chronotype", ph.getChronotype(), PersonaSchema.CHRONOTYPES);
        checkEnum(warnings, "physio.metabolism", ph.getMetabolism(), PersonaSchema.METABOLISMS);
        checkRange(errors, "physio.painTolerance", ph.getPainTolerance(), 0, 10);
        checkRange(errors, "physio.thirstTolerance", ph.getThirstTolerance(), 0, 10);
        checkRange(errors, "physio.coldTolerance", ph.getColdTolerance(), 0, 10);
        checkRange(errors, "physio.heatTolerance", ph.getHeatTolerance(), 0, 10);
        if (ph.getSleepNeedHours() != null && (ph.getSleepNeedHours() < 0 || ph.getSleepNeedHours() > 24)) {
            errors.add("physio.sleepNeedHours 超出 0-24：" + ph.getSleepNeedHours());
        }
        if (ph.getMealsPerDay() != null && (ph.getMealsPerDay() < 0 || ph.getMealsPerDay() > 10)) {
            warnings.add("physio.mealsPerDay 异常（0-10 之外）：" + ph.getMealsPerDay());
        }
    }

    /** ④ 气质与人格：OCEAN 五维必填且 0-1；其它 0-1 / 0-10 / 枚举。 */
    private void validatePersonality(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Personality pe = p.getDims().getPersonality();
        if (pe == null) {
            return;
        }
        PersonaDims.Ocean o = pe.getOcean();
        if (o == null) {
            errors.add("personality.ocean 缺失（OCEAN 五维为必需）");
        } else {
            checkUnit(errors, "personality.ocean.openness", o.getOpenness());
            checkUnit(errors, "personality.ocean.conscientiousness", o.getConscientiousness());
            checkUnit(errors, "personality.ocean.extraversion", o.getExtraversion());
            checkUnit(errors, "personality.ocean.agreeableness", o.getAgreeableness());
            checkUnit(errors, "personality.ocean.neuroticism", o.getNeuroticism());
        }
        PersonaDims.EmotionThresholds t = pe.getEmotionThresholds();
        if (t != null) {
            checkUnit(errors, "personality.emotionThresholds.anger", t.getAnger());
            checkUnit(errors, "personality.emotionThresholds.fear", t.getFear());
            checkUnit(errors, "personality.emotionThresholds.joy", t.getJoy());
            checkUnit(errors, "personality.emotionThresholds.sadness", t.getSadness());
        }
        checkRange(errors, "personality.impulseControl", pe.getImpulseControl(), 0, 10);
        checkEnum(warnings, "personality.riskPreference", pe.getRiskPreference(), PersonaSchema.RISK_PREFERENCES);
    }

    /** ⑤ 价值观与动机：价值权重 0-1。 */
    private void validateValues(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Values v = p.getDims().getValues();
        if (v == null || v.getValueWeights() == null) {
            return;
        }
        v.getValueWeights().forEach((k, val) -> checkUnit(errors, "values.valueWeights." + k, val));
    }

    /** ⑥ 能力与知识：技能 0-10；能力自评 1-5。 */
    private void validateAbility(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Ability ab = p.getDims().getAbility();
        if (ab == null) {
            return;
        }
        if (ab.getSkills() != null) {
            ab.getSkills().forEach((k, val) -> checkRange(errors, "ability.skills." + k, val, 0, 10));
        }
        if (ab.getSelfEfficacy() != null) {
            ab.getSelfEfficacy().forEach((k, val) -> checkRange(errors, "ability.selfEfficacy." + k, val, 1, 5));
        }
    }

    /** ⑦ 语言与表达：无强约束（文本维）。 */
    private void validateVoice(PersonaProfile p, List<String> errors, List<String> warnings) {
        // 文本维无取值域约束；保留方法位以便后续扩展（如句长区间解析校验）
    }

    /** ⑧ 习惯与日常：无强约束（文本维）。 */
    private void validateHabits(PersonaProfile p, List<String> errors, List<String> warnings) {
        // 同上
    }

    /** ⑨ 经历与创伤：年龄非负；触发物存在时必须有创伤事件。 */
    private void validateExperience(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Experience e = p.getDims().getExperience();
        if (e == null) {
            return;
        }
        if (e.getKeyEvents() != null) {
            for (PersonaDims.KeyEvent ke : e.getKeyEvents()) {
                if (ke != null && ke.getAge() != null && ke.getAge() < 0) {
                    errors.add("experience.keyEvents.age 为负：" + ke.getAge());
                }
            }
        }
        if (e.getTraumas() != null) {
            for (PersonaDims.Trauma t : e.getTraumas()) {
                if (t == null) {
                    continue;
                }
                if (t.getEvent() == null || t.getEvent().isBlank()) {
                    errors.add("experience.traumas 缺少 event");
                }
                if (t.getTriggers() != null && !t.getTriggers().isEmpty() && (t.getEvent() == null || t.getEvent().isBlank())) {
                    errors.add("experience.traumas 有触发物但无创伤事件（触发物必须可追溯到事件）");
                }
            }
        }
    }

    /** ⑩ 发育·遗传·谱系：机能比 0-1；死亡风险 0-1。 */
    private void validateDevelopment(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Development d = p.getDims().getDevelopment();
        if (d == null) {
            return;
        }
        if (d.getCapacityCurve() != null) {
            checkUnit(errors, "development.capacityCurve.currentRatio", d.getCapacityCurve().getCurrentRatio());
        }
        if (d.getDeathRisk() != null) {
            checkUnit(errors, "development.deathRisk.baselineAnnual", d.getDeathRisk().getBaselineAnnual());
        }
    }

    /** ⑪ 社会面具：情境码合法 + 抑制 0-1 + 破绽增量 -1~1。 */
    private void validateMask(PersonaProfile p, List<String> errors, List<String> warnings) {
        PersonaDims.Mask m = p.getDims().getMask();
        if (m == null) {
            return;
        }
        if (m.getContextSuppression() != null) {
            m.getContextSuppression().forEach((k, v) -> {
                if (!PersonaSchema.MASK_CONTEXTS.contains(k)) {
                    warnings.add("mask.contextSuppression 未知情境码：" + k);
                }
                checkUnit(errors, "mask.contextSuppression." + k, v);
            });
        }
        if (m.getBreaches() != null) {
            for (PersonaDims.Breach b : m.getBreaches()) {
                if (b == null) {
                    continue;
                }
                if (b.getTrigger() == null || b.getTrigger().isBlank()) {
                    errors.add("mask.breaches 缺少 trigger");
                }
                Double d = b.getSuppressionDelta();
                if (d != null && (d < -1 || d > 1)) {
                    errors.add("mask.breaches.suppressionDelta 超出 -1~1：" + d);
                }
            }
        }
    }

    //
    // 断言工具
    //

    /** 0-1 区间校验（null 跳过）。 */
    private void checkUnit(List<String> errors, String field, Double v) {
        if (v != null && (v < 0 || v > 1)) {
            errors.add(field + " 超出 0-1：" + v);
        }
    }

    /** 区间校验（null 跳过）。 */
    private void checkRange(List<String> errors, String field, Double v, double min, double max) {
        if (v != null && (v < min || v > max)) {
            errors.add(field + " 超出 " + min + "-" + max + "：" + v);
        }
    }

    /** 区间校验（整数版）。 */
    private void checkRange(List<String> errors, String field, Integer v, double min, double max) {
        if (v != null) {
            checkRange(errors, field, v.doubleValue(), min, max);
        }
    }

    /** 非负校验。 */
    private void checkNonNegative(List<String> errors, String field, Double v) {
        if (v != null && v < 0) {
            errors.add(field + " 为负：" + v);
        }
    }

    /** 枚举校验（非法记告警，不阻断）。 */
    private void checkEnum(List<String> warnings, String field, String v, java.util.Set<String> allowed) {
        if (v != null && !v.isBlank() && !allowed.contains(v)) {
            warnings.add(field + " 非枚举值（" + allowed + "）：" + v);
        }
    }

    /** 非空字符串。 */
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 非空列表。 */
    private boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}