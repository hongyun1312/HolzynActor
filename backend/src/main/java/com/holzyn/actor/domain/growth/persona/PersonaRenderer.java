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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色档案渲染器。
 * <p>职责：把 11 维档案按<b>分片</b>渲染为可直接进 Prompt 的紧凑中文文本，或按<b>维</b>渲染为
 * 前端/调试页展示文本。三种模式：</p>
 * <ul>
 * <li>{@link #renderShards} / {@link #renderShardsForScene}——<b>分片注入</b>（决策/对话/计划等场景的默认路径，
 * 合规要求：Prompt 工程规范 §4.3「信息密度取最优」）；</li>
 * <li>{@link #renderFull}——<b>全量 11 维</b>；</li>
 * <li>{@link #renderDims}——<b>按维渲染</b>（主前端「档案 11 维」只读区块与调试前端逐维对照）。</li>
 * </ul>
 * <p><b>渲染规则</b>：</p>
 * <ol>
 * <li>段标题统一 {@code 【名称】}（维用维标题、分片用分片标题），与 附录 A 形态一致；</li>
 * <li>段内字段以 {@code ｜} 分隔，行内不换行；字段为空则整项跳过（零噪音）；</li>
 * <li>档案内有值优先，缺失回退 {@link PersonaRenderContext} 的模块投影——这是"档案消费既有模块数据"的落点；</li>
 * <li>渲染是<b>确定性</b>的（同一输入逐字节相同），以保障 Prompt 稳定前缀与缓存命中；</li>
 * <li>中文 token 估算按实测标定 {@code 0.62 token/字符}。</li>
 * </ol>
 */
@Component
public class PersonaRenderer {

    /**
     * 中文 token/字符 实测标定系数。
     * <p><b>仅用于与 实测基线对照</b>（如"11 维全量 ≈1930 token""决策场景 ≈650 token"）；
     * 进入系统指标/trace 的口径统一走 {@link com.holzyn.actor.ai.TokenEstimator}
     * （CJK 0.55/字符），避免两套换算造成口径漂移。</p>
     */
    public static final double MEASURED_TOKEN_PER_CHAR = 0.62;

    /** 段标题左括号。 */
    private static final String TITLE_L = "【";
    /** 段标题右括号。 */
    private static final String TITLE_R = "】";
    /** 字段分隔符。 */
    private static final String SEP = "｜";
    /** 列表连接符。 */
    private static final String LIST_SEP = "、";

    /** 面具情境渲染上限（分片模式：只注入此刻相关的少数情境；完整模式不受限）。 */
    private static final int MASK_MAX_CONTEXTS = 3;

    /** 声望圈层码 → 中文。 */
    private static final Map<String, String> REPUTATION_NAMES = Map.of(
            "village", "村内", "neighboring", "邻村", "county", "县衙", "family", "族内", "outside", "外界");

    /** 称呼习惯码 → 中文标签。 */
    private static final Map<String, String> ADDRESS_NAMES = Map.of(
            "to_junior", "对晚辈", "to_peer", "对同辈", "to_senior", "对长辈", "to_child", "对子女",
            "to_stranger", "对陌生人", "to_rival", "对仇怨者", "self_reference", "自称");

    /** 摄入品类码 → 中文。 */
    private static final Map<String, String> STIMULANT_NAMES = Map.of(
            "tea", "茶", "tobacco", "烟", "alcohol", "酒", "coffee", "咖啡", "opium", "阿芙蓉", "betel", "槟榔");

    /** 面具情境码 → 中文标题（渲染与调试页同源）。 */
    private static final Map<String, String> MASK_CONTEXT_NAMES = Map.of(
            "before_authority", "对权威",
            "before_partner", "对伴侣",
            "before_stranger", "对陌生人",
            "before_peer", "对同龄同伴",
            "before_child", "对子女",
            "before_rival", "对仇怨对象",
            "public_place", "公共场合",
            "private_place", "私人场所");

    /** 作息型码 → 中文。 */
    private static final Map<String, String> CHRONOTYPE_NAMES = Map.of(
            "early_bird", "早鸟", "intermediate", "中间型", "night_owl", "夜猫子");

    /** 代谢码 → 中文。 */
    private static final Map<String, String> METABOLISM_NAMES = Map.of(
            "slow", "偏慢", "normal", "中等", "fast", "偏快");

    /** 风险偏好码 → 中文。 */
    private static final Map<String, String> RISK_NAMES = Map.of(
            "low", "低（守成）", "medium", "中", "high", "高（冒进）");

    //
    // 对外入口
    //

    /**
     * 按场景渲染默认分片组合（Prompt 注入的主入口）。
     *
     * @param profile 11 维档案（可缺 MODULE/DER 维）
     *
     * @param ctx 模块投影上下文（可空）
     *
     * @param scene Prompt 场景码（如 action_gen；未登记回退默认组合）
     *
     * @return 分片文本（空档案返回空串）
     */
    public String renderShardsForScene(PersonaProfile profile, PersonaRenderContext ctx, String scene) {
        return renderShards(profile, ctx, PersonaSchema.shardsForScene(scene));
    }

    /**
     * 渲染指定分片集合。
     *
     * @param profile 11 维档案
     *
     * @param ctx 模块投影上下文（可空）
     *
     * @param shards 分片码集合（含 {@code SALL} 时展开为 S1~S11；未知码忽略）
     *
     * @return 分片文本；分片顺序恒定（S1→S11），重复码去重
     */
    public String renderShards(PersonaProfile profile, PersonaRenderContext ctx, Collection<String> shards) {
        if (profile == null || profile.getDims() == null) {
            return "";
        }
        PersonaRenderContext c = ctx == null ? new PersonaRenderContext() : ctx;
        Set<String> wanted = new LinkedHashSet<>();
        if (shards != null) {
            for (String s : shards) {
                if (PersonaSchema.Shards.SALL.equalsIgnoreCase(s)) {
                    wanted.addAll(PersonaSchema.ALL_SHARDS);
                } else if (s != null && PersonaSchema.ALL_SHARDS.contains(s)) {
                    wanted.add(s);
                }
            }
        }
        List<String> blocks = new ArrayList<>();
        for (String shard : PersonaSchema.ALL_SHARDS) {
            if (!wanted.contains(shard)) {
                continue;
            }
            String title = TITLE_L + PersonaSchema.SHARD_TITLES.get(shard) + TITLE_R;
            // 全量注入：不设字符预算（2026-10-10 决策：移除字数硬边界，确保角色信息完整进 AI 请求；
            // 空行照旧跳过零噪音）
            List<String> lines = nonBlank(shardLines(profile, c, shard));
            if (!lines.isEmpty()) {
                blocks.add(title);
                blocks.addAll(lines);
            }
        }
        return String.join("\n", blocks);
    }

    /**
     * 渲染全量 11 维。
     *
     * @param profile 11 维档案
     *
     * @param ctx 模块投影上下文（可空）
     *
     * @return 11 维文本（按 ①→⑪ 顺序；空维整段跳过）
     */
    public String renderFull(PersonaProfile profile, PersonaRenderContext ctx) {
        return renderDims(profile, ctx, PersonaSchema.ALL_DIMS);
    }

    /**
     * 按维渲染（主前端只读展示 / 调试页逐维对照）。
     *
     * @param profile 11 维档案
     *
     * @param ctx 模块投影上下文（可空）
     *
     * @param dimKeys 维键列表（未知键忽略；顺序按入参）
     *
     * @return 维文本
     */
    public String renderDims(PersonaProfile profile, PersonaRenderContext ctx, Collection<String> dimKeys) {
        if (profile == null || profile.getDims() == null || dimKeys == null) {
            return "";
        }
        PersonaRenderContext c = ctx == null ? new PersonaRenderContext() : ctx;
        List<String> blocks = new ArrayList<>();
        for (String dim : dimKeys) {
            // 全量/按维渲染不做预算裁剪（调试与前端展示要完整），但空段必须跳过（零噪音）
            List<String> lines = nonBlank(dimLines(profile, c, dim));
            if (!lines.isEmpty()) {
                blocks.add(TITLE_L + PersonaSchema.DIM_TITLES.getOrDefault(dim, dim) + TITLE_R);
                blocks.addAll(lines);
            }
        }
        return String.join("\n", blocks);
    }

    /**
     * 估算文本 token（统一口径：复用 {@link com.holzyn.actor.ai.TokenEstimator}，
     * CJK 0.55/字符 + ASCII 0.28/字符 + 连续单词开销，禁止另立换算）。
     *
     * @param text 文本（可空）
     *
     * @return 估算 token 数
     */
    public static int estimateTokens(String text) {
        return com.holzyn.actor.ai.TokenEstimator.estimateTokens(text);
    }

    /**
     * 按 实测标定（0.62 token/字符）估算 token，仅用于对照 §3.1 的实测基线
     * （"11 维全量 ≈1930 token""决策场景 ≈650 token"），不进系统指标。
     *
     * @param text 文本（可空）
     *
     * @return 对照用 token 数
     */
    public static int measuredTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.round(text.length() * MEASURED_TOKEN_PER_CHAR);
    }

    /** 渲染量测结果（字符/token/段数；测试与调试页断言用）。 */
    public record Measure(int chars, int tokens, int blocks) {
    }

    /**
     * 量测渲染结果。
     *
     * @param text 渲染文本
     *
     * @return 字符数 / 估算 token / 段数（{@code 【} 计数）
     */
    public static Measure measure(String text) {
        if (text == null || text.isEmpty()) {
            return new Measure(0, 0, 0);
        }
        int blocks = 0;
        int idx = 0;
        while ((idx = text.indexOf(TITLE_L, idx)) >= 0) {
            blocks++;
            idx += TITLE_L.length();
        }
        return new Measure(text.length(), estimateTokens(text), blocks);
    }

    //
    // 分片行组装
    //

    /**
     * 生成某分片的文本行。
     *
     * @param p 档案
     *
     * @param c 投影上下文
     *
     * @param shard 分片码
     *
     * @return 文本行（不含段标题）
     */
    private List<String> shardLines(PersonaProfile p, PersonaRenderContext c, String shard) {
        return switch (shard) {
            // S1 身份锚定：姓名/别名/性别/种族/次级种族/出生/身份/归属/职业/当前处境
            case PersonaSchema.Shards.S1 -> concat(
                    idCoreLines(p, c), idSocialCoreLines(p, c), situationLines(p, c));
            // S2 人格与价值观：④ + ⑤
            case PersonaSchema.Shards.S2 -> concat(
                    personalityLines(p), valuesLines(p, c));
            // S3 语言指纹：⑦ 全部
            case PersonaSchema.Shards.S3 -> voiceLines(p);
            // S4 身体与生理：按"决策相关度"排序——体质 → 作息睡眠 → 饥饿代谢 → 感官 → 疾病 → 体质弱点
            // → 耐受阈值 → 生育 → 摄入排泄（预算不足时从末尾丢弃，而非丢掉关键项）
            case PersonaSchema.Shards.S4 -> concat(
                    bodyAttrLines(p), physioRhythmLines(p, c), physioMetabolismLines(p, c),
                    bodySensesLines(p), bodyIllnessLines(p), bodyWeaknessLines(p),
                    physioToleranceLines(p, c), physioReproLines(p, c), physioHabitLines(p, c));
            // S5 能力与知识：⑥
            case PersonaSchema.Shards.S5 -> abilityLines(p, c);
            // S6 习惯与日常：⑧
            case PersonaSchema.Shards.S6 -> habitsLines(p, c);
            // S7 经历与创伤：创伤（含触发物）优先 → 出身 → 心结与荣耀 → 关键经历（长文本，预算不足先舍）
            case PersonaSchema.Shards.S7 -> concat(
                    experienceTraumaLines(p), experienceOriginLines(p),
                    experienceKnotLines(p), experienceKeyEventLines(p));
            // S8 面具：⑪
            case PersonaSchema.Shards.S8 -> maskLines(p, c);
            // S9 遗传谱系：⑩
            case PersonaSchema.Shards.S9 -> developmentLines(p, c);
            // S10 外貌与体质：②的外貌部分
            case PersonaSchema.Shards.S10 -> appearanceLookLines(p);
            // S11 社会身份与资源：①的资源部分
            case PersonaSchema.Shards.S11 -> idResourceLines(p, c);
            default -> List.of();
        };
    }

    /**
     * 生成某维的完整文本行（维 = 其全部分片行之和，去重；供全量渲染与前端展示）。
     *
     * @param p 档案
     *
     * @param c 投影上下文
     *
     * @param dim 维键
     *
     * @return 文本行（不含段标题）
     */
    private List<String> dimLines(PersonaProfile p, PersonaRenderContext c, String dim) {
        return switch (dim) {
            case PersonaSchema.Dims.IDENTITY -> concat(
                    idCoreLines(p, c), idSocialCoreLines(p, c), idResourceLines(p, c), situationLines(p, c));
            case PersonaSchema.Dims.APPEARANCE -> concat(
                    appearanceLookLines(p), appearanceBodyLines(p));
            case PersonaSchema.Dims.PHYSIO -> physioLines(p, c);
            case PersonaSchema.Dims.PERSONALITY -> personalityLines(p);
            case PersonaSchema.Dims.VALUES -> valuesLines(p, c);
            case PersonaSchema.Dims.ABILITY -> abilityLines(p, c);
            case PersonaSchema.Dims.VOICE -> voiceLines(p);
            case PersonaSchema.Dims.HABITS -> habitsLines(p, c);
            case PersonaSchema.Dims.EXPERIENCE -> experienceLines(p);
            case PersonaSchema.Dims.DEVELOPMENT -> developmentLines(p, c);
            case PersonaSchema.Dims.MASK -> maskLinesAll(p);
            default -> List.of();
        };
    }

    //
    // ① 身份与社会
    //

    /** ① 核心识别行（结构列投影 + 档案别名/头衔）。 */
    private List<String> idCoreLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Identity id = p.getDims().getIdentity();
        List<String> out = new ArrayList<>();
        String name = PersonaRenderContext.firstNonBlank(c.getName(), null);
        String aliases = id == null ? null : join(id.getAliases());
        if (name != null || aliases != null) {
            out.add(line(kv("姓名", name), kv("别名", aliases)));
        }
        String gender = PersonaRenderContext.firstNonBlank(c.getGender(), null);
        String race = PersonaRenderContext.firstNonBlank(c.getRace(), null);
        String subRace = PersonaRenderContext.firstNonBlank(c.getSubRace(), null);
        String raceText = race == null ? null : (subRace == null ? race : race + "·" + subRace);
        if (gender != null || raceText != null) {
            out.add(line(kv("性别", gender), kv("种族", raceText)));
        }
        String birth = PersonaRenderContext.firstNonBlank(c.getBirthLabel(), null);
        String age = c.getAgeYears() == null ? null : c.getAgeYears() + " 岁";
        // 出生标签若已含「岁」则不再重复年龄
        String birthText = birth == null ? (age == null ? null : "今 " + age)
                : (age == null || birth.contains("岁") ? birth : birth + "（今 " + age + "）");
        String title = id != null && id.getIdentityTitle() != null && !id.getIdentityTitle().isBlank()
                ? id.getIdentityTitle() : c.getTitle();
        if (birthText != null || title != null) {
            out.add(line(kv("出生", birthText), kv("身份", title)));
        }
        return out;
    }

    /** ① 社会归属行（阶层/归属/职业/谋生）。 */
    private List<String> idSocialCoreLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Identity id = p.getDims().getIdentity();
        return List.of(line(
                        kv("阶层", id == null ? null : id.getSocialClass()),
                        kv("归属", c.getAffiliation()),
                        kv("职业", c.getOccupation())),
                line(kv("谋生", id == null ? null : id.getLivelihood())));
    }

    /** ① 资源行（财产/声望/身份标签）——S11 与 ① 维共用。 */
    private List<String> idResourceLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Identity id = p.getDims().getIdentity();
        List<String> out = new ArrayList<>();
        if (id != null) {
            out.add(line(kv("财产", propertyText(id.getProperty()))));
            out.add(line(kv("声望", reputationText(id.getReputation()))));
            out.add(line(kv("身份标签", join(id.getStatusTags()))));
        }
        return out;
    }

    /** ① 当前处境行（位置 + 当前行动；S1 专用，体现"此刻在哪、在做什么"）。 */
    private List<String> situationLines(PersonaProfile p, PersonaRenderContext c) {
        return List.of(line(
                kv("当前所在", c.getLocation()),
                kv("当前行动", c.getCurrentActivity())));
    }

    //
    // ② 外貌与体质
    //

    /** ② 外貌行（S10）。 */
    private List<String> appearanceLookLines(PersonaProfile p) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<String> shape = new ArrayList<>();
        if (a.getHeightCm() != null) {
            shape.add("身高 " + num(a.getHeightCm()) + "cm");
        }
        addIfNotBlank(shape, a.getBuild());
        addIfNotBlank(shape, a.getPosture());
        out.add(line(kv("体貌", join(shape))));
        out.add(line(kv("相貌", a.getFaceFeatures())));
        out.add(line(kv("须发", a.getHair()), kv("衣着", a.getAttireStyle())));
        out.add(line(kv("嗓音", a.getVoiceTimbre()), kv("体味", a.getScent())));
        out.add(line(kv("外观补充", a.getAppearanceNotes())));
        return out;
    }

    /** ② 体质核心行（力量/耐力/敏捷/平衡）——S4 首要信息（决策"能不能做"的体质依据）。 */
    private List<String> bodyAttrLines(PersonaProfile p) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null) {
            return List.of();
        }
        List<String> attrs = new ArrayList<>();
        addIfNotNull(attrs, "力量", a.getStrength());
        addIfNotNull(attrs, "耐力", a.getEndurance());
        addIfNotNull(attrs, "敏捷", a.getAgility());
        addIfNotNull(attrs, "平衡", a.getBalance());
        return List.of(line(kv("体质", join(attrs))));
    }

    /** ② 五感行。 */
    private List<String> bodySensesLines(PersonaProfile p) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null || a.getSenses() == null) {
            return List.of();
        }
        PersonaDims.Senses s = a.getSenses();
        List<String> senses = new ArrayList<>();
        addIfNotNull(senses, "视力", s.getVision());
        addIfNotNull(senses, "听力", s.getHearing());
        addIfNotNull(senses, "嗅觉", s.getSmell());
        addIfNotNull(senses, "味觉", s.getTaste());
        addIfNotNull(senses, "触觉", s.getTouch());
        String sensesText = join(senses);
        if (s.getNotes() != null && !s.getNotes().isBlank()) {
            sensesText = sensesText == null ? s.getNotes() : sensesText + "（" + s.getNotes() + "）";
        }
        return List.of(line(kv("感官", sensesText)));
    }

    /** ② 疾病行（S4）。 */
    private List<String> bodyIllnessLines(PersonaProfile p) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null) {
            return List.of();
        }
        return List.of(line(kv("疾病", illnessesText(a.getIllnesses()))));
    }

    /** ② 残障与体质弱点行。 */
    private List<String> bodyWeaknessLines(PersonaProfile p) {
        PersonaDims.Appearance a = p.getDims().getAppearance();
        if (a == null) {
            return List.of();
        }
        return List.of(
                line(kv("残障", join(a.getDisabilities()))),
                line(kv("体质弱点", join(a.getConstitutionWeaknesses()))));
    }

    /** ② 体质行（完整 = 核心 + 感官 + 疾病 + 残障弱点；维渲染用）。 */
    private List<String> appearanceBodyLines(PersonaProfile p) {
        return concat(bodyAttrLines(p), bodySensesLines(p), bodyIllnessLines(p), bodyWeaknessLines(p));
    }

    //
    // ③ 生理基线
    //

    /**
     * 取该 NPC 的生理基线（③ 维）——<b>档案优先，缺失则用状态模块投影</b>。
     * <p>权威在 {@code actor_state.physio_json}（状态模块参数）；档案内通常不含 ③ 维，
     * 因此这里绝大多数情况走投影分支（"生理基线不独立于状态模块"的渲染侧保证）。</p>
     *
     * @param p 档案
     *
     * @param c 渲染上下文（承载状态模块投影）
     *
     * @return 生理基线；两边都没有返回 null（该分片整段跳过）
     */
    private PersonaDims.Physio physioOf(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio fromProfile = p.getDims().getPhysio();
        if (fromProfile != null) {
            return fromProfile;
        }
        return c == null ? null : c.getPhysio();
    }
    /** ③ 作息与睡眠行（S4 高优先：精力/睡眠节律直接决定行为可行性）。 */
    private List<String> physioRhythmLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio ph = physioOf(p, c);
        if (ph == null) {
            return List.of();
        }
        String chrono = ph.getChronotype() == null ? null
                : CHRONOTYPE_NAMES.getOrDefault(ph.getChronotype(), ph.getChronotype());
        String sleep = ph.getSleepNeedHours() == null ? null : num(ph.getSleepNeedHours()) + " 小时/日";
        return List.of(line(kv("作息型", chrono), kv("睡眠需求", sleep), kv("睡眠", ph.getSleepQuality())));
    }

    /** ③ 饥饿周期与代谢行（S4 高优先：驱动 hunger 衰减节律）。 */
    private List<String> physioMetabolismLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio ph = physioOf(p, c);
        if (ph == null) {
            return List.of();
        }
        String mealCycle = ph.getMealCycleHours() == null ? null : "每 " + num(ph.getMealCycleHours()) + " 游戏小时一餐";
        String meals = ph.getMealsPerDay() == null ? null : "日 " + ph.getMealsPerDay() + " 餐";
        String meta = ph.getMetabolism() == null ? null
                : METABOLISM_NAMES.getOrDefault(ph.getMetabolism(), ph.getMetabolism());
        List<String> cycle = new ArrayList<>();
        addIfNotBlank(cycle, mealCycle);
        addIfNotBlank(cycle, meals);
        return List.of(line(kv("饥饿周期", join(cycle)), kv("代谢", meta)));
    }

    /** ③ 耐受阈值行（S4，次级优先）。 */
    private List<String> physioToleranceLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio ph = physioOf(p, c);
        if (ph == null) {
            return List.of();
        }
        List<String> tolerances = new ArrayList<>();
        addIfNotNull(tolerances, "耐痛", ph.getPainTolerance());
        addIfNotNull(tolerances, "耐渴", ph.getThirstTolerance());
        addIfNotNull(tolerances, "耐寒", ph.getColdTolerance());
        addIfNotNull(tolerances, "耐热", ph.getHeatTolerance());
        return List.of(line(kv("耐受阈值", join(tolerances))));
    }

    /** ③ 生育与性行。 */
    private List<String> physioReproLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio ph = physioOf(p, c);
        if (ph == null) {
            return List.of();
        }
        return List.of(line(kv("生育", ph.getFertilityState()), kv("性", ph.getLibido())));
    }

    /** ③ 摄入与排泄行（S4，末级优先）。 */
    private List<String> physioHabitLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Physio ph = physioOf(p, c);
        if (ph == null) {
            return List.of();
        }
        return List.of(
                line(kv("酒量", ph.getAlcoholTolerance()), kv("摄入", mapText(ph.getStimulants(), STIMULANT_NAMES))),
                line(kv("排泄", ph.getExcretionRegularity()), kv("出汗", ph.getSweatTendency())),
                line(kv("生理补充", ph.getPhysioNotes())));
    }

    /** ③ 生理基线行（完整 = 作息 + 代谢 + 耐受 + 生育 + 摄入；维渲染用）。 */
    private List<String> physioLines(PersonaProfile p, PersonaRenderContext c) {
        return concat(physioRhythmLines(p, c), physioMetabolismLines(p, c), physioToleranceLines(p, c),
                physioReproLines(p, c), physioHabitLines(p, c));
    }

    //
    // ④ 气质与人格
    //

    /** ④ 人格行（S2）。 */
    private List<String> personalityLines(PersonaProfile p) {
        PersonaDims.Personality pe = p.getDims().getPersonality();
        if (pe == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        PersonaDims.Ocean o = pe.getOcean();
        if (o != null) {
            out.add(line("OCEAN：" + join(new ArrayList<>(List.of(
                    "开放 " + pct(o.getOpenness()),
                    "尽责 " + pct(o.getConscientiousness()),
                    "外向 " + pct(o.getExtraversion()),
                    "宜人 " + pct(o.getAgreeableness()),
                    "神经质 " + pct(o.getNeuroticism()))))));
        }
        out.add(line(kv("气质型", pe.getTemperamentType()), kv("依恋", pe.getAttachmentStyle())));
        PersonaDims.EmotionThresholds t = pe.getEmotionThresholds();
        if (t != null) {
            out.add(line("情绪阈值：" + join(new ArrayList<>(List.of(
                    "怒 " + pct(t.getAnger()), "惧 " + pct(t.getFear()),
                    "喜 " + pct(t.getJoy()), "悲 " + pct(t.getSadness()))))));
        }
        out.add(line(kv("情绪恢复", pe.getEmotionRecovery())));
        out.add(line(kv("冲动控制", pe.getImpulseControl() == null ? null : pe.getImpulseControl() + "/10"),
                kv("认知风格", pe.getCognitiveStyle())));
        String risk = pe.getRiskPreference() == null ? null
                : RISK_NAMES.getOrDefault(pe.getRiskPreference(), pe.getRiskPreference());
        out.add(line(kv("风险偏好", risk), kv("时间取向", pe.getTimeOrientation())));
        out.add(line(kv("特质摘要", pe.getTraitsSummary())));
        return out;
    }

    //
    // ⑤ 价值观与动机
    //

    /** ⑤ 价值观行（S2；目标取档案预设，缺失回退 actor_goal 投影）。 */
    private List<String> valuesLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Values v = p.getDims().getValues();
        List<String> out = new ArrayList<>();
        if (v != null) {
            out.add(line(kv("价值权重", weightText(v.getValueWeights()))));
            out.add(line(kv("核心恐惧", join(v.getCoreFears()))));
            out.add(line(kv("核心欲望", join(v.getCoreDesires()))));
            out.add(line(kv("道德底线", numbered(v.getMoralBottomLines()))));
        }
        List<String> life = PersonaRenderContext.firstNonEmpty(v == null ? null : v.getLifeGoals(), c.getLifeGoals());
        List<String> current = PersonaRenderContext.firstNonEmpty(v == null ? null : v.getCurrentGoals(), c.getCurrentGoals());
        out.add(line(kv("人生目标", join(life))));
        out.add(line(kv("当前目标", join(current))));
        if (v != null) {
            out.add(line(kv("人生遗憾", join(v.getRegrets()))));
        }
        return out;
    }

    //
    // ⑥ 能力与知识
    //

    /** ⑥ 能力知识行（S5；知识领域/盲区取档案，缺失回退知识模块投影）。 */
    private List<String> abilityLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Ability ab = p.getDims().getAbility();
        List<String> out = new ArrayList<>();
        if (ab != null) {
            out.add(line(kv("技能熟练度", weightText(ab.getSkills()))));
            // 能力自评卡（P1 ①-2）：角色对自身各领域的主观评估（1-5）——与技能同级信息密度
            // （主观评估紧随客观技能，真人感来源）；空自评不输出该行
            String selfEfficacy = selfEfficacyText(ab);
            if (selfEfficacy != null) {
                out.add(line(kv("自评", selfEfficacy)));
            }
        }
        List<String> domains = PersonaRenderContext.firstNonEmpty(ab == null ? null : ab.getKnowledgeDomains(),
                c.getKnowledgeDomains());
        List<String> blind = PersonaRenderContext.firstNonEmpty(ab == null ? null : ab.getKnowledgeBlindSpots(),
                c.getKnowledgeBlindSpots());
        out.add(line(kv("知识领域", join(domains))));
        out.add(line(kv("知识盲区", join(blind))));
        if (ab != null) {
            out.add(line(kv("识字", ab.getLiteracy())));
            out.add(line(kv("语言", languagesText(ab.getLanguages()))));
            out.add(line(kv("天赋倾向", join(ab.getTalents()))));
            out.add(line(kv("认知上限", ceilingText(ab.getCognitiveCeiling()))));
        }
        return out;
    }

    /** 能力自评文本（「领域=3/5、…」+ 整体一句；无自评返回 null）。 */
    private static String selfEfficacyText(PersonaDims.Ability ab) {
        PersonaDims.Ability a = ab;
        java.util.Map<String, Integer> se = a.getSelfEfficacy();
        String comment = a.getSelfComment();
        if ((se == null || se.isEmpty()) && (comment == null || comment.isBlank())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (se != null && !se.isEmpty()) {
            List<String> bits = new ArrayList<>();
            se.forEach((k, v) -> bits.add(k + "=" + v + "/5"));
            sb.append(String.join("、", bits));
        }
        if (comment != null && !comment.isBlank()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("整体：").append(comment.trim());
        }
        return sb.toString();
    }

    //
    // ⑦ 语言与表达
    //

    /** ⑦ 语言指纹行（S3）。 */
    private List<String> voiceLines(PersonaProfile p) {
        PersonaDims.Voice v = p.getDims().getVoice();
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(line(kv("句长", v.getSentenceLength()), kv("用词", v.getRegister())));
        out.add(line(kv("语速", v.getSpeechRate()), kv("音高", v.getPitch())));
        out.add(line(kv("称呼习惯", mapText(v.getAddressHabits(), ADDRESS_NAMES))));
        out.add(line(kv("口头禅", quoted(v.getCatchphrases()))));
        out.add(line(kv("方言腔调", v.getDialect())));
        out.add(line(kv("忌讳话题", join(v.getTabooTopics()))));
        out.add(line(kv("沉默倾向", v.getSilenceTendency())));
        out.add(line(kv("修辞偏好", v.getRhetoricPreference())));
        out.add(line(kv("书面口头差异", v.getWrittenVsSpoken())));
        return out;
    }

    //
    // ⑧ 习惯与日常
    //

    /** ⑧ 习惯行（S6；日程取档案，缺失回退计划模块投影）。 */
    private List<String> habitsLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Habits h = p.getDims().getHabits();
        List<String> out = new ArrayList<>();
        List<String> sched = PersonaRenderContext.firstNonEmpty(h == null ? null : h.getSchedule(), c.getSchedule());
        out.add(line(kv("日程", arrow(sched))));
        if (h != null) {
            out.add(line(kv("作息习惯", join(h.getSleepHabits()))));
            out.add(line(kv("癖好", join(h.getQuirks()))));
            out.add(line(kv("小动作", join(h.getMicroMovements()))));
            out.add(line(kv("饮食偏好", h.getFoodPreferences())));
            out.add(line(kv("嗜好", join(h.getAddictions()))));
            out.add(line(kv("仪式行为", join(h.getRitualBehaviors()))));
        }
        return out;
    }

    //
    // ⑨ 经历与创伤
    //

    /** ⑨ 出身行。 */
    private List<String> experienceOriginLines(PersonaProfile p) {
        PersonaDims.Experience e = p.getDims().getExperience();
        return e == null ? List.of() : List.of(line(kv("出身", e.getOrigin())));
    }

    /** ⑨ 创伤行（S7 最高优先：触发物是情绪突变与面具破绽的确定性输入）。 */
    private List<String> experienceTraumaLines(PersonaProfile p) {
        PersonaDims.Experience e = p.getDims().getExperience();
        return e == null ? List.of() : List.of(line(kv("创伤", traumasText(e.getTraumas()))));
    }

    /** ⑨ 关键经历行（通常较长，分片模式下优先级最低）。 */
    private List<String> experienceKeyEventLines(PersonaProfile p) {
        PersonaDims.Experience e = p.getDims().getExperience();
        return e == null ? List.of() : List.of(line(kv("关键经历", keyEventsText(e.getKeyEvents()))));
    }

    /** ⑨ 荣誉与未解心结行。 */
    private List<String> experienceKnotLines(PersonaProfile p) {
        PersonaDims.Experience e = p.getDims().getExperience();
        if (e == null) {
            return List.of();
        }
        return List.of(
                line(kv("荣誉", join(e.getHonors()))),
                line(kv("未解心结", join(e.getUnresolvedKnots()))));
    }

    /** ⑨ 经历创伤行。 */
    private List<String> experienceLines(PersonaProfile p) {
        return concat(experienceOriginLines(p), experienceKeyEventLines(p),
                experienceTraumaLines(p), experienceKnotLines(p));
    }

    //
    // ⑩ 发育·遗传·谱系
    //

    /** ⑩ 发育谱系行（S9；阶段/谱系取档案，缺失回退主表列与 relation 投影）。 */
    private List<String> developmentLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Development d = p.getDims().getDevelopment();
        List<String> out = new ArrayList<>();
        String stage = d == null ? null : d.getStage();
        // 展示优先取上下文的中文阶段标签（档案内 stage 是阶段码；无上下文时回退阶段码）
        String stageLabel = PersonaRenderContext.firstNonBlank(c.getStageLabel(), stage);
        out.add(line(kv("发育阶段", stageLabel), kv("阶段进入", c.getStageEnteredLabel())));
        Double ratio = d != null && d.getCapacityCurve() != null && d.getCapacityCurve().getCurrentRatio() != null
                ? d.getCapacityCurve().getCurrentRatio()
                : c.getCapacityRatio();
        Double peak = d != null && d.getCapacityCurve() != null && d.getCapacityCurve().getPeakAge() != null
                ? d.getCapacityCurve().getPeakAge()
                : c.getPeakAge();
        if (ratio != null || peak != null) {
            out.add(line("机能曲线：" + join(new ArrayList<>(List.of(
                    peak == null ? "" : "峰值 " + num(peak) + " 岁",
                    ratio == null ? "" : "当前 " + Math.round(ratio * 100) + "%")))));
        }
        Double risk = d != null && d.getDeathRisk() != null && d.getDeathRisk().getBaselineAnnual() != null
                ? d.getDeathRisk().getBaselineAnnual()
                : c.getDeathRiskAnnual();
        out.add(line(kv("死亡风险", risk == null ? null : "基线 " + pct(risk * 100) + "%/年")));
        if (d != null) {
            out.add(line(kv("基因位点", mapText(d.getGenome()))));
            out.add(line(kv("表观修饰", join(d.getEpigeneticMarks()))));
        }
        Map<String, Object> lineage = d != null && d.getLineage() != null && !d.getLineage().isEmpty()
                ? d.getLineage() : c.getLineage();
        out.add(line(kv("谱系", lineageText(lineage))));
        return out;
    }

    //
    // ⑪ 社会面具
    //

    /**
     * ⑪ 面具行（S8）。
     * <p><b>只注入"此刻相关"的情境</b>：优先取上下文给出的 {@code maskContexts}；
     * 上下文为空时退化为"抑制强度最高的 3 个情境 + 破绽"——避免把 8 个情境全塞进 Prompt（信息密度取最优）。</p>
     *
     * @param p 档案
     *
     * @param c 渲染上下文（取 maskContexts）
     *
     * @return 面具文本行
     */
    private List<String> maskLines(PersonaProfile p, PersonaRenderContext c) {
        PersonaDims.Mask m = p.getDims().getMask();
        if (m == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String code : activeMaskContexts(m, c)) {
            out.add(maskContextLine(m, code));
        }
        addBreachLine(m, out);
        return out;
    }

    /**
     * ⑪ 面具行（完整版，8 个情境全渲染；维渲染/前端档案展示用，不做取舍）。
     *
     * @param p 档案
     *
     * @return 面具文本行
     */
    private List<String> maskLinesAll(PersonaProfile p) {
        PersonaDims.Mask m = p.getDims().getMask();
        if (m == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String code : PersonaSchema.MASK_CONTEXTS) {
            out.add(maskContextLine(m, code));
        }
        addBreachLine(m, out);
        return out;
    }

    /** 单个情境的抑制/允许/禁止行；该情境无抑制也无允许禁止清单时返回 null（零噪音）。 */
    private String maskContextLine(PersonaDims.Mask m, String code) {
        Double sup = m.getContextSuppression() == null ? null : m.getContextSuppression().get(code);
        List<String> allow = m.getAllowedExpressions() == null ? null : m.getAllowedExpressions().get(code);
        List<String> forbid = m.getForbiddenExpressions() == null ? null : m.getForbiddenExpressions().get(code);
        boolean noSuppression = sup == null || sup == 0d;
        boolean noLists = (allow == null || allow.isEmpty()) && (forbid == null || forbid.isEmpty());
        if (noSuppression && noLists) {
            // 该情境下"没有面具"（如丧偶者对伴侣）——不产出噪音行
            return null;
        }
        String name = MASK_CONTEXT_NAMES.getOrDefault(code, code);
        return line(kv(name, sup == null ? null : "抑制 " + pct(sup)),
                kv("允许", join(allow)), kv("禁止", join(forbid)));
    }

    /** 面具破绽行（触发物 → 抑制强度临时增量）。 */
    private void addBreachLine(PersonaDims.Mask m, List<String> out) {
        if (m.getBreaches() == null || m.getBreaches().isEmpty()) {
            return;
        }
        List<String> breaches = new ArrayList<>();
        for (PersonaDims.Breach b : m.getBreaches()) {
            if (b == null || b.getTrigger() == null) {
                continue;
            }
            breaches.add(b.getSuppressionDelta() == null ? b.getTrigger()
                    : b.getTrigger() + " 抑制 " + (b.getSuppressionDelta() > 0 ? "+" : "") + pct(b.getSuppressionDelta()));
        }
        out.add(line(kv("面具破绽", join(breaches))));
    }

    /**
     * 选出"此刻相关"的面具情境码。
     *
     * @param m 面具
     *
     * @param c 上下文
     *
     * @return 情境码列表（上下文命中优先，其后按抑制强度降序补足至 3 个）
     */
    private List<String> activeMaskContexts(PersonaDims.Mask m, PersonaRenderContext c) {
        List<String> picked = new ArrayList<>();
        if (c.getMaskContexts() != null) {
            for (String code : c.getMaskContexts()) {
                if (code != null && PersonaSchema.MASK_CONTEXTS.contains(code) && !picked.contains(code)) {
                    picked.add(code);
                }
            }
        }
        if (picked.size() >= MASK_MAX_CONTEXTS) {
            return picked.subList(0, MASK_MAX_CONTEXTS);
        }
        // 未给上下文（或不足）：按抑制强度降序补齐
        List<String> bySuppression = new ArrayList<>(PersonaSchema.MASK_CONTEXTS);
        Map<String, Double> sup = m.getContextSuppression() == null ? Map.of() : m.getContextSuppression();
        bySuppression.sort((a, b) -> Double.compare(sup.getOrDefault(b, -1d), sup.getOrDefault(a, -1d)));
        for (String code : bySuppression) {
            if (picked.size() >= MASK_MAX_CONTEXTS) {
                break;
            }
            if (!picked.contains(code) && sup.containsKey(code)) {
                picked.add(code);
            }
        }
        return picked;
    }

    //
    // 格式化工具
    //

    /** 拼接行列表（过滤空行；两段之间不去重，顺序保持）。 */
    private static List<String> concat(List<String>... groups) {
        List<String> out = new ArrayList<>();
        for (List<String> g : groups) {
            if (g == null) {
                continue;
            }
            for (String s : g) {
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 过滤空行（空段不产出标题——零噪音）。 */
    private static List<String> nonBlank(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String s : lines) {
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 按字符预算裁剪行列表（<b>信息密度取最优</b>的实现）。
     * <p>规则：按入参顺序（＝字段优先级）累加，放得下的收下、放不下的跳过并继续尝试后续更短的行
     * （不是"遇到第一个放不下就停"，这样能在有限预算内塞进更多关键信息）；
     * 若首行本身超预算，则截断该行并结束（保证段不为空）。</p>
     *
     * @param lines 已按优先级排序的行
     *
     * @param budget 字符预算（含段标题占用后的净额）
     *
     * @return 裁剪后的行
     */
    private static List<String> fitToBudget(List<String> lines, int budget) {
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        int used = 0;
        for (String l : lines) {
            if (l == null || l.isBlank()) {
                continue;
            }
            if (out.isEmpty() && l.length() > budget) {
                out.add(l.substring(0, Math.max(1, budget - 1)) + "…");
                return out;
            }
            if (used + l.length() <= budget) {
                out.add(l);
                used += l.length();
            }
        }
        return out;
    }

    /** 行拼接：{@code a｜b｜c}，跳过 null/空。 */
    private static String line(String... parts) {
        List<String> keep = new ArrayList<>();
        for (String s : parts) {
            if (s != null && !s.isBlank()) {
                keep.add(s);
            }
        }
        return String.join(SEP, keep);
    }

    /** 键值对：{@code label：value}；value 空返回 null（由 {@link #line} 跳过）。 */
    private static String kv(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return label + "：" + value.trim();
    }

    /** 列表连接（空返回 null）。 */
    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> keep = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                keep.add(s.trim());
            }
        }
        return keep.isEmpty() ? null : String.join(LIST_SEP, keep);
    }

    /** 有序列表连接（①②③…；用于道德底线等"条款"型字段）。 */
    private static String numbered(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        String[] marks = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (String s : list) {
            if (s == null || s.isBlank()) {
                continue;
            }
            parts.add((i < marks.length ? marks[i] : "·") + " " + s.trim());
            i++;
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** 数值文本（去尾零：32.0 → 32；0.95 → 0.95）。 */
    private static String num(Double v) {
        if (v == null) {
            return null;
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf((long) (double) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** 百分比/权重文本（两位小数；null → "—"占位由调用方跳过）。 */
    private static String pct(Double v) {
        if (v == null) {
            return null;
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 权重表文本：{@code 宗族 0.92、乡邻生计 0.85}（按插入顺序）。 */
    private static String weightText(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            Object v = e.getValue();
            String vs = v instanceof Double d ? num(d) : String.valueOf(v);
            parts.add(e.getKey() + " " + vs);
        }
        return String.join(LIST_SEP, parts);
    }

    /** 通用映射文本：{@code k v、k v}（键经可选映射表转中文，未命中保留原键）。 */
    private static String mapText(Map<String, ?> map) {
        return mapText(map, Map.of());
    }

    /** 通用映射文本（带键名映射表）。 */
    private static String mapText(Map<String, ?> map, Map<String, String> keyNames) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            parts.add(keyNames.getOrDefault(e.getKey(), e.getKey()) + " " + v);
        }
        return parts.isEmpty() ? null : String.join(LIST_SEP, parts);
    }

    /** 财产文本（房/田/粮/钱/传家物）。 */
    private static String propertyText(PersonaDims.Property pr) {
        if (pr == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        addIfNotBlank(parts, pr.getHouse());
        if (pr.getLandMu() != null) {
            parts.add("水田 " + num(pr.getLandMu()) + " 亩");
        }
        if (pr.getGrainShi() != null) {
            parts.add("存粮 " + num(pr.getGrainShi()) + " 石");
        }
        if (pr.getMoneyGuan() != null) {
            parts.add("铜钱 " + num(pr.getMoneyGuan()) + " 贯");
        }
        if (pr.getHeirlooms() != null) {
            parts.addAll(pr.getHeirlooms());
        }
        addIfNotBlank(parts, pr.getNotes());
        return join(parts);
    }

    /** 声望文本（分级；值为数值时保留两位小数；{@code notes} 作为补充说明并入行尾）。 */
    private static String reputationText(Map<String, Object> rep) {
        if (rep == null || rep.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String notes = null;
        for (Map.Entry<String, Object> e : rep.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if ("notes".equals(e.getKey())) {
                notes = String.valueOf(v);
                continue;
            }
            String vs = v instanceof Double d ? pct(d) : String.valueOf(v);
            parts.add(REPUTATION_NAMES.getOrDefault(e.getKey(), e.getKey()) + " " + vs);
        }
        String base = join(parts);
        if (notes != null && !notes.isBlank()) {
            return base == null ? notes : base + "（" + notes + "）";
        }
        return base;
    }

    /** 疾病文本。 */
    private static String illnessesText(List<PersonaDims.Illness> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (PersonaDims.Illness i : list) {
            if (i == null || i.getName() == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder(i.getName());
            if (i.getSeason() != null && !i.getSeason().isBlank()) {
                sb.append("（").append(i.getSeason()).append("）");
            }
            if (i.getSeverity() != null && !i.getSeverity().isBlank()) {
                sb.append("[").append(i.getSeverity()).append("]");
            }
            parts.add(sb.toString());
        }
        return join(parts);
    }

    /** 语言文本。 */
    private static String languagesText(List<PersonaDims.Language> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (PersonaDims.Language l : list) {
            if (l == null || l.getName() == null) {
                continue;
            }
            parts.add(l.getLevel() == null ? l.getName() : l.getName() + "（" + l.getLevel() + "）");
        }
        return join(parts);
    }

    /** 认知上限文本（布尔/描述混合）。 */
    private static String ceilingText(Map<String, Object> ceiling) {
        if (ceiling == null || ceiling.isEmpty()) {
            return null;
        }
        Map<String, String> names = Map.of(
                "abstract_reasoning", "抽象推理",
                "long_term_planning", "长程规划",
                "self_identity_reflection", "自我反思",
                "authority_challenge", "权威试探",
                "emotional_regulation", "情绪调节");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : ceiling.entrySet()) {
            Object v = e.getValue();
            String vs;
            if (v instanceof Boolean b) {
                vs = b ? "允许" : "不允许";
            } else {
                vs = String.valueOf(v);
            }
            parts.add(names.getOrDefault(e.getKey(), e.getKey()) + " " + vs);
        }
        return String.join(LIST_SEP, parts);
    }

    /** 关键经历文本：{@code 19 岁 接掌族中田册；31 岁 主持修东渠}。 */
    private static String keyEventsText(List<PersonaDims.KeyEvent> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (PersonaDims.KeyEvent e : list) {
            if (e == null || e.getWhat() == null) {
                continue;
            }
            String mark = i < 10 ? "①②③④⑤⑥⑦⑧⑨⑩".substring(i, i + 1) : "·";
            String age = e.getAge() == null ? null : e.getAge() + " 岁 ";
            parts.add(mark + " " + (age == null ? "" : age) + e.getWhat());
            i++;
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** 创伤文本：{@code 次子病殁（48 岁）——触发物：孩童咳嗽声、药炉气味}。 */
    private static String traumasText(List<PersonaDims.Trauma> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (PersonaDims.Trauma t : list) {
            if (t == null || t.getEvent() == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder(t.getEvent());
            List<String> trg = t.getTriggers();
            if (trg != null && !trg.isEmpty()) {
                sb.append("——触发物：").append(join(trg));
            }
            parts.add(sb.toString());
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    /** 谱系文本（权威 actor_relation 的投影）。 */
    private static String lineageText(Map<String, Object> lineage) {
        if (lineage == null || lineage.isEmpty()) {
            return null;
        }
        Map<String, String> names = Map.of(
                "father", "父", "mother", "母", "spouse", "配偶",
                "children", "子女", "siblings", "兄弟姐妹", "grandchildren", "孙辈");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : lineage.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            String vs = v instanceof List<?> l ? join(l.stream().map(String::valueOf).toList()) : String.valueOf(v);
            if (vs == null || vs.isBlank()) {
                continue;
            }
            parts.add(names.getOrDefault(e.getKey(), e.getKey()) + " " + vs);
        }
        return parts.isEmpty() ? null : String.join(SEP, parts);
    }

    /** 带引号的列表（口头禅）。 */
    private static String quoted(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                parts.add("「" + s.trim() + "」");
            }
        }
        return parts.isEmpty() ? null : String.join(LIST_SEP, parts);
    }

    /** 箭头序列（日程）：{@code 寅末起身巡田 → 辰时村公所理事 → …}。 */
    private static String arrow(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> keep = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                keep.add(s.trim());
            }
        }
        return keep.isEmpty() ? null : String.join(" → ", keep);
    }

    /** "标签 值"（值非空时）。 */
    private static void addIfNotNull(List<String> target, String label, Object value) {
        if (value == null) {
            return;
        }
        target.add(label + " " + value);
    }

    /** 非空字符串。 */
    private static void addIfNotBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }
}