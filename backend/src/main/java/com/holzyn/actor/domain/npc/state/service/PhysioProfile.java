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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPC 生理档案 → 状态模块生效系数。
 * <p><b>设计意图（用户明确要求）</b>：「生理基线的具体数据必须与 NPC 状态模块对接，不要独立出来」——
 * 因此档案 ③ 维（作息型/睡眠需求/饥饿周期/代谢快慢/耐痛/耐渴/耐寒/耐热/酒量…）<b>不落 persona_json</b>，
 * 而是写进 {@code actor_state.physio_json}，再由本类换算为<b>生效系数</b>缓存到
 * {@code actor_state.physio_coef_json}；此后每 tick 的衰减与阈值计算只读系数，
 * 即「全局默认（{@code NpcStateProperties}）× 个体系数」。</p>
 * <p><b>换算规则（确定性、可单测、有钳制，避免 AI 极端值破坏世界节律）</b>：</p>
 * <tr><th>系数</th><th>来源（档案 ③ 维）</th><th>换算</th><th>钳制</th></tr>
 * <tr><td>hungerScale</td><td>metabolism + mealCycleHours/mealsPerDay</td><td>代谢 slow .85 / normal 1 / fast 1.18，再 × (8/餐隔)</td><td>0.5~2.0</td></tr>
 * <tr><td>thirstScale</td><td>metabolism</td><td>slow .90 / normal 1 / fast 1.12</td><td>0.6~1.6</td></tr>
 * <tr><td>energyScale</td><td>sleepNeedHours + metabolism</td><td>(睡眠需求/8) × 代谢系数（睡得少→耗得慢）</td><td>0.6~1.5</td></tr>
 * <tr><td>bowelScale</td><td>metabolism</td><td>slow .90 / normal 1 / fast 1.10</td><td>0.6~1.5</td></tr>
 * <tr><td>sleepNeedHours</td><td>sleepNeedHours</td><td>原值（驱动"睡够了没"判定）</td><td>4~12</td></tr>
 * <tr><td>sleepStartHour / sleepEndHour</td><td>chronotype</td><td>早鸟 21-5／中间 22-6／夜猫 24-8</td><td>—</td></tr>
 * <tr><td>painFactor</td><td>painTolerance（0-10）</td><td>1 - (耐痛-5)/10（耐痛高→健康侵蚀慢）</td><td>0.5~1.5</td></tr>
 * <tr><td>coldSens / heatSens</td><td>coldTolerance / heatTolerance</td><td>1 - (耐受-5)/10（耐受低→环境修正放大）</td><td>0.5~1.5</td></tr>
 * <tr><td>recoverScale</td><td>appearance.endurance（体质耐力，可选）</td><td>0.8 + 耐力/25</td><td>0.7~1.3</td></tr>
 * </table>
 *
 * @param hungerScale 饥饿衰减系数
 *
 * @param thirstScale 口渴衰减系数
 *
 * @param energyScale 精力衰减系数
 *
 * @param bowelScale 膀胱衰减系数
 *
 * @param sleepNeedHours 每游戏日所需睡眠小时
 *
 * @param sleepStartHour 夜间睡眠起始小时（当日 22 → [22,24)∪[0,6) 视为睡眠）
 *
 * @param sleepEndHour 夜间睡眠结束小时
 *
 * @param painFactor 健康侵蚀系数（耐痛越高越小）
 *
 * @param coldSens 寒冷敏感系数（环境温度修正放大倍率）
 *
 * @param heatSens 炎热敏感系数
 *
 * @param recoverScale 恢复速率系数（行动/休息恢复量放大）
 *
 * @param chronotype 作息型码（审计/展示）
 *
 * @param metabolism 代谢码（审计/展示）
 */
public record PhysioProfile(
        double hungerScale,
        double thirstScale,
        double energyScale,
        double bowelScale,
        double sleepNeedHours,
        int sleepStartHour,
        int sleepEndHour,
        double painFactor,
        double coldSens,
        double heatSens,
        double recoverScale,
        String chronotype,
        String metabolism) {

    /** 默认生理档案（档案 ③ 维缺失时的兜底：全部系数 1.0，与 state 模块全局默认完全一致）。 */
    public static PhysioProfile defaults() {
        return new PhysioProfile(1.0, 1.0, 1.0, 1.0, 8.0, 22, 6, 1.0, 1.0, 1.0, 1.0, "intermediate", "normal");
    }

    /**
     * 由档案 ③ 维 JSON（{@code actor_state.physio_json}）换算生效系数。
     * <p>JSON 为空/非法/字段缺失 → 对应项取默认（<b>零阻断</b>：脏数据绝不让状态模块停摆）。</p>
     *
     * @param physioJson 档案 ③ 维 JSON（可空）
     *
     * @return 生效系数
     */
    public static PhysioProfile fromPhysioJson(String physioJson) {
        if (physioJson == null || physioJson.isBlank()) {
            return defaults();
        }
        PersonaDims.Physio p;
        try {
            p = PersonaJson.mapper().readValue(physioJson, PersonaDims.Physio.class);
        } catch (JacksonException e) {
            return defaults();
        }
        if (p == null) {
            return defaults();
        }
        return from(p);
    }

    /**
     * 由档案 ③ 维对象换算生效系数。
     *
     * @param p 档案 ③ 维（可空）
     *
     * @return 生效系数
     */
    public static PhysioProfile from(PersonaDims.Physio p) {
        if (p == null) {
            return defaults();
        }
        // ① 代谢：基础倍率
        String meta = p.getMetabolism() == null ? "normal" : p.getMetabolism();
        double[] metaScale = switch (meta) {
            case "slow" -> new double[]{0.85, 0.90, 0.95, 0.90};
            case "fast" -> new double[]{1.18, 1.12, 1.08, 1.10};
            default -> new double[]{1.0, 1.0, 1.0, 1.0};
        };
        // ② 餐制：餐隔越短 → 饿得越快（以 8 游戏小时为基线）
        double mealScale = 1.0;
        if (p.getMealCycleHours() != null && p.getMealCycleHours() > 0) {
            mealScale = 8.0 / p.getMealCycleHours();
        } else if (p.getMealsPerDay() != null && p.getMealsPerDay() > 0) {
            mealScale = p.getMealsPerDay() / 3.0;
        }
        double hungerScale = clamp(metaScale[0] * mealScale, 0.5, 2.0);
        double thirstScale = clamp(metaScale[1], 0.6, 1.6);
        double bowelScale = clamp(metaScale[3], 0.6, 1.5);
        // ③ 睡眠需求：需求越短 → 精力耗得越慢（以 8 小时为基线）
        double sleepNeed = p.getSleepNeedHours() == null ? 8.0 : p.getSleepNeedHours();
        double energyScale = clamp((sleepNeed / 8.0) * metaScale[2], 0.6, 1.5);
        // ④ 作息相位
        String chrono = p.getChronotype() == null ? "intermediate" : p.getChronotype();
        int[] window = switch (chrono) {
            case "early_bird" -> new int[]{21, 5};
            case "night_owl" -> new int[]{24, 8};
            default -> new int[]{22, 6};
        };
        // ⑤ 耐受 → 系数
        double painFactor = clamp(1.0 - (val(p.getPainTolerance(), 5) - 5) / 10.0, 0.5, 1.5);
        double coldSens = clamp(1.0 - (val(p.getColdTolerance(), 5) - 5) / 10.0, 0.5, 1.5);
        double heatSens = clamp(1.0 - (val(p.getHeatTolerance(), 5) - 5) / 10.0, 0.5, 1.5);
        return new PhysioProfile(hungerScale, thirstScale, energyScale, bowelScale,
                clamp(sleepNeed, 4.0, 12.0), window[0], window[1],
                painFactor, coldSens, heatSens, 1.0, chrono, meta);
    }

    /**
     * 叠加体质耐力（② 外貌与体质维）对恢复速率的影响。
     * <p>与 ③ 维分开入参，是因为 ② 与 ③ 在档案里属不同维度、权威模块也不同（② 落档案、③ 落状态模块）。</p>
     *
     * @param endurance 耐力 0-10（可空）
     *
     * @return 叠加后的档案
     */
    public PhysioProfile withEndurance(Integer endurance) {
        double scale = endurance == null ? 1.0 : clamp(0.8 + endurance / 25.0, 0.7, 1.3);
        return new PhysioProfile(hungerScale, thirstScale, energyScale, bowelScale, sleepNeedHours,
                sleepStartHour, sleepEndHour, painFactor, coldSens, heatSens, scale, chronotype, metabolism);
    }

    /**
     * 叠加年龄/机能修正。
     * <p>由 {@code GrowthGateService.ageModifiers(capacity)} 产出倍率表（recoverScale/energyScale/
     * hungerScale/coldSens/heatSens/painFactor），逐项相乘并钳制到既有区间——<b>幂等</b>：
     * 调用方必须从基准（{@code physio_json}）出发重算，不能在已修正的系数上再乘（会复合漂移）。</p>
     *
     * @param mods 年龄修正倍率表（键缺失/非法值 → 该项不修正；null/空表 → 原样返回）
     *
     * @return 叠加后的档案
     */
    public PhysioProfile withAgeModifiers(Map<String, Double> mods) {
        if (mods == null || mods.isEmpty()) {
            return this;
        }
        double mRecover = mods.getOrDefault("recoverScale", 1.0);
        double mEnergy = mods.getOrDefault("energyScale", 1.0);
        double mHunger = mods.getOrDefault("hungerScale", 1.0);
        double mCold = mods.getOrDefault("coldSens", 1.0);
        double mHeat = mods.getOrDefault("heatSens", 1.0);
        double mPain = mods.getOrDefault("painFactor", 1.0);
        return new PhysioProfile(
                clamp(hungerScale * mHunger, 0.5, 2.0),
                thirstScale,
                clamp(energyScale * mEnergy, 0.6, 1.5),
                bowelScale,
                sleepNeedHours, sleepStartHour, sleepEndHour,
                clamp(painFactor * mPain, 0.5, 1.5),
                clamp(coldSens * mCold, 0.5, 1.6),
                clamp(heatSens * mHeat, 0.5, 1.5),
                clamp(recoverScale * mRecover, 0.5, 1.3),
                chronotype, metabolism);
    }

    /**
     * 是否处于夜间睡眠时段。
     *
     * @param hourOfDay 当日小时 0-23
     *
     * @return true=睡眠时段（驱动精力恢复与膀胱不衰减）
     */
    public boolean isSleepWindow(int hourOfDay) {
        int h = ((hourOfDay % 24) + 24) % 24;
        if (sleepStartHour >= 24) {
            // 夜猫子：起始 24 → 次日 0 点后入睡
            return h < sleepEndHour;
        }
        return h >= sleepStartHour || h < sleepEndHour;
    }

    /**
     * 序列化为 JSON（写入 {@code actor_state.physio_coef_json}）。
     *
     * @return JSON 文本
     */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hungerScale", round(hungerScale));
        m.put("thirstScale", round(thirstScale));
        m.put("energyScale", round(energyScale));
        m.put("bowelScale", round(bowelScale));
        m.put("sleepNeedHours", round(sleepNeedHours));
        m.put("sleepStartHour", sleepStartHour);
        m.put("sleepEndHour", sleepEndHour);
        m.put("painFactor", round(painFactor));
        m.put("coldSens", round(coldSens));
        m.put("heatSens", round(heatSens));
        m.put("recoverScale", round(recoverScale));
        m.put("chronotype", chronotype);
        m.put("metabolism", metabolism);
        return PersonaJson.mapper().writeValueAsString(m);
    }

    /**
     * 从 {@code actor_state.physio_coef_json} 反序列化（缺失/非法 → 默认系数，零阻断）。
     *
     * @param coefJson 系数 JSON（可空）
     *
     * @return 生效系数
     */
    @SuppressWarnings("unchecked")
    public static PhysioProfile fromCoefJson(String coefJson) {
        if (coefJson == null || coefJson.isBlank()) {
            return defaults();
        }
        Map<String, Object> m;
        try {
            m = PersonaJson.mapper().readValue(coefJson, Map.class);
        } catch (JacksonException e) {
            return defaults();
        }
        if (m == null || m.isEmpty()) {
            return defaults();
        }
        PhysioProfile d = defaults();
        return new PhysioProfile(
                num(m.get("hungerScale"), d.hungerScale),
                num(m.get("thirstScale"), d.thirstScale),
                num(m.get("energyScale"), d.energyScale),
                num(m.get("bowelScale"), d.bowelScale),
                num(m.get("sleepNeedHours"), d.sleepNeedHours),
                (int) num(m.get("sleepStartHour"), d.sleepStartHour),
                (int) num(m.get("sleepEndHour"), d.sleepEndHour),
                num(m.get("painFactor"), d.painFactor),
                num(m.get("coldSens"), d.coldSens),
                num(m.get("heatSens"), d.heatSens),
                num(m.get("recoverScale"), d.recoverScale),
                m.get("chronotype") == null ? d.chronotype : String.valueOf(m.get("chronotype")),
                m.get("metabolism") == null ? d.metabolism : String.valueOf(m.get("metabolism")));
    }

    /**
     * 一行摘要（调试页/日志展示"档案 ③ 维 ↔ 生效系数"对照）。
     *
     * @return 摘要文本
     */
    public String summary() {
        return "饿×" + round(hungerScale) + " 渴×" + round(thirstScale) + " 精力×" + round(energyScale)
                + " 膀胱×" + round(bowelScale) + " 睡眠 " + round(sleepNeedHours) + "h(" + sleepStartHour
                + "-" + sleepEndHour + ") 痛×" + round(painFactor) + " 寒×" + round(coldSens)
                + " 热×" + round(heatSens) + " 恢复×" + round(recoverScale)
                + " [" + chronotype + "/" + metabolism + "]";
    }

    /** 数值钳制。 */
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 取整数（null → 默认）。 */
    private static int val(Integer v, int def) {
        return v == null ? def : v;
    }

    /** 四舍五入到两位小数（减少 JSON 体积）。 */
    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 从 Map 取数值。 */
    private static double num(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}