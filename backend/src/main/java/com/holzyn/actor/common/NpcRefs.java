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

/**
 * NPC 统一身份引用。
 * <p><b>背景</b>： 之前全系统存在两套身份前缀——{@code special:{actor_profile.id}}（特殊型）与
 * {@code crowd:{actor_profile_ordinary.id}}（普通型），被 65+ 个文件、30+ 张表
 * 宏观 / 评估 / 中断记录 …）依赖。合并单表后两套 id 空间合一会直接撞车（{@code special:12} 与
 * {@code crowd:12} 变成同一张表的两行），因此统一为<b>单前缀</b>：</p>
 * <pre>
 * </pre>
 * <p><b>纪律</b>：任何地方都<b>不要手写</b>{@code "npc:" + id} 字面量，一律走本类——
 * 拼接口径、解析口径、跨项目归属判定三处必须同源（对齐 {@code GuardRailPolicy}
 * 「唯一策略源」范式）。</p>
 * <p><b>与旧前缀的关系</b>：{@code special:} / {@code crowd:} 已废弃；由于 采用
 * 「归档旧库 + 新建干净基线库」的迁移方式，库内不存在需要兼容的旧引用，
 * 但 {@link #parseLenient} 仍可识别旧前缀（仅用于导入外部旧包等边界场景，返回同一 id 语义）。</p>
 */
public final class NpcRefs {

    /** 统一前缀 */
    public static final String PREFIX = "npc:";

    /** 历史前缀（特殊型；仅解析兼容用） */
    public static final String LEGACY_SPECIAL_PREFIX = "special:";

    /** 历史前缀（普通型；仅解析兼容用） */
    public static final String LEGACY_CROWD_PREFIX = "crowd:";

    /** 类型值：特殊型 */
    public static final String TYPE_SPECIAL = "special";

    /** 类型值：普通型（历史代码中亦称 crowd） */
    public static final String TYPE_COMMON = "common";

    /** 历史类型值：普通型（沙盘/对话成员等旧字段用 crowd） */
    public static final String TYPE_COMMON_LEGACY = "crowd";

    private NpcRefs() {
    }

    /**
     * 生成统一身份引用。
     *
     * @param profileId actor_profile.id（须为正数）
     *
     * @return {@code npc:{id}}；入参非法返回 null
     */
    public static String of(Long profileId) {
        if (profileId == null || profileId <= 0) {
            return null;
        }
        return PREFIX + profileId;
    }

    /**
     * 生成统一身份引用（字符串 id）。
     *
     * @param profileId 主键文本
     *
     * @return {@code npc:{id}}；非法返回 null
     */
    public static String of(String profileId) {
        return of(parseId(profileId));
    }

    /**
     * 是否为统一身份引用。
     *
     * @param ref 引用（可空）
     *
     * @return true=形如 {@code npc:{正数}}
     */
    public static boolean isNpcRef(String ref) {
        return idOf(ref) != null;
    }

    /**
     * 解析统一身份引用为 actor_profile.id。
     *
     * @param ref 引用（可空）
     *
     * @return 主键；非统一前缀或非法返回 null
     */
    public static Long idOf(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.trim();
        if (!s.startsWith(PREFIX)) {
            return null;
        }
        return parseId(s.substring(PREFIX.length()));
    }

    /**
     * 宽松解析：统一前缀与两个历史前缀都能解析（仅用于外部旧包导入等边界场景）。
     *
     * @param ref 引用（可空）
     *
     * @return 主键；无法解析返回 null
     */
    public static Long parseLenient(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.trim();
        Long id = idOf(s);
        if (id != null) {
            return id;
        }
        if (s.startsWith(LEGACY_SPECIAL_PREFIX)) {
            return parseId(s.substring(LEGACY_SPECIAL_PREFIX.length()));
        }
        if (s.startsWith(LEGACY_CROWD_PREFIX)) {
            return parseId(s.substring(LEGACY_CROWD_PREFIX.length()));
        }
        // 纯数字也接受（部分调试接口直接传 id）
        return parseId(s);
    }

    /**
     * 类型值归一化：历史 {@code crowd} → {@code common}，其余原样。
     *
     * @param type 类型值（可空）
     *
     * @return 归一化后的类型；空值返回 {@link #TYPE_SPECIAL}
     */
    public static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TYPE_SPECIAL;
        }
        String t = type.trim().toLowerCase();
        if (TYPE_COMMON_LEGACY.equals(t) || TYPE_COMMON.equals(t)) {
            return TYPE_COMMON;
        }
        return TYPE_SPECIAL;
    }

    /**
     * 是否普通型（兼容历史 {@code crowd} 写法）。
     *
     * @param type 类型值（可空）
     *
     * @return true=普通型
     */
    public static boolean isCommonType(String type) {
        return TYPE_COMMON.equals(normalizeType(type));
    }

    /**
     * 安全解析正整数 id。
     *
     * @param s 文本
     *
     * @return 正数 id；非法返回 null
     */
    private static Long parseId(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}