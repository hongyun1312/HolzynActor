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

package com.holzyn.actor.domain.equip.model;

import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.wear.WearRules;

/**
 * 一件「正在用」的装备。
 *
 * <p>品质/耐久/数量来自持有栈的<b>那一层</b>，不是装备行自己存的副本——
 * 装备行只记 {@code item_key}，「手上这件到底是哪件」由
 * {@code InventoryService.bestLayerOf} 现找。</p>
 *
 * @param slot            槽位（hand/body）
 * @param itemKey         物品键
 * @param name            展示名
 * @param category        分类
 * @param quantity        该层件数
 * @param quality         品质
 * @param durability      耐久
 * @param sinceGameSecond 从哪个游戏秒开始戴着
 */
public record EquipmentView(String slot, String itemKey, String name, String category,
                            int quantity, int quality, int durability, long sinceGameSecond) {

    /**
     * 由槽位 + 物品层构造。
     *
     * @param slot            槽位
     * @param item            物品层
     * @param sinceGameSecond 起始游戏秒
     *
     * @return 装备视图
     */
    public static EquipmentView of(String slot, ItemView item, long sinceGameSecond) {
        return new EquipmentView(slot, item.itemKey(), item.name(), item.category(),
                item.quantity(), item.quality(), item.durability(), sinceGameSecond);
    }

    /** 是否武器（与 {@link ItemView#weaponLike()} 同口径）。 */
    public boolean weaponLike() {
        return ItemView.weaponLikeOf(category, name);
    }

    /** 成色 0–100（min(品质, 耐久)）。 */
    public int condition() {
        return WearRules.conditionOf(quality, durability);
    }

    /** 成色描述（提示词/失败文本用；武器说「锋利」，衣物说「齐整」）。 */
    public String conditionLabel() {
        return WearRules.describe(condition(), weaponLike());
    }

    /** 是否已经断了（耐久归零）。 */
    public boolean broken() {
        return durability <= 0;
    }
}
