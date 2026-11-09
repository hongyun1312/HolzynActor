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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 耐久/战损纯规则。
 *
 * <p>零 Spring：成色、卷刃、伤害、文本描述都是静态函数，这里逐条钉死口径。</p>
 */
class WearRulesTest {

    @Test
    @DisplayName("成色 = min(品质, 耐久)；空值按 100；越界钳制")
    void conditionIsMinOfQualityAndDurability() {
        assertEquals(100, WearRules.conditionOf(100, 100));
        assertEquals(40, WearRules.conditionOf(40, 90), "品质更差时取品质");
        assertEquals(30, WearRules.conditionOf(90, 30), "耐久更差时取耐久");
        assertEquals(100, WearRules.conditionOf(null, null), "空值按全新");
        assertEquals(0, WearRules.conditionOf(-10, 50), "负数钳到 0");
        assertEquals(100, WearRules.conditionOf(180, 100), "超过 100 钳到 100");
    }

    @Test
    @DisplayName("卷刃：成色低于阈值；阈值为 0 时回落到默认 25")
    void bluntByThreshold() {
        assertFalse(WearRules.blunt(100, 100, 25));
        assertFalse(WearRules.blunt(100, 25, 25), "正好等于阈值不算卷刃");
        assertTrue(WearRules.blunt(100, 24, 25));
        assertTrue(WearRules.blunt(10, 100, 25), "品质太差也算卷刃");
        assertTrue(WearRules.blunt(100, 10, 0), "阈值为 0 时用默认 25");
    }

    @Test
    @DisplayName("伤害：满成色=基准；成色越差越轻，但永远至少 1 点")
    void damageScalesWithCondition() {
        assertEquals(8, WearRules.damageOf(100, 100, 8), "满成色按基准");
        assertEquals(6, WearRules.damageOf(50, 50, 8), "成色一半约打 75%");
        assertEquals(4, WearRules.damageOf(0, 0, 8), "断了也只到 50% 下限");
        assertEquals(1, WearRules.damageOf(0, 0, 1), "再轻也至少 1 点");
        assertEquals(1, WearRules.damageOf(100, 100, 0), "基准非法时按 1");
    }

    @Test
    @DisplayName("描述：断/卷刃/旧/尚可/锋利 五档，未知返回「不知道」")
    void describeBuckets() {
        assertEquals("已经断了", WearRules.describe(0));
        assertEquals("卷了刃，快断了", WearRules.describe(10));
        assertEquals("用得有点旧了", WearRules.describe(40));
        assertEquals("还使得动", WearRules.describe(70));
        assertEquals("锋利", WearRules.describe(100));
        assertEquals("不知道还能不能用", WearRules.describe(-1));
    }

    @Test
    @DisplayName("衣物措辞：同一成色不套武器词（粗布衣不能说「锋利」）")
    void describeForClothesUsesGarmentWording() {
        assertEquals("齐整", WearRules.describe(100, false));
        assertEquals("还穿得出去", WearRules.describe(70, false));
        assertEquals("有些旧了", WearRules.describe(40, false));
        assertEquals("磨得快破了", WearRules.describe(10, false));
        assertEquals("已经破了", WearRules.describe(0, false));
        assertEquals("看不出成色", WearRules.describe(-1, false));
        // 武器口径不变
        assertEquals("锋利", WearRules.describe(100, true));
        // 装备视图按槽位/分类取措辞：body 槽的粗布衣不能出现「锋利」
        assertEquals("齐整", new com.holzyn.actor.domain.equip.model.EquipmentView(
                "body", "粗布衣", "粗布衣", "wearable", 1, 100, 100, 0L).conditionLabel());
        assertEquals("锋利", new com.holzyn.actor.domain.equip.model.EquipmentView(
                "hand", "柴刀", "柴刀", "weapon", 1, 100, 100, 0L).conditionLabel());
    }

    @Test
    @DisplayName("钳制")
    void clampWorks() {
        assertEquals(5, WearRules.clamp(5, 0, 10));
        assertEquals(0, WearRules.clamp(-5, 0, 10));
        assertEquals(10, WearRules.clamp(50, 0, 10));
    }
}
