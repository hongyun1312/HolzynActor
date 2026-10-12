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

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界帧 AI 输出解析。
 * <p>职责：把 AI（world_frame_daily 模板，json_object 输出）返回的 JSON 解析为结构化
 * {@link FrameJson}——天气（按区域）/ 宏观事件 / 信息包 / 环境变化 / 前日摘要 / 进行中事件。
 * 容错策略：缺字段/空数组/解析失败按「空值兜底」处理（字段级缺省在
 * {@link WorldFrameValidator#normalize} 归一化时补全）；gameDay 不信任 AI 输出（调用方传入为准）。</p>
 * <p>JSON 结构与主开发方案 §4.3 世界帧示例一致（weather/macro_events/info_packages/
 * environment_changes 四主段）。</p>
 */
public final class WorldFrameParse {

    private WorldFrameParse() {
    }

    /** JSON 解析器（工具类内部持有） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 结构化模型（record） ====================

    /** 世界帧结构化模型（AI/机制双通道的中间表示，落库前归一化） */
    public record FrameJson(Long gameDay,
                            Map<String, WeatherEntry> weather,
                            List<MacroEvent> macroEvents,
                            List<InfoPackage> infoPackages,
                            List<EnvChange> environmentChanges,
                            String previousDaySummary,
                            List<OngoingEvent> ongoingEvents) {
        /** 空帧（所有列表为空/空 Map） */
        public static FrameJson empty(Long gameDay) {
            return new FrameJson(gameDay, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), null, new ArrayList<>());
        }
    }

    /** 天气条目（按区域键） */
    public record WeatherEntry(String condition, Double temperature, String wind) {
    }

    /** 宏观事件 */
    public record MacroEvent(String eventId, String type, String location, int severity, String summary,
                             Long startDay, Long durationDays, List<String> affectedArea) {
    }

    /** 信息包 */
    public record InfoPackage(String infoId, String origin, String content, Double reliability,
                              List<String> spreadChannels, Double spreadSpeedDaysPerKm, Long validUntilDay,
                              List<String> distortionRules, VisibilityCondition visibilityCondition) {
    }

    /** 社会层过滤条件 */
    public record VisibilityCondition(List<String> profession, List<String> affiliation, List<String> location) {
    }

    /** 环境变化 */
    public record EnvChange(String scene, String change, String effect) {
    }

    /** 进行中事件（生成输入缓存） */
    public record OngoingEvent(String type, String location, String summary, Long startDay) {
    }

    // ==================== 主入口 ====================

    /**
     * 解析 AI 输出 JSON 文本 → FrameJson（容错：任意字段缺失/类型异常均空值兜底，不抛异常）。
     *
     * @param json AI 输出 JSON 文本（可含 Markdown 代码块/首尾说明文字，经 JsonUtil.extractJson 提取）
     *
     * @param gameDay 帧所属游戏日（不信任 AI 输出，调用方传入为准）
     *
     * @return FrameJson（JSON 非法返回空帧）
     */
    public static FrameJson parse(String json, Long gameDay) {
        String extracted = JsonUtil.extractJson(json);
        if (extracted == null) {
            return FrameJson.empty(gameDay);
        }
        try {
            JsonNode root = MAPPER.readTree(extracted);
            if (root == null || !root.isObject()) {
                return FrameJson.empty(gameDay);
            }
            return new FrameJson(
                    gameDay,
                    parseWeather(root.get("weather")),
                    parseMacroEvents(root.get("macro_events")),
                    parseInfoPackages(root.get("info_packages")),
                    parseEnvChanges(root.get("environment_changes")),
                    textOf(root.get("previous_day_summary")),
                    parseOngoingEvents(root.get("ongoing_events")));
        } catch (Exception e) {
            return FrameJson.empty(gameDay);
        }
    }

    // ==================== 分字段解析（容错） ====================

    /** 天气：{"区域": {"condition": "晴", "temperature": 15, "wind": "北风"}} */
    private static Map<String, WeatherEntry> parseWeather(JsonNode node) {
        Map<String, WeatherEntry> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.properties().forEach(e -> {
            String region = e.getKey() == null ? "" : e.getKey().trim();
            JsonNode v = e.getValue();
            if (region.isBlank() || v == null || !v.isObject()) {
                return;
            }
            String condition = textOf(v.get("condition"));
            Double temperature = doubleOf(v.get("temperature"));
            String wind = textOf(v.get("wind"));
            // 关键字段缺失的天气区域丢弃（condition 必须有）
            if (condition == null || condition.isBlank()) {
                return;
            }
            out.put(region, new WeatherEntry(condition, temperature, wind));
        });
        return out;
    }

    /** 宏观事件数组 */
    private static List<MacroEvent> parseMacroEvents(JsonNode node) {
        List<MacroEvent> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(item -> {
            if (item == null || !item.isObject()) {
                return;
            }
            String summary = textOf(item.get("summary"));
            String location = textOf(item.get("location"));
            // 摘要必填：无摘要的宏观事件丢弃（不可发布）
            if (summary == null || summary.isBlank()) {
                return;
            }
            out.add(new MacroEvent(
                    textOf(item.get("event_id")),
                    textOf(item.get("type")),
                    location,
                    intOf(item.get("severity"), 3),
                    summary,
                    longOf(item.get("start_day")),
                    longOf(item.get("duration_days")),
                    stringList(item.get("affected_area"))));
        });
        return out;
    }

    /** 信息包数组 */
    private static List<InfoPackage> parseInfoPackages(JsonNode node) {
        List<InfoPackage> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(item -> {
            if (item == null || !item.isObject()) {
                return;
            }
            String origin = textOf(item.get("origin"));
            String content = textOf(item.get("content"));
            // 关键字段必填：origin/content 缺失丢弃（Validator 规则③：不可补全的丢弃）
            if (origin == null || origin.isBlank() || content == null || content.isBlank()) {
                return;
            }
            JsonNode vc = item.get("visibility_condition");
            out.add(new InfoPackage(
                    textOf(item.get("info_id")),
                    origin,
                    content,
                    doubleOf(item.get("reliability")),
                    stringList(item.get("spread_channels")),
                    doubleOf(item.get("spread_speed_days_per_km")),
                    longOf(item.get("valid_until_day")),
                    stringList(item.get("distortion_rules")),
                    vc == null || !vc.isObject() ? null : new VisibilityCondition(
                            stringList(vc.get("profession")),
                            stringList(vc.get("affiliation")),
                            stringList(vc.get("location")))));
        });
        return out;
    }

    /** 环境变化数组 */
    private static List<EnvChange> parseEnvChanges(JsonNode node) {
        List<EnvChange> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(item -> {
            if (item == null || !item.isObject()) {
                return;
            }
            String scene = textOf(item.get("scene"));
            String change = textOf(item.get("change"));
            if (scene == null || scene.isBlank() || change == null || change.isBlank()) {
                return; // scene/change 必填
            }
            out.add(new EnvChange(scene, change, textOf(item.get("effect"))));
        });
        return out;
    }

    /** 进行中事件数组（生成输入缓存） */
    private static List<OngoingEvent> parseOngoingEvents(JsonNode node) {
        List<OngoingEvent> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(item -> {
            if (item == null || !item.isObject()) {
                return;
            }
            String summary = textOf(item.get("summary"));
            if (summary == null || summary.isBlank()) {
                return;
            }
            out.add(new OngoingEvent(
                    textOf(item.get("type")),
                    textOf(item.get("location")),
                    summary,
                    longOf(item.get("start_day"))));
        });
        return out;
    }

    // ==================== 值读取辅助 ====================

    /** 文本值（null/缺失返回 null） */
    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String t = node.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    /** Double 值（缺失/非数值返回 null） */
    private static Double doubleOf(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.doubleValue();
    }

    /** Long 值（缺失/非数值返回 null） */
    private static Long longOf(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.longValue();
    }

    /** int 值（缺失/非法返回缺省） */
    private static int intOf(JsonNode node, int def) {
        if (node == null || !node.isNumber()) {
            return def;
        }
        return node.intValue();
    }

    /** 字符串数组（缺失/非数组返回空列表） */
    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(n -> {
            String t = n == null ? "" : n.asText("").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        });
        return out;
    }
}
