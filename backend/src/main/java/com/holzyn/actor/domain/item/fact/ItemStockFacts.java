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

package com.holzyn.actor.domain.item.fact;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 物品变化的「被看见」层：把某处还剩多少写成 {@code stock:*} 事实（感知回流）。
 *
 * <p><b>为什么必须有</b>：事实层是「旁人知道什么」的唯一载体。市场成交已经会写
 * {@code stock:*}，但「从货架上拿走」「做出来放进仓库」「开局铺货」都不写——结果就是
 * 玩家明明把面包拿光了，路过的人仍然读到「面包还剩 10 件」。I 线与之间最硬的一处断点
 * 就在这里。</p>
 *
 * <p><b>为什么只给地点写</b>：{@code stock:} 的语义是「柜台/货架上还剩多少」，默认
 * {@code location} 可见——到场的人看得见。个人背包不写：那是私事，写出去等于全世界都能
 * 隔着墙数你家有几袋米。人身上「看得见」的那部分由 {@code equip:*} 负责（手上拿着什么）。</p>
 *
 * <p><b>为什么失败只告警</b>：货已经搬完了，一条观察事实写不进去不该把整笔操作回滚
 * （ENGINE 权威下 {@code AuthorityWriteGuard} 会拒绝，与 {@code MarketService} 同口径）。</p>
 */
@Slf4j
@Component
public class ItemStockFacts {

    /** 事实层（未装配 → 整体 no-op） */
    @Autowired(required = false)
    private WorldFactService worldFactService;

    /** 物品读服务（{@code item.enabled=false} 时整体 no-op） */
    @Autowired(required = false)
    private InventoryService inventoryService;

    /** 物品域配置（关着不写任何事实，保证「关开关=回基线」） */
    @Autowired(required = false)
    private ItemProperties itemProperties;

    /** 地点仓储（只用来把 {@code loc:65} 翻成人话地名） */
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    /**
     * 同步某持有者某物品的存量事实（持有者不是地点时直接跳过）。
     *
     * @param projectId  项目 ID
     *
     * @param holderRef  持有者（{@code loc:{id}} 才写）
     *
     * @param itemKey    物品键
     *
     * @param gameSecond 游戏秒
     */
    public void syncHolder(Long projectId, String holderRef, String itemKey, long gameSecond) {
        if (!available() || holderRef == null || itemKey == null || !ItemRefs.isLocation(holderRef)) {
            return;
        }
        try {
            write(projectId, holderRef, itemKey,
                    inventoryService.quantityOf(projectId, holderRef, itemKey), gameSecond);
        } catch (RuntimeException e) {
            log.warn("[物品事实] stock 同步失败（不影响物品变更）：holder={} item={} : {}",
                    holderRef, itemKey, e.getMessage());
        }
    }

    /**
     * 一次转移的两端都同步（任一端不是地点则跳过该端）。
     *
     * @param projectId  项目 ID
     *
     * @param fromRef    来源持有者（可空）
     *
     * @param toRef      目标持有者（可空）
     *
     * @param itemKey    物品键
     *
     * @param gameSecond 游戏秒
     */
    public void syncPair(Long projectId, String fromRef, String toRef, String itemKey, long gameSecond) {
        syncHolder(projectId, fromRef, itemKey, gameSecond);
        syncHolder(projectId, toRef, itemKey, gameSecond);
    }

    /**
     * 把某持有者全部物品的存量事实刷一遍（开局铺货后用一次，避免逐个物品各查一遍）。
     *
     * @param projectId  项目 ID
     *
     * @param holderRef  持有者
     *
     * @param gameSecond 游戏秒
     */
    public void syncAll(Long projectId, String holderRef, long gameSecond) {
        if (!available() || holderRef == null || !ItemRefs.isLocation(holderRef)) {
            return;
        }
        try {
            for (ItemView item : inventoryService.list(projectId, holderRef)) {
                if (item != null && item.quantity() > 0) {
                    write(projectId, holderRef, item.itemKey(), item.quantity(), gameSecond);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[物品事实] stock 全量同步失败（不影响物品变更）：holder={} : {}",
                    holderRef, e.getMessage());
        }
    }

    /** 依赖是否齐、开关是否开。 */
    private boolean available() {
        return worldFactService != null && inventoryService != null
                && (itemProperties == null || itemProperties.isEnabled());
    }

    /**
     * 写一条 {@code stock:*} 事实。
     *
     * <p>文本与 {@code MarketService.syncStockFact} 保持<b>逐字一致</b>：同一个 key 由两处
     * 写入时，格式不同会让事实版本号无意义地抖动，读者也分不清「谁写的」。</p>
     */
    private void write(Long projectId, String holderRef, String itemKey, int left, long gameSecond) {
        String factKey = "stock:" + holderRef + ":" + itemKey;
        String name = nameOf(projectId, itemKey);
        String stateText = left <= 0 ? name + "卖完了" : name + "还剩 " + left + " 件";
        String attrs = "{\"qty\":" + left + ",\"sellerRef\":\"" + holderRef
                + "\",\"itemKey\":\"" + itemKey + "\"}";
        Long locationId = ItemRefs.idOf(holderRef);
        FactCommand cmd = new FactCommand(projectId, "stock", factKey, name, stateText, attrs,
                locationId, locationNameOf(holderRef), "location", FactAuthority.BRAIN,
                gameSecond, null, gameSecond, null, null, 2, null);
        worldFactService.upsert(cmd);
    }

    /** 物品展示名（定义缺失时用键本身）。 */
    private String nameOf(Long projectId, String itemKey) {
        try {
            return inventoryService.findDef(projectId, itemKey)
                    .map(def -> def.getName() == null || def.getName().isBlank() ? itemKey : def.getName())
                    .orElse(itemKey);
        } catch (RuntimeException e) {
            return itemKey;
        }
    }

    /** {@code loc:{id}} → 地名（查不到返回 null，不编地名）。 */
    private String locationNameOf(String holderRef) {
        if (locationRepository == null) {
            return null;
        }
        Long id = ItemRefs.idOf(holderRef);
        if (id == null) {
            return null;
        }
        try {
            return locationRepository.findById(id)
                    .map(loc -> loc.getName())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
