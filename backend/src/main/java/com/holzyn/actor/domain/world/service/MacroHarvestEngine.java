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

/**
 * 宏观社会演化 · 收成系数引擎。
 * <p>职责： 全链联动源头（设计 §七：季节/天气 → 收成系数 → 粮食库存 → 粮价 → 生计 →
 * 势力/舆论； 「A：季节基准 × 天气修正 × 连续累积」）：</p>
 * <ul>
 * <li><b>季节基准</b>：春 0.90（青苗）/ 夏 1.05（生长）/ 秋 1.20（收获）/ 冬 0.40（休耕），
 * 再按世界观模板季节联动强度缩放（seasonFactorScale：仙侠 1.0 / 高武 0.7 / 现代 0.4 /
 * 未来 0.15 近零——未来气候控制下季节几乎不影响产出）；
 * <li><b>当日天气修正</b>：小雨 +0.05（适量雨水利于生长）/ 晴 +0.02 / 多云 +0.01 / 微风 +0.03 /
 * 中雨 −0.05 / 大雨 −0.08 / 雷雨 −0.12 / 暴雨 −0.15 / 小雪 −0.02 / 中雪 −0.08 / 大雪 −0.15 /
 * 阴 0（与 SeasonService.conditionTempOffset 同条件口径）；</li>
 * <li><b>连续天气累积</b>：生长季连续 ≥5 日无有效降水 → 旱情逐日加深（每多 1 日 −2%）；
 * 连续 ≥3 日强降水 → 涝情（每多 1 日 −3%）；冬季连续 ≥2 日降雪 → 雪被保温（+2%/日，
 * 封顶 +6%）；</li>
 * <li><b>极端季节事件</b>：大旱（生长季连续 ≥7 日无雨）/ 洪涝（连续 ≥4 日强降水）/
 * 暴雪（冬季连续 ≥3 日降雪）→ 事件标志由调度器产出宏观事件（阈值 AI 叙事）+ 民怨冲击；</li>
 * <li>收成系数钳制 [0.2, 1.5]，全部确定性（daySeed 仅用于未来扩展，当前无随机）。</li>
 * </ul>
 */
public final class MacroHarvestEngine {

    private MacroHarvestEngine() {
    }

    /** 收成 tick 输入（streak 为上一日累积值，引擎推进后返回新值） */
    public record HarvestInput(String season, String weatherCondition, int dryStreak, int floodStreak,
                               int snowStreak, double seasonFactorScale, long daySeed) {
    }

    /** 收成 tick 结果（收成系数 + 连续累积新值 + 极端事件标志） */
    public record HarvestResult(double harvestFactor, int dryStreak, int floodStreak, int snowStreak,
                                boolean drought, boolean flood, boolean blizzard) {
    }

    /** 季节基准系数（春 0.90 青苗 / 夏 1.05 生长 / 秋 1.20 收获 / 冬 0.40 休耕） */
    private static final double SEASON_BASE_SPRING = 0.90;
    private static final double SEASON_BASE_SUMMER = 1.05;
    private static final double SEASON_BASE_AUTUMN = 1.20;
    private static final double SEASON_BASE_WINTER = 0.40;

    /** 收成系数钳制区间 */
    private static final double FACTOR_MIN = 0.2;
    private static final double FACTOR_MAX = 1.5;

    /** 旱情累积起始日（生长季连续 ≥5 日无降水开始逐日加深） */
    private static final int DROUGHT_START_DAYS = 5;
    /** 旱情每日加深幅度（每多 1 日 −2%） */
    private static final double DROUGHT_DAILY_PENALTY = 0.02;
    /** 大旱事件触发（生长季连续 ≥7 日无有效降水） */
    private static final int DROUGHT_EVENT_DAYS = 7;

    /** 涝情累积起始日（连续 ≥3 日强降水开始逐日加深） */
    private static final int FLOOD_START_DAYS = 3;
    /** 涝情每日加深幅度（每多 1 日 −3%） */
    private static final double FLOOD_DAILY_PENALTY = 0.03;
    /** 洪涝事件触发（连续 ≥4 日强降水） */
    private static final int FLOOD_EVENT_DAYS = 4;

    /** 雪被保温起始（冬季连续 ≥2 日降雪 +2%/日，封顶 +6%） */
    private static final int SNOW_INSULATE_START_DAYS = 2;
    private static final double SNOW_DAILY_BONUS = 0.02;
    private static final double SNOW_BONUS_CAP = 0.06;
    /** 暴雪事件触发（冬季连续 ≥3 日降雪） */
    private static final int SNOW_EVENT_DAYS = 3;

    /** 生长季（旱情只在需要水的季节累积惩罚） */
    private static boolean growingSeason(String season) {
        return SeasonService.SPRING.equals(season) || SeasonService.SUMMER.equals(season)
                || SeasonService.AUTUMN.equals(season);
    }

    /** 是否有效降水（小雨/中雨/大雨/雷雨/暴雨——打断旱情累积） */
    private static boolean effectiveRain(String condition) {
        return condition != null && switch (condition.trim()) {
            case "小雨", "中雨", "大雨", "雷雨", "暴雨" -> true;
            default -> false;
        };
    }

    /** 是否强降水（中雨及以上——涝情累积） */
    private static boolean heavyRain(String condition) {
        return condition != null && switch (condition.trim()) {
            case "中雨", "大雨", "雷雨", "暴雨" -> true;
            default -> false;
        };
    }

    /** 是否降雪（小雪/中雪/大雪——雪被保温/暴雪累积） */
    private static boolean snowing(String condition) {
        return condition != null && switch (condition.trim()) {
            case "小雪", "中雪", "大雪" -> true;
            default -> false;
        };
    }

    /**
     * 收成系数每日推进（确定性；streak 维护：无天气信息=保持上一日累积不清零——缺帧降级）。
     *
     * @param in 收成输入
     *
     * @return 收成结果（harvestFactor 恒 ∈ [0.2, 1.5]）
     */
    public static HarvestResult step(HarvestInput in) {
        // ① 季节基准 × 模板季节联动强度缩放
        double seasonBase = switch (in.season() == null ? SeasonService.AUTUMN : in.season()) {
            case SeasonService.SPRING -> SEASON_BASE_SPRING;
            case SeasonService.SUMMER -> SEASON_BASE_SUMMER;
            case SeasonService.AUTUMN -> SEASON_BASE_AUTUMN;
            default -> SEASON_BASE_WINTER;
        };
        double scale = in.seasonFactorScale() <= 0 ? 1.0 : in.seasonFactorScale();
        double seasonEff = 1.0 + (seasonBase - 1.0) * scale;

        // ② 当日天气修正
        double weatherMod = weatherModifier(in.weatherCondition());

        // ③ 连续天气累积（先推进 streak）
        boolean rain = effectiveRain(in.weatherCondition());
        boolean heavy = heavyRain(in.weatherCondition());
        boolean snow = snowing(in.weatherCondition());
        boolean winter = SeasonService.WINTER.equals(in.season());
        int dry = rain ? 0 : in.dryStreak() + 1;
        int flood = heavy ? in.floodStreak() + 1 : 0;
        int snowStreak = snow ? in.snowStreak() + 1 : 0;

        double droughtPenalty = 0.0;
        if (growingSeason(in.season()) && dry >= DROUGHT_START_DAYS) {
            droughtPenalty = (dry - DROUGHT_START_DAYS + 1) * DROUGHT_DAILY_PENALTY;
        }
        double floodPenalty = 0.0;
        if (flood >= FLOOD_START_DAYS) {
            floodPenalty = (flood - FLOOD_START_DAYS + 1) * FLOOD_DAILY_PENALTY;
        }
        double snowBonus = 0.0;
        if (winter && snowStreak >= SNOW_INSULATE_START_DAYS) {
            snowBonus = Math.min(SNOW_BONUS_CAP, (snowStreak - SNOW_INSULATE_START_DAYS + 1) * SNOW_DAILY_BONUS);
        }

        double factor = seasonEff * (1.0 + weatherMod) * (1.0 - droughtPenalty)
                * (1.0 - floodPenalty) * (1.0 + snowBonus);
        factor = clamp(factor, FACTOR_MIN, FACTOR_MAX);

        // ④ 极端季节事件（事件由调度器产出；此处仅判定）
        boolean drought = growingSeason(in.season()) && dry >= DROUGHT_EVENT_DAYS;
        boolean floodEvt = flood >= FLOOD_EVENT_DAYS;
        boolean blizzard = winter && snowStreak >= SNOW_EVENT_DAYS;

        return new HarvestResult(round3(factor), dry, flood, snowStreak, drought, floodEvt, blizzard);
    }

    /** 当日天气修正（无天气信息/未知条件 → 0，零阻断）。 */
    static double weatherModifier(String condition) {
        if (condition == null || condition.isBlank()) {
            return 0.0;
        }
        return switch (condition.trim()) {
            case "小雨" -> 0.05;
            case "晴", "晴间多云" -> 0.02;
            case "微风" -> 0.03;
            case "多云" -> 0.01;
            case "阴", "风" -> 0.0;
            case "中雨" -> -0.05;
            case "大雨" -> -0.08;
            case "雷雨" -> -0.12;
            case "暴雨" -> -0.15;
            case "小雪" -> -0.02;
            case "中雪" -> -0.08;
            case "大雪" -> -0.15;
            default -> 0.0;
        };
    }

    /** 钳制到 [min, max]。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 三位小数舍入（收成系数精度）。 */
    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
