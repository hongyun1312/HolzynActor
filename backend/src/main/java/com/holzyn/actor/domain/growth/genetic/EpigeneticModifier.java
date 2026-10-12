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

package com.holzyn.actor.domain.growth.genetic;

import java.util.ArrayList;
import java.util.List;

/**
 * 表观遗传修饰因子计算。
 * <p><b></b>：双源（父母 L3 核心记忆 + 世界事件，importance≥4，孕前
 * 90 游戏日窗口）+ 单代 + 留痕（epigenetics_json）+ 出生到幼儿期末线性衰减。</p>
 * <p><b>四类事件 → 子代表达因子（对齐参考文档 §4.3）</b>：</p>
 * <ul>
 * <li>创伤/战乱/暴力（关键词命中）→ 神经质表达 ×1.10~1.30（子代应激敏感；跨代创伤研究依据）</li>
 * <li>饥荒/灾年 → 代谢相关表达 ×1.05~1.15（子代代谢偏好囤积；荷兰饥荒队列跨代依据）</li>
 * <li>孤立/被逐 → 外向性 ×0.85~0.95（子代社交回避倾向）</li>
 * <li>丰饶/庆典/喜事 → 宜人性 ×1.05~1.10（子代亲社会倾向）</li>
 * </ul>
 * <p><b>分类口径</b>：按文本关键词正则命中；同一目标（如 NEUROTICISM）取全部事件中强度最大的一条
 * （避免多条叠加把子代推爆）；无命中 → 空印记（{@code marks=[]}，零阻断）。</p>
 * <p><b>有效期</b>：印记从出生生效，到幼儿期末（出生 + 7 游戏年）线性衰减至 1.0；
 * 本类只落印记本身（含 {@code valid_until_game_second}），衰减插值在新生儿档案构建消费方完成。</p>
 */
public final class EpigeneticModifier {

    /** 目标特征键：神经质（OCEAN_NEUROTICISM） */
    public static final String TARGET_NEUROTICISM = GeneticLoci.KEY_OCEAN_NEUROTICISM;
    /** 目标特征键：外向性（OCEAN_EXTRAVERSION） */
    public static final String TARGET_EXTRAVERSION = GeneticLoci.KEY_OCEAN_EXTRAVERSION;
    /** 目标特征键：宜人性（OCEAN_AGREEABLENESS） */
    public static final String TARGET_AGREEABLENESS = GeneticLoci.KEY_OCEAN_AGREEABLENESS;
    /** 目标特征键：代谢（非 OCEAN，③ 生理基线消费） */
    public static final String TARGET_METABOLISM = "METABOLISM";

    /** 印记有效期（出生后游戏年数，幼儿期末，参考文档 §4.3「约 7 游戏年」） */
    public static final int MARK_VALID_YEARS = 7;

    /** 父母重大事件（收集方喂入的原始事件；gameSecond 可空=时间窗内视为符合[如 L3 蒸馏块无时刻]） */
    public record ParentEvent(String text, int importance, Long gameSecond) {
    }

    private EpigeneticModifier() {
    }

    /**
     * 计算表观修饰印记集。
     *
     * @param parentEvents 父母双方孕前重大事件（importance≥4；本方法内再过滤一次）
     *
     * @param pregnancyStartSecond 怀孕开始时刻（游戏秒；世界事件窗口起点 = 此 − 90 游戏日）
     *
     * @param newbornGameSecond 新生儿出生时刻（印记生效起点）
     *
     * @param windowGameDays 孕前窗口（游戏日，默认 90）
     *
     * @param gameSecondsPerYear 游戏年秒数（印记到期 = 出生 + 7 游戏年）
     *
     * @return 印记集（无命中=空 marks）
     */
    public static EpigeneticMarks apply(List<ParentEvent> parentEvents, long pregnancyStartSecond,
                                        long newbornGameSecond, int windowGameDays, long gameSecondsPerYear) {
        EpigeneticMarks marks = new EpigeneticMarks();
        if (parentEvents == null || parentEvents.isEmpty()) {
            return marks;
        }
        long windowSeconds = (long) windowGameDays * 86400L;
        // 命中同一目标的候选（按强度排序取最强）
        List<ParentEvent> trauma = new ArrayList<>();
        List<ParentEvent> famine = new ArrayList<>();
        List<ParentEvent> isolation = new ArrayList<>();
        List<ParentEvent> abundance = new ArrayList<>();
        for (ParentEvent e : parentEvents) {
            if (e == null || e.text() == null || e.text().isBlank()) {
                continue;
            }
            if (e.importance() < 4) {
                continue;
            }
            // 世界事件（有时刻）须落在孕前窗口内；L3 蒸馏块（无时刻）视为符合
            if (e.gameSecond() != null && (e.gameSecond() < pregnancyStartSecond - windowSeconds
                    || e.gameSecond() > pregnancyStartSecond)) {
                continue;
            }
            String t = e.text();
            if (hits(t, "战", "仗", "杀", "暴", "伤", "亡", "殁", "病", "劫", "祸", "仇", "死", "血")) {
                trauma.add(e);
            } else if (hits(t, "饥", "荒", "歉收", "绝收", "粮", "饿")) {
                famine.add(e);
            } else if (hits(t, "逐", "离村", "孤立", "被弃", "独居", "流放", "被拒", "疏远")) {
                isolation.add(e);
            } else if (hits(t, "丰", "庆", "喜", "婚", "宴", "收成", "诞", "荣", "赏", "庙会")) {
                abundance.add(e);
            }
        }
        long validUntil = newbornGameSecond + (long) MARK_VALID_YEARS * gameSecondsPerYear;
        addMark(marks, TARGET_NEUROTICISM, trauma, 1.10, 1.30, "trauma_war", newbornGameSecond, validUntil);
        addMark(marks, TARGET_METABOLISM, famine, 1.05, 1.15, "famine", newbornGameSecond, validUntil);
        addMark(marks, TARGET_EXTRAVERSION, isolation, 0.85, 0.95, "social_isolation", newbornGameSecond, validUntil);
        addMark(marks, TARGET_AGREEABLENESS, abundance, 1.05, 1.10, "abundance", newbornGameSecond, validUntil);
        return marks;
    }

    /** 文本是否命中任一词。 */
    private static boolean hits(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加一条印记（同目标取最强：因子距 1.0 最远者）。
     *
     * @param marks 印记集（原地追加）
     *
     * @param target 目标特征键
     *
     * @param candidates 命中该分类的事件
     *
     * @param factorMin 因子区间下界
     *
     * @param factorMax 因子区间上界
     *
     * @param sourceEvent 来源事件类型码
     *
     * @param issued 生效起点（出生时刻）
     *
     * @param validUntil 到期时刻
     */
    private static void addMark(EpigeneticMarks marks, String target, List<ParentEvent> candidates,
                                double factorMin, double factorMax, String sourceEvent,
                                long issued, long validUntil) {
        if (candidates.isEmpty()) {
            return;
        }
        // 按重要度取最强事件（重要度同则取首条）；因子随事件数在区间内取偏
        ParentEvent best = candidates.get(0);
        for (ParentEvent e : candidates) {
            if (e.importance() > best.importance()) {
                best = e;
            }
        }
        // 强度：事件条数越多越接近区间极值；单条取区间中值
        double t = candidates.size() <= 1 ? 0.5 : Math.min(1.0, 0.5 + 0.15 * (candidates.size() - 1));
        double factor = factorMin + (factorMax - factorMin) * t;
        marks.getMarks().add(EpigeneticMarks.Mark.of(target, Math.round(factor * 100.0) / 100.0,
                sourceEvent, truncate(best.text(), 60), issued, validUntil));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
