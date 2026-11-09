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

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.world.fact.service.FactDecayHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 腐坏 tick：每游戏小时把有 {@code decay_hours} 的东西往下推一档。
 *
 * <p>挂在预留的 {@link FactDecayHook} 上——世界引擎一行不改，物品域自己注册一个
 * Bean 就接上了（与 {@code CraftJobSweepHook} 并列，两个钩子互不影响）。</p>
 *
 * <p><b>时间口径</b>：只认游戏秒。世界暂停时活不推进，面包也不会坏——
 * 这与「世界时间是唯一权威」一致，不会出现「挂着机面包就馊了」。</p>
 */
@Slf4j
@Component
public class ItemDecayHook implements FactDecayHook {

    private final InventoryService inventoryService;

    private final WearProperties wearProperties;

    private final ItemProperties itemProperties;

    /**
     * 构造。
     *
     * @param inventoryService 物品唯一写入口（腐坏只由它改栈）
     * @param wearProperties   损耗域配置
     * @param itemProperties   物品域配置
     */
    public ItemDecayHook(InventoryService inventoryService, WearProperties wearProperties,
                         ItemProperties itemProperties) {
        this.inventoryService = inventoryService;
        this.wearProperties = wearProperties;
        this.itemProperties = itemProperties;
    }

    @Override
    public void onHour(Long projectId, long gameSecond) {
        if (projectId == null) {
            return;
        }
        if (wearProperties == null || !wearProperties.isEnabled()) {
            return;
        }
        if (itemProperties == null || !itemProperties.isEnabled()) {
            return;
        }
        try {
            InventoryService.DecayReport report = inventoryService.decayTick(projectId, gameSecond);
            if (report.changed()) {
                log.info("[腐坏] 结算：project={} 降质 {} 层 / 烂透 {} 层（游戏秒 {}）",
                        projectId, report.movedLayers(), report.spoiledLayers(), gameSecond);
            }
        } catch (RuntimeException e) {
            // 腐坏失败不能阻断事实到期（FactSweepService 也会兜一层，这里留明确日志）
            log.warn("[腐坏] 结算失败（隔离继续）：project={} : {}", projectId, e.getMessage());
        }
    }
}
