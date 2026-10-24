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

package com.holzyn.actor.domain.mapgen.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地图环境快照（季节 / 天气 / 潮汐 / 昼夜）。
 *
 * <p><b>为什么地图需要这一层</b>：一张永远「晴天正午」的地图，无论画得多细，都是死的。
 * 真实世界的地表是被时间驱动的：春天田里是嫩苗、秋天是割过的茬；涨潮时滩涂被淹掉一截；
 * 雨天路面发暗、水面起涟漪；夜里只有窗火。<b>同一个世界时钟</b>推动这些变化，
 * 地图才会跟着世界一起「在跑」，而不是一张静态照片。</p>
 *
 * <p><b>为什么由程序算而不是让 AI 编</b>：季节是天文量（黄赤交角）、潮汐是天文量
 * （月球引潮力，半日周期 12 小时 25 分），这些都有确定的物理规律。让 AI 自由发挥
 * 只会得到「随机天气」，反而不真实。天气的随机性用「seed + 日序号」派生，
 * 同一天刷新两次看到的是同一种天气。</p>
 *
 * <p>单位：{@code tide}/{@code wetness} 0~1；{@code minuteOfDay} 0~1439；
 * {@code temperatureC} 摄氏度。全部可直接进前端。</p>
 *
 * @param season        spring / summer / autumn / winter
 * @param seasonName    中文季节名
 * @param weather       clear / cloudy / rain / fog / wind
 * @param weatherName   中文天气名
 * @param dayOfYear     一年中的第几天（1~365）
 * @param dayIndex      世界开启以来的第几天（从 0 起）
 * @param minuteOfDay   当天第几分钟（0~1439）
 * @param hour          小时（0~23）
 * @param isNight       是否夜间（用于夜景底图与窗火）
 * @param tide          潮位（0=低潮，1=高潮）
 * @param tideName      潮汐中文名（涨潮/退潮/满潮/干潮）
 * @param wetness       地表湿度（0~1；雨天/雨后高）
 * @param temperatureC  气温（摄氏度）
 * @param sunAzimuthDeg 太阳方位（度，0=北，顺时针）
 * @param sunElevationDeg 太阳高度角（度；夜间为负）
 * @param groundTint    地表季节色（#rrggbb）
 * @param tintStrength  季节色强度（0~1，给前端叠色用）
 */
public record MapAmbient(String season, String seasonName,
                         String weather, String weatherName,
                         int dayOfYear, int dayIndex, int minuteOfDay, int hour, boolean isNight,
                         double tide, String tideName, double wetness, double temperatureC,
                         double sunAzimuthDeg, double sunElevationDeg,
                         String groundTint, double tintStrength) {

    /** 一个游戏日 = 86400 游戏秒。 */
    public static final long SECONDS_PER_DAY = 86400L;

    /** 潮汐周期（秒）：半日潮 12 小时 25 分。 */
    private static final double TIDE_PERIOD_S = 12.42 * 3600.0;

    /**
     * 由「世界开启后经过的游戏秒」派生环境。
     *
     * <p>季节按一年 365 天均分（前工业世界的常识），潮汐按半日潮正弦，
     * 天气按「日序号 + 种子」确定性抽取并做季节加权（雨季多雨、冬季多雾雪）。
     * 昼夜用太阳高度角近似：日出 6 点、日落 18 点，正午最高 55°（中纬度）。</p>
     *
     * @param gameSecond 世界开启后经过的游戏秒（可为负/过大，内部取模）
     * @param seed       地图种子（天气抽取的稳定盐）
     * @return 环境快照
     */
    public static MapAmbient of(long gameSecond, long seed) {
        long sec = Math.floorMod(gameSecond, SECONDS_PER_DAY * 365L);
        int dayIndex = (int) (sec / SECONDS_PER_DAY);
        long secOfDay = Math.floorMod(gameSecond, SECONDS_PER_DAY);
        int minuteOfDay = (int) (secOfDay / 60);
        int hour = minuteOfDay / 60;

        // 季节：每季 91 天（3 个月），第一天算春分后
        int dayOfYear = dayIndex % 365 + 1;
        String season;
        String seasonName;
        if (dayOfYear <= 91) {
            season = "spring";
            seasonName = "春";
        } else if (dayOfYear <= 182) {
            season = "summer";
            seasonName = "夏";
        } else if (dayOfYear <= 273) {
            season = "autumn";
            seasonName = "秋";
        } else {
            season = "winter";
            seasonName = "冬";
        }

        // 昼夜与太阳：日出 6:00、日落 18:00；高度角按正弦近似
        boolean night = hour < 6 || hour >= 18;
        double dayFrac = Math.max(0, Math.min(1, (minuteOfDay - 360) / 720.0));
        double sunEl = night ? -12 : Math.sin(dayFrac * Math.PI) * 55.0;
        // 太阳方位：上午在东、下午在西（极简模型，够给前端画光影方向）
        double sunAz = night ? 0 : 90 + dayFrac * 180;

        // 潮汐：半日潮，两次高潮之间约 12h25m
        double tidePhase = Math.floorMod((long) (secOfDay * 1000), (long) (TIDE_PERIOD_S * 1000))
                / (TIDE_PERIOD_S * 1000.0);
        double tide = 0.5 - 0.5 * Math.cos(tidePhase * Math.PI * 2);
        String tideName = tide > 0.85 ? "满潮" : tide < 0.15 ? "干潮"
                : tidePhase < 0.5 ? "涨潮" : "退潮";

        // 天气：按日抽一次，季节加权
        int daySalt = (int) ((seed * 31 + dayIndex) & 0x7FFFFFFF);
        double roll = hash01(daySalt);
        String weather;
        String weatherName;
        if ("winter".equals(season)) {
            weather = roll < 0.30 ? "fog" : roll < 0.55 ? "cloudy" : roll < 0.70 ? "rain" : "clear";
        } else if ("summer".equals(season)) {
            weather = roll < 0.28 ? "rain" : roll < 0.48 ? "cloudy" : roll < 0.58 ? "wind" : "clear";
        } else if ("spring".equals(season)) {
            weather = roll < 0.32 ? "rain" : roll < 0.52 ? "cloudy" : "clear";
        } else {
            weather = roll < 0.24 ? "fog" : roll < 0.44 ? "cloudy" : roll < 0.56 ? "wind" : "clear";
        }
        if (roll < 0.04) {
            weather = "fog";
        }
        weatherName = switch (weather) {
            case "rain" -> "雨";
            case "cloudy" -> "阴";
            case "fog" -> "雾";
            case "wind" -> "风";
            default -> "晴";
        };
        // 湿度：雨天最高，雾天次之，晴天最低；夜里再高一点（露水）
        double wetness = switch (weather) {
            case "rain" -> 0.85 + 0.1 * hash01(daySalt + 7);
            case "fog" -> 0.62;
            case "cloudy" -> 0.40;
            case "wind" -> 0.22;
            default -> 0.16;
        };
        if (night) {
            wetness = Math.min(1, wetness + 0.08);
        }

        // 气温：季均 + 日较差（夜里低），再按天气修正
        double base = switch (season) {
            case "summer" -> 26.0;
            case "winter" -> 2.0;
            case "spring" -> 14.0;
            default -> 16.0;
        };
        double diurnal = night ? -4.5 : Math.sin(dayFrac * Math.PI) * 5.0 - 1.0;
        double weatherAdj = "rain".equals(weather) ? -2.5 : "fog".equals(weather) ? -1.5
                : "cloudy".equals(weather) ? -1.0 : 0.0;
        double temp = Math.round((base + diurnal + weatherAdj) * 10.0) / 10.0;

        // 季节色：春嫩绿、夏深绿偏暖、秋金黄、冬灰冷
        String tint = switch (season) {
            case "spring" -> "#8fbf6a";
            case "summer" -> "#7fa855";
            case "autumn" -> "#c8a457";
            default -> "#9fb0bd";
        };
        double tintStrength = switch (season) {
            case "autumn" -> 0.16;
            case "winter" -> 0.14;
            default -> 0.08;
        };
        return new MapAmbient(season, seasonName, weather, weatherName, dayOfYear, dayIndex,
                minuteOfDay, hour, night, Math.round(tide * 1000) / 1000.0, tideName,
                Math.round(wetness * 1000) / 1000.0, temp, sunAz, Math.round(sunEl * 10) / 10.0,
                tint, tintStrength);
    }

    /** 确定性 0~1 哈希。 */
    private static double hash01(int seed) {
        int h = seed * 1103515245 + 12345;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }

    /**
     * 落库形态（存 {@code actor_world_map.ambient_json}，并原样进 bundle）。
     *
     * @return 可序列化 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", season);
        m.put("seasonName", seasonName);
        m.put("weather", weather);
        m.put("weatherName", weatherName);
        m.put("dayOfYear", dayOfYear);
        m.put("dayIndex", dayIndex);
        m.put("minuteOfDay", minuteOfDay);
        m.put("hour", hour);
        m.put("isNight", isNight);
        m.put("tide", tide);
        m.put("tideName", tideName);
        m.put("wetness", wetness);
        m.put("temperatureC", temperatureC);
        m.put("sunAzimuthDeg", sunAzimuthDeg);
        m.put("sunElevationDeg", sunElevationDeg);
        m.put("groundTint", groundTint);
        m.put("tintStrength", tintStrength);
        return m;
    }
}
