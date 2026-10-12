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

package com.holzyn.actor.domain.world.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 文化舆论引擎。
 * <p>职责：文化子系统每月推进 ——舆论
 * 传播模型（与信息扩散联动、按议题聚合社会情绪）、信仰演变、新习俗/流行：</p>
 * <ul>
 * <li><b>议题强度</b>= 上期 × 自然衰减（0.92/月） + 入账贡献（NPC 言行 culture delta，
 * 本月聚合，×0.25 封顶 +0.2）+ 社会级信息扩散聚合（本月宏观信息包按话题命中，
 * 每条 +0.1 封顶 +0.3）+ 事件冲击（重大事件按类型 ±0.05~0.15）；</li>
 * <li><b>议题倾向</b>：随事件方向漂移（赈济→民怨降、丰年→正面、民变→负面，±0.05 封顶）；</li>
 * <li><b>新议题诞生</b>：宏观信息包话题（如「粮价」「大旱」）持续出现且无对应议题
 * → 以强度 0.3 新增（议题数 ≤6 防膨胀）；</li>
 * <li><b>舆论转向</b>（阈值 AI 叙事）：最强议题强度单月变化 ≥±30% 或最强议题易位；</li>
 * <li><b>信仰演变</b>：缓慢确定性漂移（±0.005）+ 事件冲击（丰年 +0.03 / 饥荒 −0.05 /
 * 瘟疫 −0.04 / 开仓赈济 +0.02）；单月跌幅 ≥15% → 信仰危机（阈值 AI 叙事）；</li>
 * <li><b>新习俗/流行</b>：丰年/赈济等重大正面事件 → 程序化新增习俗（fashionSinceDay 记录
 * 起始日，超 90 游戏日消退）；→ 新习俗兴起（阈值 AI 叙事）；</li>
 * <li><b>谣言成风</b>：单月宏观信息包 ≥3 条 → 事件（常规规则模板）。</li>
 * </ul>
 */
public final class MacroCultureEngine {

    private MacroCultureEngine() {
    }

    /** 文化月度 tick 输入 */
    public record CultureInput(List<MacroState.Issue> issues, Map<String, Double> beliefs,
                               List<String> fashions, Map<String, Long> fashionSinceDay,
                               Map<String, Double> cultureDeltas /* 议题话题 → 本月入账合计 */,
                               List<String> macroPacketTopics /* 本月宏观信息包命中话题 */,
                               List<String> extremeEventTypes /* 本月重大事件类型（冲击源） */,
                               long currentDay, long daySeed) {
    }

    /** 文化月度 tick 结果（issues/beliefs/fashions 原地演进 + 事件标志） */
    public record CultureResult(List<MacroState.Issue> issues, Map<String, Double> beliefs,
                                List<String> fashions, Map<String, Long> fashionSinceDay,
                                List<String> events /* 舆论转向/信仰危机/新习俗兴起/谣言成风 */) {
    }

    /** 议题自然衰减（每月 ×0.92） */
    private static final double ISSUE_DECAY = 0.92;
    /** 入账贡献强度折算（×0.25，封顶 +0.2/月） */
    private static final double DELTA_WEIGHT = 0.25;
    private static final double DELTA_CAP = 0.2;
    /** 信息扩散聚合强度（每条命中 +0.1，封顶 +0.3/月） */
    private static final double PACKET_BOOST = 0.1;
    private static final double PACKET_CAP = 0.3;
    /** 舆论转向阈值（最强议题单月强度变化 ≥30% 或易位） */
    private static final double TURN_THRESHOLD = 0.30;
    /** 信仰危机阈值（单月跌幅 ≥15%） */
    private static final double FAITH_CRISIS_DROP = 0.15;
    /** 新议题诞生强度 */
    private static final double NEW_ISSUE_INTENSITY = 0.3;
    /** 议题数上限（防膨胀） */
    private static final int MAX_ISSUES = 6;
    /** 习俗消退年龄（游戏日） */
    private static final long FASHION_MAX_AGE_DAYS = 90;
    /** 谣言成风信息包条数阈值（单月 ≥3） */
    private static final int RUMOR_PACKET_THRESHOLD = 3;

    /**
     * 文化月度推进（确定性；issues/beliefs/fashions 原地演进）。
     * <p>⚠️ 前置修复（月度 tick NPE）：输入空值防御——cultureDeltas/macroPacketTopics/
     * extremeEventTypes/beliefs/fashions/fashionSinceDay 为 null 或个别值为 null 时归一为
     * 安全空值（null 值按 0.0 计），引擎永不抛 NPE（空值解包防御）。</p>
     */
    public static CultureResult step(CultureInput in) {
        List<MacroState.Issue> issues = in.issues() == null ? new ArrayList<>() : in.issues();
        Map<String, Double> beliefs = in.beliefs() == null ? new LinkedHashMap<>() : in.beliefs();
        List<String> fashions = in.fashions() == null ? new ArrayList<>() : in.fashions();
        Map<String, Long> fashionSinceDay = in.fashionSinceDay() == null
                ? new LinkedHashMap<>() : in.fashionSinceDay();
        Map<String, Double> cultureDeltas = in.cultureDeltas() == null
                ? new LinkedHashMap<>() : in.cultureDeltas();
        List<String> packetTopics = in.macroPacketTopics() == null ? new ArrayList<>() : in.macroPacketTopics();
        List<String> extremeEventTypes = in.extremeEventTypes() == null
                ? new ArrayList<>() : in.extremeEventTypes();
        List<String> events = new ArrayList<>();

        // ==================== ① 议题强度/倾向演进 ====================
        Map<String, Double> prevIntensity = new LinkedHashMap<>();
        for (MacroState.Issue i : issues) {
            prevIntensity.put(i.getTopic(), i.getIntensity());
        }
        for (MacroState.Issue i : issues) {
            // null 值防御：getOrDefault 在「键存在但值为 null」时返回 null，直接赋 double 会解包 NPE
            Double deltaBox = cultureDeltas.get(i.getTopic());
            double delta = deltaBox == null ? 0.0 : deltaBox;
            double packet = packetTopics.contains(i.getTopic()) ? PACKET_BOOST : 0.0;
            double shock = eventShock(extremeEventTypes, i.getTopic());
            double next = i.getIntensity() * ISSUE_DECAY
                    + Math.min(DELTA_CAP, delta * DELTA_WEIGHT)
                    + Math.min(PACKET_CAP, packet)
                    + shock;
            i.setIntensity(clamp(next, 0.0, 1.0));
            // 倾向漂移（事件方向）
            double drift = orientationDrift(extremeEventTypes);
            i.setOrientation(clamp(i.getOrientation() + drift, -1.0, 1.0));
        }
        // 新议题诞生（宏观信息包话题持续出现且无对应议题）
        for (String topic : packetTopics) {
            if (issues.size() >= MAX_ISSUES) {
                break;
            }
            if (issues.stream().noneMatch(i -> i.getTopic() == null ? topic == null : i.getTopic().equals(topic))) {
                MacroState.Issue ni = new MacroState.Issue();
                ni.setTopic(topic);
                ni.setOrientation(0.0);
                ni.setIntensity(NEW_ISSUE_INTENSITY);
                issues.add(ni);
                prevIntensity.put(topic, 0.0);
            }
        }
        // 舆论转向判定：最强议题强度变化 ≥30% 或最强议题易位（事件码=通用语义码）
        if (!issues.isEmpty()) {
            MacroState.Issue top = issues.stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity)).orElse(null);
            if (top != null) {
                double prev = prevIntensity.getOrDefault(top.getTopic(), top.getIntensity());
                double change = prev > 0.01 ? Math.abs(top.getIntensity() - prev) / prev : 0.0;
                if (change >= TURN_THRESHOLD) {
                    events.add("opinion_shift");
                }
            }
        }

        // ==================== ② 信仰演变（null 值按 0.0 计——空值解包防御） ====================
        for (Map.Entry<String, Double> e : beliefs.entrySet()) {
            double drift = (MacroPopulationEngine.jitter(in.daySeed(), 41) - 0.5) * 0.01; // ±0.005
            double shock = beliefShock(extremeEventTypes);
            double cur = e.getValue() == null ? 0.0 : e.getValue();
            double next = cur + drift + shock;
            e.setValue(clamp(next, 0.05, 1.0));
        }
        for (Map.Entry<String, Double> e : beliefs.entrySet()) {
            if ((e.getValue() == null ? 0.0 : e.getValue()) < FAITH_CRISIS_DROP) {
                events.add("faith_crisis");
                break;
            }
        }

        // ==================== ③ 新习俗/流行（正面事件程序化新增 + 超龄消退） ====================
        if (extremeEventTypes.contains("harvest_good")) {
            String fashion = "丰收庆典";
            if (!fashions.contains(fashion)) {
                fashions.add(fashion);
                fashionSinceDay.put(fashion, in.currentDay());
                events.add("new_custom");
            }
        }
        if (extremeEventTypes.contains("relief")) {
            String fashion = "济困施粥日";
            if (!fashions.contains(fashion)) {
                fashions.add(fashion);
                fashionSinceDay.put(fashion, in.currentDay());
                events.add("new_custom");
            }
        }
        // 超龄消退（≥90 游戏日）
        fashions.removeIf(f -> {
            Long since = fashionSinceDay.get(f);
            return since != null && in.currentDay() - since >= FASHION_MAX_AGE_DAYS;
        });

        // ==================== ④ 谣言成风（信息包 ≥3 条） ====================
        if (packetTopics.size() >= RUMOR_PACKET_THRESHOLD) {
            events.add("rumor");
        }

        return new CultureResult(issues, beliefs, fashions, fashionSinceDay, events);
    }

    /** 事件冲击（按事件码 ±强度；与议题主题无关的全局冲击；事件码=通用语义码）。 */
    private static double eventShock(List<String> events, String topic) {
        double s = 0.0;
        for (String e : events) {
            switch (e) {
                case "harvest_good" -> s += 0.05;
                case "harvest_bad", "drought", "flood", "blizzard" -> s += 0.08;
                case "revolt_risk" -> s += 0.12;
                case "plague", "famine" -> s += 0.06;
                case "relief" -> s += 0.04;
                default -> {
                }
            }
        }
        return Math.min(0.15, s);
    }

    /** 倾向漂移（事件方向：正面事件 + / 负面事件 −，封顶 ±0.05；事件码=通用语义码）。 */
    private static double orientationDrift(List<String> events) {
        double d = 0.0;
        for (String e : events) {
            switch (e) {
                case "harvest_good", "relief", "caravan", "tech_breakthrough" -> d += 0.02;
                case "revolt_risk", "famine", "drought", "flood" -> d -= 0.02;
                case "plague" -> d -= 0.01;
                default -> {
                }
            }
        }
        return clamp(d, -0.05, 0.05);
    }

    /** 信仰冲击（重大事件对信仰强度的影响；事件码=通用语义码）。 */
    private static double beliefShock(List<String> events) {
        double s = 0.0;
        for (String e : events) {
            switch (e) {
                case "harvest_good" -> s += 0.03;
                case "relief" -> s += 0.02;
                case "famine" -> s -= 0.05;
                case "plague" -> s -= 0.04;
                case "faith_crisis" -> s -= 0.03;
                default -> {
                }
            }
        }
        return s;
    }

    /** 钳制到 [min, max]。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
