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

import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 世界帧摘要组装。
 * <p>职责：把当前生效世界帧组装为「今日世界状态」紧凑摘要文本（导演上帝视角直读帧数据，
 * 不走 NPC 三层过滤），供演化编排注入（RoleplayService 的 {{world_frame}} 占位 / AI 选角内联段）——
 * 导演编排读世界帧作事实源，不凭空编造世界状态（对齐 State-Grounded / StoryBox 混合编排研究）。</p>
 * <p>组装规则（零 AI、逐段降级）：</p>
 * <ul>
 * <li>天气：≤ {@link #WEATHER_MAX} 区域（「区域 晴 15° 北风」）；</li>
 * <li>宏观事件：≤ {@link #MACRO_MAX} 条（「地点：摘要（重要度N）」）；</li>
 * <li>信息包传闻：≤ {@link #PACKET_MAX} 条（「origin：内容」）；</li>
 * <li>环境变化：≤ {@link #ENV_MAX} 条（「场景：变化」）；</li>
 * <li>总长预算 {@link #TOTAL_BUDGET} 字符，超出硬截断（保持各段内部完整）；全部为空返回空串。</li>
 * </ul>
 */
public final class WorldFrameSummary {

    private WorldFrameSummary() {
    }

    /** 天气区域展示上限 */
    static final int WEATHER_MAX = 6;

    /** 宏观事件展示上限 */
    static final int MACRO_MAX = 3;

    /** 信息包传闻展示上限 */
    static final int PACKET_MAX = 3;

    /** 环境变化展示上限 */
    static final int ENV_MAX = 2;

    /** 摘要总字符预算（超长截断） */
    static final int TOTAL_BUDGET = 400;

    /** JSON 解析器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从已持久化的世界帧实体组装摘要：把四段 JSON 列合并为单对象后
     * 复用 {@link WorldFrameParse#parse} 解析（容错：任何列缺失/非法按空段降级）。
     *
     * @param frame 世界帧实体（可空）
     *
     * @return 紧凑摘要文本（无帧/全空返回空串）
     */
    public static String build(ActorWorldFrame frame) {
        if (frame == null) {
            return "";
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.set("weather", parseNode(frame.getWeatherJson()));
            root.set("macro_events", parseNode(frame.getMacroEventsJson()));
            root.set("info_packages", parseNode(frame.getInfoPacketsJson()));
            root.set("environment_changes", parseNode(frame.getEnvironmentChangesJson()));
            return build(WorldFrameParse.parse(MAPPER.writeValueAsString(root), frame.getGameDay()));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从结构化帧组装摘要（可单测核心入口）。
     *
     * @param frame 世界帧结构化模型（可空）
     *
     * @return 紧凑摘要文本（空帧返回空串）
     */
    public static String build(WorldFrameParse.FrameJson frame) {
        if (frame == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        // 天气（≤6 区域，逐段降级）
        Map<String, WorldFrameParse.WeatherEntry> weather = frame.weather();
        if (weather != null && !weather.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (Map.Entry<String, WorldFrameParse.WeatherEntry> e : weather.entrySet()) {
                if (w.size() >= WEATHER_MAX) {
                    break;
                }
                WorldFrameParse.WeatherEntry v = e.getValue();
                if (v == null || v.condition() == null || v.condition().isBlank()) {
                    continue;
                }
                StringBuilder s = new StringBuilder(e.getKey() == null ? "" : e.getKey()).append(" ").append(v.condition());
                if (v.temperature() != null) {
                    s.append(" ").append(v.temperature().intValue()).append("°");
                }
                if (v.wind() != null && !v.wind().isBlank()) {
                    s.append(" ").append(v.wind());
                }
                w.add(s.toString());
            }
            if (!w.isEmpty()) {
                body.append("· 天气：").append(String.join("、", w)).append("\n");
            }
        }
        // 宏观事件（≤3 条）
        List<WorldFrameParse.MacroEvent> events = frame.macroEvents();
        if (events != null && !events.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (WorldFrameParse.MacroEvent m : events) {
                if (e.size() >= MACRO_MAX) {
                    break;
                }
                if (m == null || m.summary() == null || m.summary().isBlank()) {
                    continue;
                }
                String loc = m.location() == null || m.location().isBlank() ? "" : m.location() + "：";
                e.add(loc + m.summary() + "（重要度" + m.severity() + "）");
            }
            if (!e.isEmpty()) {
                body.append("· 宏观事件：").append(String.join("；", e)).append("\n");
            }
        }
        // 信息包传闻（≤3 条）
        List<WorldFrameParse.InfoPackage> packets = frame.infoPackages();
        if (packets != null && !packets.isEmpty()) {
            List<String> p = new ArrayList<>();
            for (WorldFrameParse.InfoPackage pkg : packets) {
                if (p.size() >= PACKET_MAX) {
                    break;
                }
                if (pkg == null || pkg.content() == null || pkg.content().isBlank()) {
                    continue;
                }
                String origin = pkg.origin() == null || pkg.origin().isBlank() ? "" : pkg.origin() + "：";
                p.add(origin + pkg.content());
            }
            if (!p.isEmpty()) {
                body.append("· 传闻：").append(String.join("；", p)).append("\n");
            }
        }
        // 环境变化（≤2 条）
        List<WorldFrameParse.EnvChange> env = frame.environmentChanges();
        if (env != null && !env.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (WorldFrameParse.EnvChange c : env) {
                if (e.size() >= ENV_MAX) {
                    break;
                }
                if (c == null || c.change() == null || c.change().isBlank()) {
                    continue;
                }
                String scene = c.scene() == null || c.scene().isBlank() ? "" : c.scene() + "：";
                e.add(scene + c.change());
            }
            if (!e.isEmpty()) {
                body.append("· 环境变化：").append(String.join("；", e)).append("\n");
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        String head = frame.gameDay() == null ? "今日世界状态：\n" : "今日世界状态（第" + frame.gameDay() + "游戏日）：\n";
        String text = head + body;
        return text.length() <= TOTAL_BUDGET ? text : text.substring(0, TOTAL_BUDGET);
    }

    /** 解析单段 JSON 列（null/非法返回 MissingNode，WorldFrameParse 容错为空段） */
    private static JsonNode parseNode(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.missingNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.missingNode();
        }
    }
}
