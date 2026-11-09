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

package com.holzyn.actor.domain.item.wear;

/**
 * 耐久战损的纯规则。
 *
 * <p>零依赖、零 Spring、可静态单测：裁决层与战斗服务都只调这里，保证
 * 「刀有多钝」只有一套口径（对齐 AGENTS §8.1.6 单一判定入口）。</p>
 *
 * <p><b>成色 = min(品质, 耐久)</b>：品质是「造得好不好」，耐久是「用旧了没有」，
 * 一件东西的实际表现由更差的那一头决定——一把好刀用卷了刃，砍起来和一把粗制的新刀
 * 差不多。用乘积会让两个 80 分变 64 分，惩罚过重；取最小可解释、可复算。</p>
 */
public final class WearRules {

    /** 满成色 */
    public static final int MAX_CONDITION = 100;

    /** 低于该成色视为「卷刃」：还能打，但打不狠 */
    public static final int BLUNT_CONDITION = 25;

    /** 伤害系数下限：再破的刀也不是空手 */
    private static final double MIN_DAMAGE_FACTOR = 0.5d;

    private WearRules() {
    }

    /**
     * 成色（0–100）= min(品质, 耐久)。
     *
     * @param quality    品质（可空，按 100 处理）
     * @param durability 耐久（可空，按 100 处理）
     *
     * @return 成色
     */
    public static int conditionOf(Integer quality, Integer durability) {
        int q = quality == null ? MAX_CONDITION : clamp(quality, 0, MAX_CONDITION);
        int d = durability == null ? MAX_CONDITION : clamp(durability, 0, MAX_CONDITION);
        return Math.min(q, d);
    }

    /**
     * 是否已经卷刃（成色低于阈值）。
     *
     * @param quality    品质
     * @param durability 耐久
     * @param threshold  阈值（配置；&lt;=0 时用 {@link #BLUNT_CONDITION}）
     *
     * @return true=卷刃
     */
    public static boolean blunt(Integer quality, Integer durability, int threshold) {
        int limit = threshold <= 0 ? BLUNT_CONDITION : threshold;
        return conditionOf(quality, durability) < limit;
    }

    /**
     * 一击伤害（成色越差打得越轻，但永远至少 1 点）。
     *
     * @param quality    品质
     * @param durability 耐久
     * @param baseDamage 满成色时的基准伤害
     *
     * @return 伤害
     */
    public static int damageOf(Integer quality, Integer durability, int baseDamage) {
        int base = Math.max(1, baseDamage);
        double factor = MIN_DAMAGE_FACTOR
                + (1.0d - MIN_DAMAGE_FACTOR) * conditionOf(quality, durability) / (double) MAX_CONDITION;
        return Math.max(1, (int) Math.round(base * factor));
    }

    /**
     * 成色的中文描述（失败文本/提示词用；同一成色文本固定，利于前缀缓存）。
     *
     * @param condition 成色
     *
     * @return 描述
     */
    public static String describe(int condition) {
        return describe(condition, true);
    }

    /**
     * 按「是不是武器」分成两套措辞的成色描述。
     *
     * <p><b>为什么要分</b>：{@link #describe(int)} 的词表是给刀剑写的（锋利/卷刃），
     * 穿在身上的粗布衣也套这套词，界面上就会出现「粗布衣：锋利」这种读起来荒唐的成色。
     * 同一条成色数值，武器说「锋利」，衣物只能说「齐整」。</p>
     *
     * @param condition  成色
     *
     * @param weaponLike true=武器措辞 / false=衣物装备措辞
     *
     * @return 描述
     */
    public static String describe(int condition, boolean weaponLike) {
        if (condition < 0) {
            return weaponLike ? "不知道还能不能用" : "看不出成色";
        }
        if (condition <= 0) {
            return weaponLike ? "已经断了" : "已经破了";
        }
        if (condition < BLUNT_CONDITION) {
            return weaponLike ? "卷了刃，快断了" : "磨得快破了";
        }
        if (condition < 60) {
            return weaponLike ? "用得有点旧了" : "有些旧了";
        }
        if (condition < 90) {
            return weaponLike ? "还使得动" : "还穿得出去";
        }
        return weaponLike ? "锋利" : "齐整";
    }

    /**
     * 钳制。
     *
     * @param value 值
     * @param lo    下限
     * @param hi    上限
     *
     * @return 钳制后的值
     */
    public static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
