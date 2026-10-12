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
import java.util.List;
import java.util.Map;

/**
 * 世界帧边界校验器。
 * <p>职责：AI 生成的世界帧在落库前执行边界校验（主开发方案 §4.3）——</p>
 * <ul>
 * <li><b>规则① 禁 NPC 行为</b>：世界帧只描述「世界的状态」，不允许出现 NPC 名称 / 「XX 做了什么」——
 * （世界层只提供势与信息输入，不替 NPC 做决定）。扫描帧内全部文本字段（宏观事件/信息包/
 * 环境变化/天气），命中项目特殊 NPC 名称 → 校验失败（调用方回退机制兜底帧，世界演化不中断）。</li>
 * <li><b>规则② 世界观越界</b>：超出世界观内容的表达（可配关键词，默认空=不校验；
 * 以规则为主，AI 校验器二次判定留后续）。</li>
 * <li><b>规则③ 信息包字段完整</b>：缺 origin/affected_area/spread_speed/valid_until/
 * distortion_rules → 按默认补全（{@link #normalize}）或丢弃（origin/content 缺失在解析阶段已丢）。</li>
 * <li><b>规则④ 前日矛盾</b>：与前一日帧矛盾—— 为占位（仅记录级，不做自动修正；
 * 高严重度矛盾修正留后续里程碑）。</li>
 * </ul>
 * <p>归一化 {@link #normalize}：对校验通过（或机制兜底）的帧做字段级补全——severity 钳制 1~5、
 * 信息包 spread_speed_days_per_km 缺省 1.0（天/公里）、valid_until_day 缺省 当前日+3、
 * reliability 缺省 0.8 并钳制 0~1、distortion_rules/visibility_condition 空列表兜底、文本截断。</p>
 */
public final class WorldFrameValidator {

    /** 文本字段最大长度（防 AI 输出超长刷屏/爆库） */
    private static final int TEXT_MAX = 500;

    /** 信息包传播速度缺省（天/公里） */
    private static final double DEFAULT_SPREAD_SPEED = 1.0;

    /** 信息包可信度缺省 */
    private static final double DEFAULT_RELIABILITY = 0.8;

    /** 信息包有效天数缺省（当前游戏日 + 该值） */
    private static final long DEFAULT_VALID_DAYS = 3;

    private WorldFrameValidator() {
    }

    /** 校验结果（passed=false 时调用方回退机制兜底帧） */
    public record ValidationResult(boolean passed, String reason) {
        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * 边界校验主入口（规则①②；规则③由 normalize 承载补全、规则④为占位）。
     *
     * @param characterNames 项目特殊 NPC 名称列表（规则① 禁 NPC 行为；null/空=跳过名称检测）
     *
     * @param bannedKeywords 世界观越界关键词（规则②；null/空=不校验）
     *
     * @param frame 待校验帧（null → 失败）
     *
     * @return 校验结果（passed=true 才允许落库）
     */
    public static ValidationResult validate(List<String> characterNames, List<String> bannedKeywords,
                                            WorldFrameParse.FrameJson frame) {
        if (frame == null) {
            return ValidationResult.fail("世界帧为空");
        }
        // 规则①：禁 NPC 行为——帧文本命中项目 NPC 名称即违规（世界帧不描述角色行为）
        String violation = findNpcNameViolation(characterNames, frame);
        if (violation != null) {
            return ValidationResult.fail("世界帧出现 NPC 名称/行为表述，违规：" + violation);
        }
        // 规则②：世界观越界关键词
        String keyword = findBannedKeyword(bannedKeywords, frame);
        if (keyword != null) {
            return ValidationResult.fail("世界帧超出世界观内容，命中关键词：" + keyword);
        }
        // 规则③/④：信息包字段缺失在 normalize 补全；前日矛盾 为占位（不做自动修正）
        return ValidationResult.pass();
    }

    /**
     * 归一化：对校验通过/机制兜底的帧做字段级补全与钳制（返回值非空，恒可落库）。
     *
     * @param frame 原始帧（null 视为空帧）
     *
     * @param gameDay 帧所属游戏日（valid_until_day 缺省基准）
     *
     * @return 归一化后的帧
     */
    public static WorldFrameParse.FrameJson normalize(WorldFrameParse.FrameJson frame, long gameDay) {
        if (frame == null) {
            return WorldFrameParse.FrameJson.empty(gameDay);
        }
        // 天气：文本截断
        Map<String, WorldFrameParse.WeatherEntry> weather = new java.util.LinkedHashMap<>();
        if (frame.weather() != null) {
            frame.weather().forEach((region, w) -> {
                if (region != null && !region.isBlank() && w != null) {
                    weather.put(truncate(region, 50),
                            new WorldFrameParse.WeatherEntry(truncate(w.condition(), 30),
                                    w.temperature() == null ? null : w.temperature(), truncate(w.wind(), 30)));
                }
            });
        }
        // 宏观事件：severity 钳制 1~5 + 文本截断
        List<WorldFrameParse.MacroEvent> macroEvents = new ArrayList<>();
        if (frame.macroEvents() != null) {
            for (WorldFrameParse.MacroEvent e : frame.macroEvents()) {
                macroEvents.add(new WorldFrameParse.MacroEvent(
                        truncate(e.eventId(), 50),
                        truncate(e.type(), 30),
                        truncate(e.location(), 50),
                        clamp(e.severity(), 1, 5),
                        truncate(e.summary(), TEXT_MAX),
                        e.startDay() == null ? null : e.startDay(),
                        e.durationDays() == null ? null : e.durationDays(),
                        truncateList(e.affectedArea(), 30)));
            }
        }
        // 信息包：扩散参数缺省补全 + 可信度钳制 + 文本截断
        List<WorldFrameParse.InfoPackage> packets = new ArrayList<>();
        if (frame.infoPackages() != null) {
            for (WorldFrameParse.InfoPackage p : frame.infoPackages()) {
                packets.add(normalizePacket(p, gameDay));
            }
        }
        // 环境变化：文本截断
        List<WorldFrameParse.EnvChange> env = new ArrayList<>();
        if (frame.environmentChanges() != null) {
            for (WorldFrameParse.EnvChange e : frame.environmentChanges()) {
                env.add(new WorldFrameParse.EnvChange(truncate(e.scene(), 50), truncate(e.change(), TEXT_MAX),
                        truncate(e.effect(), TEXT_MAX)));
            }
        }
        return new WorldFrameParse.FrameJson(
                gameDay,
                weather.isEmpty() ? defaultWeather() : weather,
                macroEvents,
                packets,
                env,
                truncate(frame.previousDaySummary(), 1000),
                frame.ongoingEvents() == null ? new ArrayList<>() : frame.ongoingEvents());
    }

    /** 信息包归一化（规则③ 字段补全）。 */
    private static WorldFrameParse.InfoPackage normalizePacket(WorldFrameParse.InfoPackage p, long gameDay) {
        double speed = p.spreadSpeedDaysPerKm() == null || p.spreadSpeedDaysPerKm() <= 0
                ? DEFAULT_SPREAD_SPEED : p.spreadSpeedDaysPerKm();
        long validUntil = p.validUntilDay() == null || p.validUntilDay() < gameDay
                ? gameDay + DEFAULT_VALID_DAYS : p.validUntilDay();
        double reliability = p.reliability() == null ? DEFAULT_RELIABILITY
                : Math.max(0.0, Math.min(1.0, p.reliability()));
        return new WorldFrameParse.InfoPackage(
                truncate(p.infoId(), 50),
                truncate(p.origin(), 100),
                truncate(p.content(), TEXT_MAX),
                reliability,
                truncateList(p.spreadChannels(), 30),
                speed,
                validUntil,
                truncateList(p.distortionRules(), 200),
                p.visibilityCondition() == null ? null : new WorldFrameParse.VisibilityCondition(
                        truncateList(p.visibilityCondition().profession(), 30),
                        truncateList(p.visibilityCondition().affiliation(), 30),
                        truncateList(p.visibilityCondition().location(), 30)));
    }

    /** 规则① 检测：帧内任意文本字段命中 NPC 名称 → 返回命中文本（未命中 null）。 */
    static String findNpcNameViolation(List<String> characterNames, WorldFrameParse.FrameJson frame) {
        if (characterNames == null || characterNames.isEmpty() || frame == null) {
            return null;
        }
        List<String> names = characterNames.stream()
                .filter(n -> n != null && n.length() >= 2).toList(); // 单字名误命中率高，跳过
        if (names.isEmpty()) {
            return null;
        }
        // 收集帧内全部文本字段
        List<String> texts = new ArrayList<>();
        if (frame.macroEvents() != null) {
            for (WorldFrameParse.MacroEvent e : frame.macroEvents()) {
                texts.add(e.summary());
                texts.add(e.type());
                texts.add(e.location());
            }
        }
        if (frame.infoPackages() != null) {
            for (WorldFrameParse.InfoPackage p : frame.infoPackages()) {
                texts.add(p.content());
                texts.add(p.origin());
            }
        }
        if (frame.environmentChanges() != null) {
            for (WorldFrameParse.EnvChange e : frame.environmentChanges()) {
                texts.add(e.change());
                texts.add(e.scene());
                texts.add(e.effect());
            }
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String name : names) {
                if (text.contains(name)) {
                    return abbreviate(text, 60);
                }
            }
        }
        return null;
    }

    /** 规则② 检测：帧文本命中世界观越界关键词 → 返回命中关键词（未命中 null）。 */
    static String findBannedKeyword(List<String> bannedKeywords, WorldFrameParse.FrameJson frame) {
        if (bannedKeywords == null || bannedKeywords.isEmpty() || frame == null) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        if (frame.macroEvents() != null) {
            frame.macroEvents().forEach(e -> texts.add(e.summary()));
        }
        if (frame.infoPackages() != null) {
            frame.infoPackages().forEach(p -> texts.add(p.content()));
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String kw : bannedKeywords) {
                if (kw != null && !kw.isBlank() && text.contains(kw)) {
                    return kw;
                }
            }
        }
        return null;
    }

    /** 天气缺省（无任何天气区域时兜底：主区域晴）。 */
    static Map<String, WorldFrameParse.WeatherEntry> defaultWeather() {
        Map<String, WorldFrameParse.WeatherEntry> m = new java.util.LinkedHashMap<>();
        m.put("主区域", new WorldFrameParse.WeatherEntry("晴", null, null));
        return m;
    }

    /** 文本截断（null/空白返回原值）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 列表逐项截断（null 返回空列表）。 */
    private static List<String> truncateList(List<String> list, int max) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (String s : list) {
            String t = truncate(s, max);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** 数值钳制到 [min, max]。 */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
