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

package com.holzyn.actor.common;

import java.util.Set;

/**
 * 事件字段归一化与长度约束（<b>唯一口径</b>）。
 * <p>阶段④发现的问题：事件字段的白名单归一与截断原先只存在于
 * {@code WorldEventBusImpl}（运行时总线）内部，而<b>导入</b>（{@code HolzynImportService}）
 * 与<b>时间线兜底直写</b>（{@code TimelineService}）两条路径把外部数据原样落库——
 * 一旦外部包的 {@code kind}/{@code source} 超出白名单或超长，就会在数据库层抛出
 * {@code Value too long}（或写入总线读不回来的脏枚举值），
 * 属于"外部数据反噬业务"的典型形态。</p>
 * <p>因此本类把三件事抽成公共口径，供上述三条路径共用：</p>
 * <ol>
 * <li><b>白名单归一</b>：{@code kind}/{@code source}/{@code visibility} 非法值回退到安全缺省；</li>
 * <li><b>长度约束</b>：与 {@code ActorEvent} 实体列宽一一对应（见 {@code *_MAX} 常量），
 * 超长截断而非抛异常；</li>
 * <li><b>重要度钳制</b>：1~5。</li>
 * </ol>
 */
public final class ActorEventFields {

    /**
     * 允许的事件类型（{@code actor_event.kind}，列宽 30）。
     * <p><b>收敛</b>：新写入一律 {@code common}（普通居民活动），历史 {@code crowd}
     * 保留在白名单内以便读取兼容（时间线/事件流按 kind 渲染"居民活动"档位时两种值同义）；
     * 读取侧判定两值等价的口径见 {@code TimelineService}/前端时间线 kind 映射。</p>
     * <p><b>M3 移动事件</b>（位置移动与地图生成系统）：新增 {@code move_to}（移动开始/到达）、
     * {@code walk}（途中插值）、{@code pass_by}（途中经过他角色/途中可见）、
     * {@code move_encounter}（途中轻量交互）——移动类事件/编年史落库（§4.7）。</p>
     */
    public static final Set<String> KINDS = Set.of("event", "manual", "evolution", "system", "action",
            "crowd", "common", "world", "relation", "roleplay", "world_frame", "macro", "death",
            "world_map", "move_to", "walk", "pass_by", "move_encounter");

    /** 允许的事件来源（{@code actor_event.source}，列宽 30） */
    public static final Set<String> SOURCES = Set.of("manual", "ai", "simulation", "evolution", "dialog",
            "action", "crowd", "common", "learning", "roleplay", "engine", "macro", "growth");

    /** 允许的可见范围（{@code actor_event.visibility}，列宽 20） */
    public static final Set<String> VISIBILITIES = Set.of("global", "location", "private");

    /** kind 列宽上限 */
    public static final int KIND_MAX = 30;
    /** source 列宽上限 */
    public static final int SOURCE_MAX = 30;
    /** title 列宽上限 */
    public static final int TITLE_MAX = 200;
    /** location_name 列宽上限 */
    public static final int LOCATION_MAX = 100;
    /** source_key 列宽上限 */
    public static final int SOURCE_KEY_MAX = 100;

    private ActorEventFields() {
    }

    /**
     * kind 归一化：空白/非法回退 {@code event}；超长按列宽截断。
     *
     * @param kind 原始值（可空）
     *
     * @return 合法 kind（恒非空）
     */
    public static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "event";
        }
        String k = truncate(kind, KIND_MAX);
        if (k == null) {
            return "event";
        }
        String lower = k.toLowerCase();
        return KINDS.contains(lower) ? lower : "event";
    }

    /**
     * source 归一化：空白/非法回退 {@code manual}；超长按列宽截断。
     *
     * @param source 原始值（可空）
     *
     * @return 合法 source（恒非空）
     */
    public static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "manual";
        }
        String s = truncate(source, SOURCE_MAX);
        if (s == null) {
            return "manual";
        }
        String lower = s.toLowerCase();
        return SOURCES.contains(lower) ? lower : "manual";
    }

    /**
     * visibility 归一化：空白/非法回退 {@code location}。
     *
     * @param visibility 原始值（可空）
     *
     * @return 合法 visibility（恒非空）
     */
    public static String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return "location";
        }
        String v = visibility.trim().toLowerCase();
        return VISIBILITIES.contains(v) ? v : "location";
    }

    /**
     * 事件重要度归一化：非法/空回退 3；越界钳制到 1~5。
     *
     * @param importance 原始值（可空）
     *
     * @return 1~5 的重要度（恒非空）
     */
    public static Integer normalizeImportance(Integer importance) {
        if (importance == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, importance));
    }

    /**
     * 字符串截断（null/空白返回 null；超长按字符数截断）。
     * <p>与 {@link Texts#truncate} 同实现——本方法是事件字段场景的便捷入口，
     * 便于调用方把「事件列宽」与「其它列宽」一眼区分开。</p>
     *
     * @param s 原始文本（可空）
     *
     * @param max 最大字符数
     *
     * @return 截断后的文本（空白为 null）
     */
    public static String truncate(String s, int max) {
        return Texts.truncate(s, max);
    }
}