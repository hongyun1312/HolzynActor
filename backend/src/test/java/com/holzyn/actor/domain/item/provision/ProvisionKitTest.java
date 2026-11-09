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

package com.holzyn.actor.domain.item.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开局供给规则（对接）：职业家伙口粮铺货判定口径。
 *
 * <p>纯函数测试，不起 Spring：这些规则一旦错了，真实世界开局就会「铁匠没锤子、
 * 铺子里没钱」，而这种错在日志里看不出来，只有数一遍持有栈才会发现。</p>
 */
class ProvisionKitTest {

    @Test
    @DisplayName("职业家伙：铁匠有锤、农户有锄头与麦子、猎户有弓")
    void occupationKits() {
        assertTrue(keys(ProvisionKit.forOccupation("铁匠")).contains("铁锤"));
        assertTrue(keys(ProvisionKit.forOccupation("村东农户")).contains("锄头"));
        assertTrue(keys(ProvisionKit.forOccupation("村东农户")).contains("麦子"));
        assertTrue(keys(ProvisionKit.forOccupation("猎户")).contains("猎弓"));
        assertTrue(keys(ProvisionKit.forOccupation("厨娘")).contains("菜刀"));
        assertTrue(keys(ProvisionKit.forOccupation("杂货掌柜")).contains(ProvisionKit.COIN));
    }

    @Test
    @DisplayName("职业为空/不认识：退回一把柴刀（不让人空手开局）")
    void unknownOccupationFallsBack() {
        assertEquals(List.of("柴刀"), keys(ProvisionKit.forOccupation(null)));
        assertEquals(List.of("柴刀"), keys(ProvisionKit.forOccupation("  ")));
        assertEquals(List.of("柴刀"), keys(ProvisionKit.forOccupation("闲汉")));
    }

    @Test
    @DisplayName("NPC 整套：职业家伙 + 面包 + 水 + 零钱 + 粗布衣（衣物要穿上）")
    void npcKitHasRationAndClothes() {
        List<ProvisionKit.Entry> kit = ProvisionKit.forNpc("铁匠", 2, 2);
        assertTrue(keys(kit).containsAll(List.of("铁锤", "面包", "水", ProvisionKit.COIN, "粗布衣")));
        ProvisionKit.Entry clothes = kit.stream()
                .filter(e -> ProvisionKit.DAILY_CLOTHES.equals(e.itemKey())).findFirst().orElseThrow();
        assertTrue(clothes.equip(), "粗布衣必须标记为「要穿」");
        assertFalse(kit.stream().filter(e -> "面包".equals(e.itemKey())).findFirst().orElseThrow().equip(),
                "口粮不该被穿上");
        // 没有零钱的村民永远买不了东西：市场对他们是橱窗（整世界长跑实测）
        ProvisionKit.Entry purse = kit.stream()
                .filter(e -> ProvisionKit.COIN.equals(e.itemKey())).findFirst().orElseThrow();
        assertEquals(ProvisionKit.NPC_PURSE, purse.qty(), "村民要有一点应急钱");
        assertTrue(purse.qty() > 0 && purse.qty() < 100, "零钱够应急、不够挥霍");
    }

    @Test
    @DisplayName("玩家整套：短刀 + 口粮 + 100 文 + 粗布衣")
    void playerKit() {
        List<ProvisionKit.Entry> kit = ProvisionKit.forPlayer();
        assertTrue(keys(kit).containsAll(List.of("短刀", "面包", "水", ProvisionKit.COIN, "粗布衣")));
        ProvisionKit.Entry coin = kit.stream()
                .filter(e -> ProvisionKit.COIN.equals(e.itemKey())).findFirst().orElseThrow();
        assertEquals(100, coin.qty(), "玩家第一天得买得起东西");
    }

    @Test
    @DisplayName("铺货：铁匠铺有刀有铁锭，普通地点不铺")
    void locationStock() {
        assertTrue(keys(ProvisionKit.forLocation("铁匠铺")).containsAll(List.of("柴刀", "铁锭", "面包")));
        assertTrue(keys(ProvisionKit.forLocation("药铺")).contains("药草"));
        assertTrue(ProvisionKit.forLocation("青岳主峰").isEmpty(), "山顶不该有货架");
        assertTrue(ProvisionKit.forLocation(null).isEmpty());
    }

    @Test
    @DisplayName("铺货：铁匠铺要铺齐「打柴刀/炼铁锭」的料，否则制作永远差材料")
    void smithyHasCraftMaterials() {
        // 配方：打柴刀 = 铁锭×1 + 木头×1；炼铁锭 = 铁矿×3 + 炭×1（都要铁匠炉）。
        // 整世界长跑实测：铺子里只有铁锭与刀，铁匠有炉子也永远开不了工（0 笔制作）。
        List<String> stock = keys(ProvisionKit.forLocation("铁匠铺"));
        assertTrue(stock.containsAll(List.of("木头", "炭", "铁矿")), "铁匠铺要有木料/炭/矿");
    }

    @Test
    @DisplayName("布庄/钱庄/茶庄/饭庄算店铺，「山庄/村庄」不算")
    void shopLikeManors() {
        assertTrue(ProvisionKit.isShopLike("布庄"));
        assertTrue(ProvisionKit.isShopLike("钱庄"));
        assertFalse(ProvisionKit.isShopLike("青溪村"), "村不是店铺");
        assertFalse(ProvisionKit.isShopLike("避暑山庄"), "山庄不是店铺");
    }

    @Test
    @DisplayName("店铺判定：集市/铺/摊算，广场/祠堂不算")
    void shopLike() {
        assertTrue(ProvisionKit.isShopLike("集市"));
        assertTrue(ProvisionKit.isShopLike("村口杂货铺"));
        assertTrue(ProvisionKit.isShopLike("铁匠作坊"));
        assertFalse(ProvisionKit.isShopLike("城市广场"), "单字「市」不能把广场判成店铺");
        assertFalse(ProvisionKit.isShopLike("祠堂"));
        assertFalse(ProvisionKit.isShopLike(""));
    }

    @Test
    @DisplayName("聚落判定：镇 > 城/关/寨 > 村 > 街/渡口；山/峰/溪/朝/系 不算")
    void settlementRank() {
        assertEquals(4, ProvisionKit.settlementRank("临溪镇"));
        assertEquals(3, ProvisionKit.settlementRank("白帝城"));
        assertEquals(2, ProvisionKit.settlementRank("青禾村"));
        assertEquals(1, ProvisionKit.settlementRank("灵溪渡口"));
        assertEquals(0, ProvisionKit.settlementRank("青岳主峰"));
        assertEquals(0, ProvisionKit.settlementRank("大雍朝"), "国号不是聚落");
        assertEquals(0, ProvisionKit.settlementRank("青岳山系"));
        assertTrue(ProvisionKit.isSettlement("临溪镇"));
        assertFalse(ProvisionKit.isSettlement("幽竹秘境"));
    }

    @Test
    @DisplayName("口粮按重要度：1 件 / 2 件 / 3 件")
    void rationByImportance() {
        assertEquals(1, ProvisionKit.rationFor(1));
        assertEquals(2, ProvisionKit.rationFor(5));
        assertEquals(3, ProvisionKit.rationFor(9));
        assertEquals(1, ProvisionKit.rationFor(null));
    }

    private static List<String> keys(List<ProvisionKit.Entry> entries) {
        return entries.stream().map(ProvisionKit.Entry::itemKey).toList();
    }
}
