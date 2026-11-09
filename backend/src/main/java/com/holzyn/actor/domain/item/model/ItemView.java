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

import java.util.List;

/**
 * 物品只读视图（§4.5 的 {@code ItemView}）。
 *
 * <p>裁决器与提示词只读本记录，不碰实体——规则单测因此无需 Spring/JPA。</p>
 *
 * <p>一个实例对应「一个品质层」，同一 itemKey 可能有多条（品质不同）。需要总量时
 * 用 {@link #totalOf(List, String)} 求和，不要假设 list 里 itemKey 唯一。</p>
 */
public record ItemView(
        String itemKey,
        String name,
        String category,
        int quantity,
        int quality,
        Integer durability,
        String equipSlot) {

    /** 是否武器（分类优先；懒建定义可能把刀归为 material，故再按名称兜底）。 */
    public boolean weaponLike() {
        return weaponLikeOf(category, name);
    }

    /**
     * 是否武器（静态版：装备视图/规则层不持有 {@link ItemView} 时用同一口径）。
     *
     * <p><b>为什么名称兜底必须留着</b>：宽松策略会把没登记过的键懒建成
     * {@code material}，历史上「柴刀」就是 material。V215 已把这类占位定义升级，
     * 但老存档里仍可能有漏网的键——名称兜底保证「拿刀砍人」不会因为分类没改而判空手。</p>
     *
     * @param category 分类
     * @param name     展示名
     *
     * @return true=像武器
     */
    public static boolean weaponLikeOf(String category, String name) {
        if ("weapon".equalsIgnoreCase(category)) {
            return true;
        }
        String n = name == null ? "" : name;
        return n.contains("剑") || n.contains("刀") || n.contains("匕首") || n.contains("弓")
                || n.contains("斧") || n.contains("锤") || n.contains("枪") || n.contains("矛");
    }

    /**
     * 成色 = min(品质, 耐久)：这把东西「还能用几成」的唯一口径（与
     * {@code WearRules.conditionOf} 同源，这里只是把 null 耐久当全新）。
     *
     * <p><b>为什么不直接用耐久</b>：品质是「出生时的底子」，耐久是「用旧了多少」。
     * 一把 60 品质的新刀和一把 100 品质但磨损到 60 的刀，砍下去都是六成力——合成
     * 一个数才不会被两套刻度各自解释。</p>
     *
     * @return 0–100
     */
    public int condition() {
        int d = durability == null ? 100 : Math.max(0, Math.min(100, durability));
        int q = Math.max(0, Math.min(100, quality));
        return Math.min(q, d);
    }

    /**
     * 成色口语标签（「全新 / 完好 / 尚可 / 旧了 / 快坏了 / 已经断了」）。
     *
     * @return 中文标签
     */
    public String conditionLabel() {
        int c = condition();
        if (c >= 100) {
            return "全新";
        }
        if (c >= 80) {
            return "完好";
        }
        if (c >= 60) {
            return "尚可";
        }
        if (c >= 40) {
            return "旧了";
        }
        if (c > 0) {
            return "快坏了";
        }
        return "已经断了";
    }

    /**
     * 是否能吃（分类优先；懒建定义常把食物归成 material，故按名称兜底）。
     *
     * @return true=可入口
     */
    public boolean foodLike() {
        return foodLikeOf(category, name);
    }

    /**
     * 是否能喝。
     *
     * @return true=可饮用
     */
    public boolean drinkLike() {
        return drinkLikeOf(category, name);
    }

    /**
     * 是否能吃（静态口径，供规则层与提示词共用）。
     *
     * <p>刻意<b>不</b>把「水」算进食：渴和饿是两条需求，混一起会让「喝水充饥」变成
     * 真的把水当饭吃。</p>
     *
     * @param category 分类
     * @param name     展示名
     *
     * @return true=可入口
     */
    public static boolean foodLikeOf(String category, String name) {
        if ("food".equalsIgnoreCase(category)) {
            return true;
        }
        String n = name == null ? "" : name;
        return n.contains("面包") || n.contains("饭") || n.contains("粥") || n.contains("肉")
                || n.contains("饼") || n.contains("馒头") || n.contains("面") || n.contains("果")
                || n.contains("菜") || n.contains("粮") || n.contains("干粮");
    }

    /**
     * 是否能喝（静态口径）。
     *
     * @param category 分类
     * @param name     展示名
     *
     * @return true=可饮用
     */
    public static boolean drinkLikeOf(String category, String name) {
        if ("drink".equalsIgnoreCase(category)) {
            return true;
        }
        String n = name == null ? "" : name;
        return n.contains("水") || n.contains("酒") || n.contains("茶") || n.contains("汤")
                || n.contains("奶");
    }

    /**
     * 一句话携带描述（感知段与裁决提示共用同一口径）。
     *
     * <p>形如「柴刀×1（完好 98）」「面包×3」——成色满时不写数字，避免每行都挂一串
     * 100，把真正该注意的磨损淹掉。</p>
     *
     * @return 描述文本
     */
    public String carryLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null || name.isBlank() ? itemKey : name);
        if (quantity > 1) {
            sb.append('×').append(quantity);
        }
        int c = condition();
        if (c < 100) {
            sb.append("（").append(conditionLabel()).append(' ').append(c).append('）');
        }
        return sb.toString();
    }

    /** 某一物品键的总持有量（跨品质求和；键比较不区分 ASCII 大小写，与 normalizeItemKey 同口径）。 */
    public static int totalOf(List<ItemView> items, String itemKey) {
        if (items == null || itemKey == null) {
            return 0;
        }
        String wanted = itemKey.trim();
        int sum = 0;
        for (ItemView item : items) {
            if (item != null && item.itemKey() != null && wanted.equalsIgnoreCase(item.itemKey().trim())) {
                sum += item.quantity();
            }
        }
        return sum;
    }
}
