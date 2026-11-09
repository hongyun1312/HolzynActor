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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 随身物品的一句话描述（感知段 / 裁决提示 / 主动性打分共用同一口径）。
 *
 * <p><b>为什么必须同源</b>：同一个人「带了什么」，如果感知段说「柴刀、面包」而裁决
 * 提示说「一把短刀、两个馍」，模型会以为这是两批东西。所有消费方都调这里，格式只改一处。</p>
 *
 * <p><b>排序为什么不是按数量</b>：按「能不能打 → 能不能吃 → 其余」分类排，同类按名称
 * 稳定排序。玩家/模型扫一眼先看到武器和口粮，才是「人清点随身物品」的顺序。</p>
 *
 * <p><b>为什么成色满时不写数字</b>：每行都挂「100」会把真正该注意的「旧了 62」淹掉；
 * 只有真的旧了才写。</p>
 */
public final class ItemCarryText {

    /** 感知段默认最多列几样（超过只报「等 N 样」，防 token 膨胀）。 */
    public static final int DEFAULT_MAX_ITEMS = 8;

    private ItemCarryText() {
    }

    /**
     * 渲染「背着/带着」的那一串（不含手上与身上，那两处由装备段单独说）。
     *
     * @param carried  物品视图（可空；一个状态层一条）
     *
     * @param maxItems 最多列几样（&le;0 用默认值）
     *
     * @return 形如「面包×3、水×2、麦子×5（旧了 62）」；没有物品返回空串
     */
    public static String renderCarried(List<ItemView> carried, int maxItems) {
        if (carried == null || carried.isEmpty()) {
            return "";
        }
        int limit = maxItems <= 0 ? DEFAULT_MAX_ITEMS : maxItems;
        List<ItemView> sorted = new ArrayList<>(carried.size());
        for (ItemView item : carried) {
            if (item != null && item.quantity() > 0) {
                sorted.add(item);
            }
        }
        if (sorted.isEmpty()) {
            return "";
        }
        sorted.sort(Comparator
                .comparingInt(ItemCarryText::rankOf)
                .thenComparing(item -> item.name() == null ? "" : item.name())
                .thenComparing(item -> item.itemKey() == null ? "" : item.itemKey()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ItemView item : sorted) {
            if (shown >= limit) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(item.carryLabel());
            shown++;
        }
        int rest = sorted.size() - shown;
        if (rest > 0) {
            sb.append("，等 ").append(rest).append(" 样");
        }
        return sb.toString();
    }

    /**
     * 分类排序权重：武器 0 / 食物 1 / 饮品 2 / 其余 3。
     *
     * @param item 物品视图
     *
     * @return 权重
     */
    static int rankOf(ItemView item) {
        if (item == null) {
            return 9;
        }
        if (item.weaponLike()) {
            return 0;
        }
        if (item.foodLike()) {
            return 1;
        }
        if (item.drinkLike()) {
            return 2;
        }
        return 3;
    }

    /**
     * 从一堆物品里挑「最该吃的那样」：能吃、还有剩、成色最差优先（先吃快坏的）。
     *
     * @param items 物品视图
     *
     * @return 该吃的那件；没有可吃的返回 null
     */
    public static ItemView bestFoodOf(List<ItemView> items) {
        return worstEdibleOf(items, false);
    }

    /**
     * 从一堆物品里挑「最该喝的那样」：能喝、还有剩、成色最差优先。
     *
     * @param items 物品视图
     *
     * @return 该喝的那件；没有可喝的返回 null
     */
    public static ItemView bestDrinkOf(List<ItemView> items) {
        return worstEdibleOf(items, true);
    }

    private static ItemView worstEdibleOf(List<ItemView> items, boolean drink) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        ItemView best = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() <= 0) {
                continue;
            }
            boolean ok = drink ? item.drinkLike() : item.foodLike();
            if (!ok) {
                continue;
            }
            if (best == null || item.condition() < best.condition()) {
                best = item;
            }
        }
        return best;
    }

    /**
     * 随身「快不能用了」的那件（成色低于阈值；优先武器/工具，其次其它）。
     *
     * <p>为什么要挑出来：主动性里「家伙快断了，想找人帮衬」这一类发起，靠的就是
     * 「他自己知道手里这件撑不住了」。成色满的东西不该触发这种焦虑。</p>
     *
     * @param items     物品视图
     *
     * @param threshold 成色阈值（低于它算「快不能用了」）
     *
     * @return 最该担心的一件；没有返回 null
     */
    public static ItemView wornOutOf(List<ItemView> items, int threshold) {
        if (items == null || items.isEmpty() || threshold <= 0) {
            return null;
        }
        ItemView worst = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() <= 0 || item.condition() >= threshold) {
                continue;
            }
            boolean useful = item.weaponLike() || "tool".equalsIgnoreCase(item.category());
            if (worst == null) {
                worst = item;
                continue;
            }
            boolean worstUseful = worst.weaponLike() || "tool".equalsIgnoreCase(worst.category());
            if (useful && !worstUseful) {
                worst = item;
            } else if (useful == worstUseful && item.condition() < worst.condition()) {
                worst = item;
            }
        }
        return worst;
    }

    /**
     * 手里攒着、可以出手的那样货（数量够多、不是钱、不是口粮）。
     *
     * <p>不拿口粮当「货」：一个饿着的人说「我这有点面包你要不要」，听着就不像真话。</p>
     *
     * @param items       物品视图
     *
     * @param currencyKey 货币物品键（不作为货）
     *
     * @param minQty      至少攒到多少件才算「一批货」
     *
     * @return 数量最多的那样货；没有返回 null
     */
    public static ItemView surplusOf(List<ItemView> items, String currencyKey, int minQty) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        int need = Math.max(2, minQty);
        ItemView best = null;
        for (ItemView item : items) {
            if (item == null || item.quantity() < need) {
                continue;
            }
            if (currencyKey != null && currencyKey.equalsIgnoreCase(item.itemKey())) {
                continue;
            }
            if (item.foodLike() || item.drinkLike()) {
                continue;
            }
            if (best == null || item.quantity() > best.quantity()) {
                best = item;
            }
        }
        return best;
    }
}
