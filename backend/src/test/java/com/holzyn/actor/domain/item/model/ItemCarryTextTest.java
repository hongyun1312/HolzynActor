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

package com.holzyn.actor.domain.item.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随身物品描述（对接）：感知段与主动性共用同一口径。
 *
 * <p>重点锁三件事：① 排序是「能打 → 能吃 → 能喝 → 其余」；② 成色满时不写数字；
 * ③ 「该吃哪件 / 哪件快坏了 / 哪样是货」的挑法与物品域扣减口径一致。</p>
 */
class ItemCarryTextTest {

    @Test
    @DisplayName("空背包：空串（感知段据此整段不写「带着：」）")
    void emptyCarried() {
        assertEquals("", ItemCarryText.renderCarried(List.of(), 8));
        assertEquals("", ItemCarryText.renderCarried(null, 8));
    }

    @Test
    @DisplayName("排序：武器 → 食物 → 饮品 → 材料；成色满不写数字")
    void orderingAndLabels() {
        String text = ItemCarryText.renderCarried(List.of(
                new ItemView("麦子", "麦子", "material", 5, 100, 100, null),
                new ItemView("水", "水", "drink", 2, 100, 100, null),
                new ItemView("面包", "面包", "food", 3, 100, 100, null),
                new ItemView("柴刀", "柴刀", "weapon", 1, 100, 98, "hand")), 8);

        assertEquals("柴刀（完好 98）、面包×3、水×2、麦子×5", text);
        assertFalse(text.contains("（全新 100）"), "成色满时不该挂数字");
    }

    @Test
    @DisplayName("超出上限：只列前 N 样并报「等 N 样」")
    void capsAndCounts() {
        // 用 ASCII 名：中文排序按码位，丙(4E19) 会排在 甲(7532) 前面，断言会变成测码表
        String text = ItemCarryText.renderCarried(List.of(
                new ItemView("a", "A", "material", 1, 100, 100, null),
                new ItemView("b", "B", "material", 1, 100, 100, null),
                new ItemView("c", "C", "material", 1, 100, 100, null)), 2);
        assertTrue(text.startsWith("A、B"), text);
        assertTrue(text.endsWith("等 1 样"), text);
    }

    @Test
    @DisplayName("该吃哪件：能吃、成色最差优先；水不算吃的")
    void bestFood() {
        List<ItemView> items = List.of(
                new ItemView("面包", "面包", "food", 3, 100, 100, null),
                new ItemView("干粮", "干粮", "food", 1, 100, 60, null),
                new ItemView("水", "水", "drink", 5, 100, 100, null));
        ItemView food = ItemCarryText.bestFoodOf(items);
        assertEquals("干粮", food.name(), "先吃快坏的");
        assertEquals("水", ItemCarryText.bestDrinkOf(items).name());
        assertNull(ItemCarryText.bestFoodOf(List.of(
                new ItemView("水", "水", "drink", 5, 100, 100, null))));
    }

    @Test
    @DisplayName("快不能用了：挑武器/工具里成色最低的，成色满的不算")
    void wornOut() {
        List<ItemView> items = List.of(
                new ItemView("柴刀", "柴刀", "weapon", 1, 100, 20, "hand"),
                new ItemView("麦子", "麦子", "material", 9, 100, 10, null));
        assertEquals("柴刀", ItemCarryText.wornOutOf(items, 25).name(),
                "武器优先于材料——人担心的是吃饭的家伙");
        assertNull(ItemCarryText.wornOutOf(List.of(
                new ItemView("柴刀", "柴刀", "weapon", 1, 100, 100, "hand")), 25));
    }

    @Test
    @DisplayName("有货要出手：排除口粮与货币，取数量最多的那样")
    void surplus() {
        List<ItemView> items = List.of(
                new ItemView("面包", "面包", "food", 9, 100, 100, null),
                new ItemView("coin", "铜钱", "currency", 99, 100, 100, null),
                new ItemView("布匹", "布匹", "material", 6, 100, 100, null),
                new ItemView("麦子", "麦子", "material", 3, 100, 100, null));
        assertEquals("布匹", ItemCarryText.surplusOf(items, "coin", 5).name());
        assertNull(ItemCarryText.surplusOf(items, "coin", 10), "没有一样够 10 件");
    }
}
