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
 * 宏观社会演化 · 势力数值引擎。
 * <p>职责：势力子系统每旬推进：
 * ① 影响力 = clamp(0,100, 前值×0.98 + 成员行动贡献×0.15 + 经济实力×0.03 + 抖动±1.5)
 * ——成员行动贡献来自 入账（参战/效力/叛离 → faction delta，按势力名聚合）；
 * ② 势力关系状态机：关系分 -100..100，每旬向中立漂移（10%），冲突事件负向冲击；
 * 状态派生：hostile≤-60 / tense≤-30 / neutral / friendly≥30 / allied≥60；
 * ③ 事件：影响力位移 &gt;30%（vs 上旬）→「势力易主/崛起」重大事件（阈值触发 AI 叙事）、
 * 关系分骤降（单旬 &lt;-15）→「家族决裂/冲突」、关系分上升（&gt;+15）→「家族联姻/结盟」。
 * 全部确定性（daySeed 哈希抖动），可复现可单测。</p>
 */
public final class MacroFactionEngine {

    private MacroFactionEngine() {
    }

    /** 势力 tick 输入（factions 直接原地演进——各势力 relations 同表更新） */
    public record FactionInput(Map<String, MacroState.Faction> factions,
                               Map<String, Double> actionContributions,
                               Map<String, Double> economyStrength,
                               Map<String, Integer> prevInfluence /* 势力名→上旬影响力 */,
                               double grievance /* 民怨 0-100（粮价链末端：高民怨→当权影响力↓） */,
                               int harvestOutcome /* 本季收成结果 1丰/0平/-1歉（当权影响力 ±） */,
                               long daySeed) {
    }

    /** 势力 tick 结果（events=规则事件 code 列表 + 事件涉及势力名） */
    public record FactionResult(Map<String, MacroState.Faction> factions, List<String[]> events) {
    }

    /** 民怨→当权势力影响力侵蚀系数（民怨 >30 起，每点民怨 −0.04） */
    private static final double GRIEVANCE_EROSION = 0.04;
    /** 民怨生效阈值（>30 才侵蚀——轻微不满不伤根基） */
    private static final double GRIEVANCE_MIN = 30.0;
    /** 丰年/歉收对当权势力影响力修正（±1.5/旬） */
    private static final double HARVEST_INFLUENCE = 1.5;
    /** 赈济触发：民怨 ≥50 且当权势力影响力 ≥40（负反馈环：开仓→粮价↓民怨↓当权↑） */
    static final double RELIEF_GRIEVANCE = 50.0;
    static final double RELIEF_INFLUENCE = 40.0;
    /** 民变风险触发：民怨 ≥80（重大事件 AI 叙事） */
    static final double REVOLT_GRIEVANCE = 80.0;

    /**
     * 势力每旬推进（确定性；factions 原地演进）。
     */
    public static FactionResult step(FactionInput in) {
        Map<String, MacroState.Faction> factions = in.factions();
        List<String[]> events = new ArrayList<>();
        Map<String, Double> newInfluence = new LinkedHashMap<>();

        for (MacroState.Faction f : factions.values()) {
            double contribution = in.actionContributions().getOrDefault(f.getName(), 0.0);
            double economy = in.economyStrength().getOrDefault(f.getName(), 0.0);
            double jitter = (MacroPopulationEngine.jitter(in.daySeed(), 23) - 0.5) * 3.0;
            double influence = clamp(f.getInfluence() * 0.98 + contribution * 0.15 + economy * 0.03 + jitter, 0.0, 100.0);
            newInfluence.put(f.getName(), influence);
            f.setInfluence(round2(influence));
        }

        // 民怨/收成修正（粮价→生计→势力因果链末端）：当权势力（影响力最高者）承受
        // 民怨侵蚀（>30 起）与收成正向/负向影响（丰年得人心 ±1.5、歉收失人心——与民怨独立生效）
        if (!newInfluence.isEmpty()) {
            Map.Entry<String, Double> ruling = newInfluence.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (ruling != null) {
                double corrected = ruling.getValue();
                if (in.grievance() > GRIEVANCE_MIN) {
                    corrected -= (in.grievance() - GRIEVANCE_MIN) * GRIEVANCE_EROSION;
                }
                corrected = clamp(corrected + in.harvestOutcome() * HARVEST_INFLUENCE, 0.0, 100.0);
                newInfluence.put(ruling.getKey(), corrected);
                factions.get(ruling.getKey()).setInfluence(round2(corrected));
                // 极端民怨事件（赈济负反馈 / 民变风险；事件码=通用语义码）
                if (in.grievance() >= REVOLT_GRIEVANCE) {
                    events.add(new String[]{"revolt_risk", ruling.getKey(), null});
                } else if (in.grievance() >= RELIEF_GRIEVANCE && corrected >= RELIEF_INFLUENCE) {
                    events.add(new String[]{"relief", ruling.getKey(), null});
                }
            }
        }

        // 关系漂移 + 事件冲击（先漂移后冲击，确定性）
        List<String> names = new ArrayList<>(factions.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i);
                String b = names.get(j);
                MacroState.Faction fa = factions.get(a);
                MacroState.Faction fb = factions.get(b);
                int score = fa.getRelations().getOrDefault(b, 0);
                // 向中立漂移 10%
                double drifted = score + (0 - score) * 0.1;
                int newScore = (int) Math.round(drifted);
                // 冲突冲击：敌对势力 + 经济压力 → 关系恶化；友好 → 缓慢修复（已含漂移；
                // 事件码=通用语义码 faction_split/faction_alliance）
                if (score <= -30 && MacroPopulationEngine.jitter(in.daySeed(), 29 + i * 7 + j) < 0.4) {
                    newScore -= 4;
                    events.add(new String[]{"faction_split", a, b});
                } else if (score >= 30 && MacroPopulationEngine.jitter(in.daySeed(), 31 + i * 7 + j) < 0.2) {
                    newScore += 3;
                    events.add(new String[]{"faction_alliance", a, b});
                }
                newScore = (int) Math.max(-100, Math.min(100, newScore));
                fa.getRelations().put(b, newScore);
                fb.getRelations().put(a, newScore);
            }
        }

        // 影响力位移事件（>30% 阈值；事件码=通用语义码 power_shift）
        for (Map.Entry<String, Double> e : newInfluence.entrySet()) {
            double prev = in.prevInfluence().getOrDefault(e.getKey(), (int) Math.round(e.getValue()));
            double shift = prev > 0 ? Math.abs(e.getValue() - prev) / prev : 0.0;
            if (shift > 0.30) {
                events.add(new String[]{"power_shift", e.getKey(), null});
            }
        }
        return new FactionResult(factions, events);
    }

    /** 关系分 → 状态机（hostile/tense/neutral/friendly/allied）。 */
    public static String relationState(int score) {
        if (score <= -60) return "hostile";
        if (score <= -30) return "tense";
        if (score >= 60) return "allied";
        if (score >= 30) return "friendly";
        return "neutral";
    }

    /** 钳制到 [min, max]。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 两位小数舍入。 */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
