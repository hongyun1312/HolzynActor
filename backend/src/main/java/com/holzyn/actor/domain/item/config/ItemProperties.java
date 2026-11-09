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

package com.holzyn.actor.domain.item.config;

import com.holzyn.actor.domain.item.model.ItemProvider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 物品域配置（前缀 {@code holzyn.actor.item}，§4.10）。
 *
 * <p><b>默认关</b>：{@code enabled=false} 时 {@code InventoryPort} 不装配，
 * {@code EffectApplier} 的 {@code inventory.*} 记 {@code SKIPPED_NO_INVENTORY} 并继续
 * （行为与基线逐字节一致）。桌面端验证用
 * {@code HOLOZYN_ACTOR_ITEM_ENABLED=true} 单独打开。</p>
 *
 * <p>env 覆盖：yml 已写显式占位符，按占位符名精确匹配。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.item")
public class ItemProperties {

    /** 物品域总开关（false=端口不装配，行为回基线） */
    private boolean enabled = false;

    /** 实现形态：db/ engine（E1d，未实现） */
    private ItemProvider provider = ItemProvider.DB;

    /** 未知 itemKey 是否懒建最小定义（material/99/0）；false=拒绝未登记物品 */
    private boolean lazyDef = true;

    /** 懒建定义的默认堆叠上限 */
    private int defaultStackMax = 99;

    /** 单次转移数量上限（防 AI 幻觉一次搬空世界） */
    private int maxQtyPerTransfer = 999;
}
