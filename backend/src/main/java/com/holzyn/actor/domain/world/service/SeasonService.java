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

import com.holzyn.actor.domain.world.entity.ActorWorldClimateConfig;
import com.holzyn.actor.domain.world.repository.ActorWorldClimateConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 季节服务。
 * <p>职责：
 * <b>季节 = 按游戏月固定绑定</b>（1-3 春 / 4-6 夏 / 7-9 秋 / 10-12 冬，用户 决策#6
 * 「固定死的，AI 不决定季节归属」）+ <b>骨架内波动</b>（每日天气/气温在季节骨架内波动，由
 * 世界帧 AI 生成承载；波动不改变季节归属）。提供：</p>
 * <ul>
 * <li><b>日历解析</b>：游戏秒 → 游戏月/季节（纯静态可单测）；季节中文名/色调。</li>
 * <li><b>四季骨架</b>：内置默认骨架（权威基线，daylightOffsetMin=0 保持日出日落查表口径——
 * 设计文档 §5.1 例表里 daylightOffsetMin 为示例值，与查表重复叠加会破坏「冬 17:30 黑天」
 * 口径；因此内置骨架统一 0，项目自定义骨架可覆盖做整体平移）；项目级可覆盖
 * （actor_world_climate_config 新表，GET/PUT climate-config 接口）。</li>
 * <li><b>日出日落查表</b>：季节基准日出/日落 + 每日确定性波动 ±15 分钟（按游戏日取模，
 * 同一天内所有查询一致）+ 骨架 daylightOffsetMin 平移；春季 18:30 日落、冬季 17:30
 * 日落（用户示例：冬 18:00 已全黑、夏 18:00 天还亮）。</li>
 * <li><b>天气倾向池</b>：机制兜底帧按季节 weatherTendency 确定性选天气（游戏日取模加权）。</li>
 * <li><b>气温口径</b>：季节基准温度中值 + 天气条件修正（晴+2/雨-3/雷雨-5/雪-8/阴-1…）；
 * 天气切换「雪」按季节修正（冬=季基准-8，非冬反常=固定 5℃）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SeasonService {

    // ==================== 季节常量 ====================

    /** 季节 code：春 */
    public static final String SPRING = "spring";
    /** 季节 code：夏 */
    public static final String SUMMER = "summer";
    /** 季节 code：秋 */
    public static final String AUTUMN = "autumn";
    /** 季节 code：冬 */
    public static final String WINTER = "winter";

    /** 季节中文名（展示/事件标题用） */
    public static final Map<String, String> SEASON_NAMES = Map.of(
            SPRING, "春", SUMMER, "夏", AUTUMN, "秋", WINTER, "冬");

    /** 季节色调（前端/文案可选；骨架 colorTone 覆盖） */
    public static final Map<String, String> SEASON_TONES = Map.of(
            SPRING, "新绿", SUMMER, "浓绿", AUTUMN, "金黄", WINTER, "素白");

    /** 季节特征一句话（季节更替事件文案） */
    public static final Map<String, String> SEASON_FEATURES = Map.of(
            SPRING, "万物复苏，草木萌发，溪水回暖",
            SUMMER, "暑气渐盛，蝉鸣四起，草木繁茂",
            AUTUMN, "天高气爽，五谷渐熟，草木转黄",
            WINTER, "寒气袭人，朔风渐紧，天地肃杀");

    // ==================== 日历常量（与 WorldClockService 同源：1 月=30 日，1 年=12 月） ====================

    /** 1 月秒数（30 日） */
    static final long SECONDS_PER_MONTH = 30L * 86400L;
    /** 1 日秒数 */
    static final long SECONDS_PER_DAY = 86400L;

    // ==================== 日出日落基准查表（分钟数；用户示例：冬 18:00 已全黑/夏 18:00 天还亮） ====================

    /** 各季节基准日出分钟（春 06:00 / 夏 05:00 / 秋 06:00 / 冬 07:00） */
    private static final Map<String, Integer> BASE_SUNRISE_MIN = Map.of(
            SPRING, 6 * 60, SUMMER, 5 * 60, AUTUMN, 6 * 60, WINTER, 7 * 60);
    /** 各季节基准日落分钟（春 18:30 / 夏 20:00 / 秋 18:00 / 冬 17:30） */
    private static final Map<String, Integer> BASE_SUNSET_MIN = Map.of(
            SPRING, 18 * 60 + 30, SUMMER, 20 * 60, AUTUMN, 18 * 60, WINTER, 17 * 60 + 30);

    /** 每日确定性波动 ±15 分钟（按游戏日取模轮换，同一天全项目一致） */
    private static final int[] DAILY_WAVE_MIN = {-15, -5, 5, 15};

    // ==================== 天气条件 → 气温修正（体感气温/机制帧共用口径） ====================

    /**
     * 天气条件气温修正：晴 +2 / 雨 -3 / 雷雨 -5 / 雪 -8 / 阴 -1 / 多云 -1 / 微风 +1。
     * 与设计文档 §5.2 ③ 体感气温「季基准 + 天气修正」口径一致。
     *
     * @param condition 天气条件（可空/未知 → 0）
     *
     * @return 修正量
     */
    public static double conditionTempOffset(String condition) {
        if (condition == null) {
            return 0;
        }
        return switch (condition.trim()) {
            case "晴", "晴间多云" -> 2;
            case "雨", "小雨", "中雨", "大雨" -> -3;
            case "雷雨", "暴雨" -> -5;
            case "雪", "小雪", "中雪", "大雪" -> -8;
            case "阴" -> -1;
            case "多云" -> -1;
            case "微风", "微风晴", "风" -> 1;
            default -> 0;
        };
    }

    /**
     * 季节修正气温：季基准中值 + 天气修正（机制帧/天气切换填充温度用）。
     *
     * @param season 季节 code
     *
     * @param baseMid 该季基准温度中值
     *
     * @param condition 天气条件
     *
     * @return 修正后气温
     */
    public static double seasonAdjustedTemp(String season, double baseMid, String condition) {
        return baseMid + conditionTempOffset(condition);
    }

    /**
     * 天气切换「雪」的目标气温：冬季=季基准-8（雪在冬季为正常形态）；
     * 非冬季=固定 5℃（反季节飞雪按「骤降温」处理——：允许但按季节修正气温）。
     *
     * @param season 当前季节 code
     *
     * @param baseMid 该季基准温度中值
     *
     * @return 目标气温
     */
    public static double snowSwitchTemp(String season, double baseMid) {
        return WINTER.equals(season) ? baseMid - 8 : 5.0;
    }

    // ==================== 纯静态日历解析（可单测） ====================

    /**
     * 游戏秒 → 游戏月（1-12；1 年=12 月×30 日，与 WorldClockService.formatWorldTime0 同源）。
     *
     * @param gameSecond 游戏总秒数（负数按 0）
     *
     * @return 游戏月 1-12
     */
    public static int monthOf(Long gameSecond) {
        long s = gameSecond == null || gameSecond < 0 ? 0L : gameSecond;
        return (int) (s / SECONDS_PER_MONTH) % 12 + 1;
    }

    /**
     * 游戏秒 → 游戏日当月日（1-30）。
     *
     * @param gameSecond 游戏总秒数
     *
     * @return 当月日 1-30
     */
    public static int dayOfMonth(Long gameSecond) {
        long s = gameSecond == null || gameSecond < 0 ? 0L : gameSecond;
        return (int) (s / SECONDS_PER_DAY) % 30 + 1;
    }

    /**
     * 游戏秒 → 当日小时（0-23）。
     *
     * @param gameSecond 游戏总秒数
     *
     * @return 当日小时 0-23
     */
    public static int hourOfDay(Long gameSecond) {
        long s = gameSecond == null || gameSecond < 0 ? 0L : gameSecond;
        return (int) (s / 3600L) % 24;
    }

    /**
     * 游戏秒 → 当日分钟（0-1439）。
     *
     * @param gameSecond 游戏总秒数
     *
     * @return 当日分钟 0-1439
     */
    public static int minuteOfDay(Long gameSecond) {
        long s = gameSecond == null || gameSecond < 0 ? 0L : gameSecond;
        return (int) (s / 60L) % (24 * 60);
    }

    /**
     * 游戏月 → 季节（固定绑定， #6：1-3 春 / 4-6 夏 / 7-9 秋 / 10-12 冬）。
     *
     * @param month 游戏月 1-12
     *
     * @return 季节 code（month 非法 → autumn 兜底）
     */
    public static String seasonOfMonth(int month) {
        if (month < 1 || month > 12) {
            return AUTUMN; // 非法月兜底（中性季节）
        }
        if (month <= 3) return SPRING;
        if (month <= 6) return SUMMER;
        if (month <= 9) return AUTUMN;
        return WINTER;
    }

    /**
     * 游戏秒 → 季节（固定绑定）。
     *
     * @param gameSecond 游戏总秒数
     *
     * @return 季节 code
     */
    public static String seasonOf(Long gameSecond) {
        return seasonOfMonth(monthOf(gameSecond));
    }

    /**
     * 季节中文名（春/夏/秋/冬；未知回退「未知」）。
     *
     * @param season 季节 code
     *
     * @return 中文名
     */
    public static String seasonDisplayName(String season) {
        if (season == null) {
            return "未知";
        }
        return SEASON_NAMES.getOrDefault(season.trim(), "未知");
    }

    /**
     * 当日日出分钟（季节查表 + 每日确定性波动 ±15 + 骨架 daylightOffsetMin 平移）。
     *
     * @param skeleton 季节骨架（可空=用 0 偏移）
     *
     * @param season 季节 code
     *
     * @param gameDay 游戏日序号（自纪元起；波动种子）
     *
     * @return 日出分钟（0-1439）
     */
    public static int sunriseMinuteOf(SeasonSkeleton skeleton, String season, Long gameDay) {
        int base = BASE_SUNRISE_MIN.getOrDefault(season, BASE_SUNRISE_MIN.get(AUTUMN));
        return clampMinute(base + dailyWave(gameDay) + (skeleton == null ? 0 : skeleton.daylightOffsetMin()));
    }

    /**
     * 当日日落分钟（口径同上）。
     *
     * @param skeleton 季节骨架
     *
     * @param season 季节 code
     *
     * @param gameDay 游戏日序号
     *
     * @return 日落分钟（0-1439）
     */
    public static int sunsetMinuteOf(SeasonSkeleton skeleton, String season, Long gameDay) {
        int base = BASE_SUNSET_MIN.getOrDefault(season, BASE_SUNSET_MIN.get(AUTUMN));
        return clampMinute(base + dailyWave(gameDay) + (skeleton == null ? 0 : skeleton.daylightOffsetMin()));
    }

    /** 每日确定性波动（-15/-5/+5/+15，按游戏日取模；同一天内一致） */
    private static int dailyWave(Long gameDay) {
        long d = gameDay == null ? 0L : gameDay;
        return DAILY_WAVE_MIN[Math.floorMod(d, DAILY_WAVE_MIN.length)];
    }

    /** 分钟钳制到 [0, 1439]（跨午夜平移安全） */
    private static int clampMinute(int m) {
        if (m < 0) return 0;
        return Math.min(m, 24 * 60 - 1);
    }

    /** 分钟 → "HH:MM" 文本。 */
    public static String minuteToText(int minuteOfDay) {
        int h = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        return String.format("%02d:%02d", h, m);
    }

    /** 季节默认风向（机制帧 wind 兜底）：春东风/夏南风/秋西风/冬北风。 */
    public static String windOf(String season) {
        if (season == null) {
            return null;
        }
        return switch (season) {
            case SPRING -> "东风";
            case SUMMER -> "南风";
            case AUTUMN -> "西风";
            case WINTER -> "北风";
            default -> null;
        };
    }

    // ==================== 骨架模型 ====================

    /**
     * 季节骨架（内置默认 / 项目覆盖统一模型）。
     *
     * @param season 季节 code
     *
     * @param baseTempLo 基准低温
     *
     * @param baseTempHi 基准高温
     *
     * @param weatherTendency 天气倾向（条件 → 权重）
     *
     * @param daylightOffsetMin 日出日落平移（分钟；内置默认 0）
     *
     * @param colorTone 色调文案
     */
    public record SeasonSkeleton(String season, double baseTempLo, double baseTempHi,
                                 Map<String, Integer> weatherTendency, int daylightOffsetMin, String colorTone) {

        /** 基准温度中值 */
        public double baseTempMid() {
            return (baseTempLo + baseTempHi) / 2.0;
        }

        /** 温度区间文本（如 "12~22℃"） */
        public String tempRangeText() {
            return ((int) baseTempLo) + "~" + ((int) baseTempHi) + "℃";
        }
    }

    /**
     * 内置默认骨架（权威基线，各季基准温度区间 + 天气倾向 + 日出日落平移 0 +
     * 色调；对齐设计文档 §5.1 例表，weatherTendency 权重为百位内整数；
     * 倾向用 LinkedHashMap 保证确定性迭代顺序——机制帧选样/提示词展示依赖稳定顺序）。
     */
    public static Map<String, SeasonSkeleton> defaultSkeleton() {
        Map<String, SeasonSkeleton> m = new LinkedHashMap<>();
        Map<String, Integer> spring = new LinkedHashMap<>();
        spring.put("晴", 40); spring.put("雨", 35); spring.put("阴", 20); spring.put("风", 5);
        Map<String, Integer> summer = new LinkedHashMap<>();
        summer.put("晴", 45); summer.put("雷雨", 25); summer.put("阴", 20); summer.put("风", 10);
        Map<String, Integer> autumn = new LinkedHashMap<>();
        autumn.put("晴", 40); autumn.put("雨", 25); autumn.put("阴", 25); autumn.put("风", 10);
        Map<String, Integer> winter = new LinkedHashMap<>();
        winter.put("晴", 30); winter.put("雪", 35); winter.put("阴", 25); winter.put("风", 10);
        m.put(SPRING, new SeasonSkeleton(SPRING, 12, 22, spring, 0, "新绿"));
        m.put(SUMMER, new SeasonSkeleton(SUMMER, 26, 34, summer, 0, "浓绿"));
        m.put(AUTUMN, new SeasonSkeleton(AUTUMN, 14, 24, autumn, 0, "金黄"));
        m.put(WINTER, new SeasonSkeleton(WINTER, -5, 6, winter, 0, "素白"));
        return m;
    }

    // ==================== 实例：项目级骨架配置解析 ====================

    /** 骨架配置仓库（字段注入保持构造器不变，null=未装配只读默认骨架） */
    @Autowired(required = false)
    private ActorWorldClimateConfigRepository climateConfigRepository;

    /** JSON 解析器 */
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /**
     * 解析项目季节骨架（无配置行/解析失败回退内置默认骨架）。
     *
     * @param projectId 项目 ID（null → 内置默认）
     *
     * @param season 季节 code
     *
     * @return 季节骨架（恒非空）
     */
    public SeasonSkeleton skeletonOf(Long projectId, String season) {
        Map<String, SeasonSkeleton> defaults = defaultSkeleton();
        if (season == null) {
            return defaults.get(AUTUMN);
        }
        String s = season.trim();
        if (projectId == null || climateConfigRepository == null || objectMapper == null) {
            return defaults.getOrDefault(s, defaults.get(AUTUMN));
        }
        try {
            ActorWorldClimateConfig cfg = climateConfigRepository.findByProjectId(projectId).orElse(null);
            if (cfg == null || cfg.getConfigJson() == null || cfg.getConfigJson().isBlank()) {
                return defaults.getOrDefault(s, defaults.get(AUTUMN));
            }
            JsonNode root = objectMapper.readTree(cfg.getConfigJson());
            if (root == null || !root.isObject()) {
                return defaults.getOrDefault(s, defaults.get(AUTUMN));
            }
            JsonNode node = root.path(s);
            if (node == null || !node.isObject()) {
                return defaults.getOrDefault(s, defaults.get(AUTUMN));
            }
            SeasonSkeleton custom = parseSkeleton(node, s);
            return custom != null ? custom : defaults.getOrDefault(s, defaults.get(AUTUMN));
        } catch (Exception e) {
            log.warn("[季节] 项目骨架解析失败，回退内置默认: project={} season={} : {}", projectId, s, e.getMessage());
            return defaults.getOrDefault(s, defaults.get(AUTUMN));
        }
    }

    /**
     * 解析单季节骨架节点（容错：字段缺失/类型异常回退默认单字段）。
     *
     * @param node JSON 节点
     *
     * @param season 季节 code
     *
     * @return 骨架（节点非法返回 null，调用方回退默认）
     */
    static SeasonSkeleton parseSkeleton(JsonNode node, String season) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode temp = node.path("baseTempC");
        double lo = temp != null && temp.isArray() && temp.size() >= 2 && temp.get(0).isNumber()
                ? temp.get(0).asDouble() : 10;
        double hi = temp != null && temp.isArray() && temp.size() >= 2 && temp.get(1).isNumber()
                ? temp.get(1).asDouble() : 20;
        if (hi < lo) {
            double t = hi;
            hi = lo;
            lo = t;
        }
        Map<String, Integer> tendency = new LinkedHashMap<>();
        JsonNode tnd = node.path("weatherTendency");
        if (tnd != null && tnd.isObject()) {
            for (Map.Entry<String, JsonNode> e : tnd.properties()) {
                if (e.getValue() != null && e.getValue().isNumber()) {
                    tendency.put(e.getKey(), e.getValue().asInt());
                }
            }
        }
        if (tendency.isEmpty()) {
            tendency = defaultSkeleton().get(season).weatherTendency();
        }
        int offset = node.path("daylightOffsetMin").isNumber()
                ? node.path("daylightOffsetMin").asInt() : 0;
        String tone = node.path("colorTone").isTextual()
                ? node.path("colorTone").asText().trim() : SEASON_TONES.getOrDefault(season, "如常");
        return new SeasonSkeleton(season, lo, hi, tendency, offset, tone);
    }

    /**
     * 项目+季节 → 天气倾向（条件 → 权重；空 → 季节默认）。
     *
     * @param projectId 项目 ID
     *
     * @param season 季节 code
     *
     * @return 天气倾向 Map
     */
    public Map<String, Integer> weatherTendencyOf(Long projectId, String season) {
        return skeletonOf(projectId, season).weatherTendency();
    }

    /**
     * 机制帧天气选择：按季节天气倾向权重 + 游戏日取模确定性加权抽样。
     * 倾向键（晴/雨/雷雨/雪/阴/风）归一化为帧天气条件（雨→小雨、雪→小雪、风→微风、雷雨→雷雨）。
     *
     * @param projectId 项目 ID
     *
     * @param season 季节 code
     *
     * @param gameDay 游戏日序号（取模种子）
     *
     * @return 帧天气条件（恒非空；倾向空回退「多云」）
     */
    public String pickMechanismWeather(Long projectId, String season, Long gameDay) {
        Map<String, Integer> tendency = weatherTendencyOf(projectId, season);
        if (tendency.isEmpty()) {
            return "多云";
        }
        List<String> keys = tendency.keySet().stream().toList();
        int total = tendency.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            total = 1;
        }
        long d = gameDay == null ? 0L : gameDay;
        int pick = (int) Math.floorMod(d, total);
        int acc = 0;
        String raw = keys.get(0);
        for (String k : keys) {
            acc += Math.max(0, tendency.getOrDefault(k, 0));
            if (pick < acc) {
                raw = k;
                break;
            }
        }
        return normalizeCondition(raw);
    }

    /** 天气倾向键 → 帧天气条件（雨→小雨 / 雪→小雪 / 风→微风 / 雷雨→雷雨；其余原样）。 */
    static String normalizeCondition(String raw) {
        if (raw == null) {
            return "多云";
        }
        return switch (raw.trim()) {
            case "雨" -> "小雨";
            case "雪" -> "小雪";
            case "风" -> "微风";
            default -> raw.trim();
        };
    }

    /**
     * 项目是否存在自定义骨架配置行（来源判定；仓库未装配/异常 → false）。
     *
     * @param projectId 项目 ID
     *
     * @return true=存在配置行
     */
    public boolean configExists(Long projectId) {
        if (projectId == null || climateConfigRepository == null) {
            return false;
        }
        try {
            return climateConfigRepository.findByProjectId(projectId).isPresent();
        } catch (Exception e) {
            log.warn("[季节] 配置存在性检查失败（按不存在处理）: project={} : {}", projectId, e.getMessage());
            return false;
        }
    }

    /**
     * 保存项目骨架配置（PUT /climate-config 落库；已存在则更新 config_json 并版本 +1）。
     * 入参已由控制器校验为含四季键的合法 JSON 对象。
     *
     * @param projectId 项目 ID
     *
     * @param configJson 骨架 JSON 文本
     */
    public void saveConfig(Long projectId, String configJson) {
        if (projectId == null || climateConfigRepository == null) {
            throw new IllegalStateException("季节配置仓库未装配");
        }
        ActorWorldClimateConfig cfg = climateConfigRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorWorldClimateConfig c = new ActorWorldClimateConfig();
            c.setProjectId(projectId);
            return c;
        });
        cfg.setConfigJson(configJson);
        cfg.setVersion((cfg.getVersion() == null ? 0 : cfg.getVersion()) + 1);
        climateConfigRepository.save(cfg);
        log.info("[季节] 项目骨架配置已保存: project={} version={}", projectId, cfg.getVersion());
    }
}
