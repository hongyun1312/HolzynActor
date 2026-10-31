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

package com.holzyn.actor.domain.world.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 世界程度：控制这个世界的「能力上限」与「设定边界」。
 *
 * <p>要解决的问题：度假小岛生活的世界里，NPC 突然为了「宗门大比」互相算计、宏观层开始推演
 * 势力吞并与赋税——内容本身没错，但和这个世界的设定彻底冲突。此前项目里只有一个
 * {@code AiIntensityGate}（只管 AI 调用密度：programmatic / light / full / max），
 * <b>没有任何东西表达「这个世界该有多严肃」</b>。</p>
 *
 * <p>四档与边界（「标准」= 本轮改造前的行为，保证零回归）：</p>
 * <ul>
 *   <li><b>休闲</b>：不涉势力斗争/战争/暗杀/背叛/政治倾轧，不做宏观社会推演，
 *       冲突只停在个人误会与生活琐事，NPC 不设宏观目标；</li>
 *   <li><b>轻度</b>：允许人情往来、邻里争执、门第差异等小规模摩擦，
 *       宏观只保留文化/人口两个轻引擎，不出现战争与政权更替；</li>
 *   <li><b>标准</b>：按题材本身的世界尺度正常推进（今天的行为）；</li>
 *   <li><b>硬核</b>：势力博弈、战争、灾变、政权更替与宏观推演全开，NPC 可有势力与天下级目标。</li>
 * </ul>
 *
 * <p>落地方式（硬开关 + 提示词边界，两条腿）：{@link #macroSchedule()} 决定宏观调度器还要不要跑
 * （见 MacroScheduler#tickIfDue）、{@link #boundary()} 作为「世界程度边界」段注入到各场景提示词
 * （见 AiContextInjector#worldBrief）。</p>
 *
 * <p>所属模块：domain/world（世界设定分类）</p>
 */
public enum WorldTone {

    /** 休闲：生活向，不要斗争与宏观调控 */
    CASUAL("casual", "休闲", MacroSchedule.NONE, false, false),

    /** 轻度：有小摩擦，无战争与政权更替 */
    LIGHT("light", "轻度", MacroSchedule.CULTURE_ONLY, false, false),

    /** 标准：与改造前完全一致 */
    STANDARD("standard", "标准", MacroSchedule.FULL, true, true),

    /** 硬核：冲突与宏观全开 */
    HARD("hard", "硬核", MacroSchedule.FULL, true, true);

    /** 宏观社会演化的调度强度 */
    public enum MacroSchedule {
        /** 完全不调度（不建快照、不产宏观事件、不注入宏观上下文） */
        NONE,
        /** 只跑文化与人口这类轻引擎（日均/月度），跳过势力与战争 */
        CULTURE_ONLY,
        /** 全量（每日 / 每旬势力 / 每月文化科技） */
        FULL
    }

    private final String code;

    private final String label;

    private final MacroSchedule macroSchedule;

    /** 是否允许「势力斗争 / 战争 / 暗杀 / 背叛」这类现实冲突 */
    private final boolean conflictAllowed;

    /** 是否允许「宏观世界调控」（势力博弈、经济政治推演、大规模事件） */
    private final boolean macroGovernanceAllowed;

    WorldTone(String code, String label, MacroSchedule macroSchedule,
              boolean conflictAllowed, boolean macroGovernanceAllowed) {
        this.code = code;
        this.label = label;
        this.macroSchedule = macroSchedule;
        this.conflictAllowed = conflictAllowed;
        this.macroGovernanceAllowed = macroGovernanceAllowed;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public MacroSchedule macroSchedule() {
        return macroSchedule;
    }

    public boolean conflictAllowed() {
        return conflictAllowed;
    }

    public boolean macroGovernanceAllowed() {
        return macroGovernanceAllowed;
    }

    /** 是否完全不跑宏观层（真·不调度，而不是「跑了但少说点」） */
    public boolean macroDisabled() {
        return macroSchedule == MacroSchedule.NONE;
    }

    /** 是否跳过「势力/战争」这条链（休闲与轻度都跳过） */
    public boolean factionDisabled() {
        return macroSchedule != MacroSchedule.FULL || !conflictAllowed;
    }

    /** 初始化时是否跳过「宏观社会模板」这一步（休闲/轻度 → 标已跳过而非失败） */
    public boolean skipsMacroTemplate() {
        return macroSchedule != MacroSchedule.FULL;
    }

    /** 前端下拉用的 {code,label,boundary} 列表 */
    public static List<String> labels() {
        List<String> out = new ArrayList<>(values().length);
        for (WorldTone t : values()) {
            out.add(t.label);
        }
        return List.copyOf(out);
    }

    /** 按编码查找（缺省/非法一律落 {@link #STANDARD}，避免历史数据把世界判成休闲） */
    public static WorldTone ofCode(String code) {
        if (code != null && !code.isBlank()) {
            String t = code.trim();
            for (WorldTone v : values()) {
                if (v.code.equalsIgnoreCase(t) || v.label.equals(t)) {
                    return v;
                }
            }
        }
        return STANDARD;
    }

    /** 同 {@link #ofCode}，但返回 Optional 供"是否显式设置过"判断 */
    public static Optional<WorldTone> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String t = code.trim();
        for (WorldTone v : values()) {
            if (v.code.equalsIgnoreCase(t) || v.label.equals(t)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * 一句话说明（前端下拉的副标题 / 帮助文本，比 {@link #boundary()} 短得多）。
     *
     * @return 中文短说明
     */
    public String summary() {
        return switch (this) {
            case CASUAL -> "生活向：不出现势力斗争、战争与宏观推演";
            case LIGHT -> "允许小摩擦：不出现战争与政权更替";
            case STANDARD -> "按题材本身的世界尺度正常推进";
            case HARD -> "势力博弈、战争与灾变全开";
        };
    }

    /**
     * 注入提示词的「世界程度边界」段。
     *
     * <p>写法要点：说清「不要写什么」而不是只写「轻松一点」——模型对否定式边界更敏感；
     * 同时明确「用户显式要求时仍可例外」，否则连正常的争执都写不出来，世界会变成一潭死水。</p>
     *
     * @return 中文边界段落（不含前后换行）
     */
    public String boundary() {
        return switch (this) {
            case CASUAL -> "世界程度：休闲。这个世界是生活向的，冲突只停留在个人误会、情感摩擦与日常琐事；"
                    + "不要写势力斗争、战争、暗杀、背叛、政治倾轧、权力更替，也不要做人口/经济/科技/文化的宏观推演；"
                    + "NPC 不设宏观目标（争权、扩疆、统一天下、宗门大比之类的竞争性叙事视为越界）；"
                    + "能力上限偏低，世界运转靠人情与日常，而不是靠强权与灾难。";
            case LIGHT -> "世界程度：轻度。允许人情往来、邻里争执、门第与家境差异这类小规模摩擦，"
                    + "但不出现战争、政权更替、大规模势力吞并、暗杀与背叛；"
                    + "不做宏观社会调控（赋税、征兵、经济周期、科技跃迁只作为背景一句带过）；"
                    + "NPC 目标以家庭、朋友与小圈子为主，最多到「在镇上说得上话」这种程度。";
            case STANDARD -> "世界程度：标准。按题材本身的世界尺度正常推进：该有的冲突、势力与宏观变化都可以发生，"
                    + "以世界观设定与登场角色的处境为准，不必刻意回避现实内容。";
            case HARD -> "世界程度：硬核。世界尺度偏硬：势力博弈、战争、灾变、政权更替与经济周期都可以发生，"
                    + "后果要落到具体的人身上；NPC 可以有势力级、天下级目标，也允许出现牺牲与失败。";
        };
    }

    /**
     * 宏观上下文是否注入（休闲档连宏观快照都不该出现在提示词里）。
     *
     * @return true=可以把宏观摘要注入提示词
     */
    public boolean macroContextVisible() {
        return macroSchedule != MacroSchedule.NONE;
    }
}
