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

package com.holzyn.actor.domain.item.service;

import com.holzyn.actor.domain.item.config.ItemDbProviderCondition;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * {@link InventoryPort} 的数据库实现（BRAIN 权威）。
 *
 * <p>薄适配层：只做端口到 {@link InventoryService} 的转发，自身不写任何表——
 * 单一写入口仍然是 {@code InventoryService}。E1d 会以
 * {@code EngineInventoryAdapter} 替换本实现（{@code item.provider=engine}），
 * 效果链路与裁决链路都不改。</p>
 */
@Service
@Conditional(ItemDbProviderCondition.class)
public class DbInventoryService implements InventoryPort {

    private final InventoryService inventoryService;

    /**
     * 构造。
     *
     * @param inventoryService 唯一写入口
     */
    public DbInventoryService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void transfer(Long projectId, String itemKey, int qty, String fromRef, String toRef,
                         String reason, Long eventId, long gameSecond, String idempotencyKey) {
        inventoryService.transfer(projectId, itemKey, qty, fromRef, toRef,
                reason, eventId, gameSecond, idempotencyKey);
    }

    @Override
    public int quantityOf(Long projectId, String holderRef, String itemKey) {
        return inventoryService.quantityOf(projectId, holderRef, itemKey);
    }

    @Override
    public boolean has(Long projectId, String holderRef, String itemKey, int qty) {
        return inventoryService.has(projectId, holderRef, itemKey, qty);
    }

    @Override
    public List<ItemView> list(Long projectId, String holderRef) {
        return inventoryService.list(projectId, holderRef);
    }

    @Override
    public void consume(Long projectId, String itemKey, int qty, String holderRef, String reason,
                        Long eventId, long gameSecond, String idempotencyKey) {
        inventoryService.remove(projectId, itemKey, qty, holderRef, reason, eventId, gameSecond,
                idempotencyKey);
    }

    @Override
    public void produce(Long projectId, String itemKey, int qty, String holderRef, String reason,
                        Long eventId, long gameSecond, String idempotencyKey) {
        inventoryService.add(projectId, itemKey, qty, holderRef, reason, eventId, gameSecond,
                idempotencyKey);
    }

    @Override
    public DurabilityChange wear(Long projectId, String holderRef, String itemKey, int points,
                                 Long eventId, long gameSecond, String idempotencyKey) {
        return inventoryService.wear(projectId, holderRef, itemKey, points, eventId, gameSecond,
                idempotencyKey);
    }

    @Override
    public DurabilityChange restore(Long projectId, String holderRef, String itemKey, int points,
                                    Long eventId, long gameSecond, String idempotencyKey) {
        return inventoryService.restore(projectId, holderRef, itemKey, points, eventId, gameSecond,
                idempotencyKey);
    }

    @Override
    public Optional<ItemView> bestLayerOf(Long projectId, String holderRef, String itemKey) {
        return inventoryService.bestLayerOf(projectId, holderRef, itemKey);
    }
}
