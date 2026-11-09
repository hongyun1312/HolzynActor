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

package com.holzyn.actor.domain.equip.repository;

import com.holzyn.actor.domain.equip.entity.ActorItemEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 装备仓储。
 *
 * <p>只由 {@link com.holzyn.actor.domain.equip.service.EquipmentService} 写入；
 * 裁决/提示词只读。</p>
 */
@Repository
public interface ActorItemEquipmentRepository extends JpaRepository<ActorItemEquipment, Long> {

    /**
     * 某持有者的全部装备（按槽位排序，保证提示词逐字节稳定）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 装备行
     */
    List<ActorItemEquipment> findByProjectIdAndHolderRefOrderBySlotAsc(Long projectId, String holderRef);

    /**
     * 某持有者某槽位。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param slot      槽位
     *
     * @return 装备行
     */
    Optional<ActorItemEquipment> findByProjectIdAndHolderRefAndSlot(Long projectId, String holderRef, String slot);

    /**
     * 某持有者是否戴着某件东西（清点/对账用）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 行数
     */
    long countByProjectIdAndHolderRefAndItemKey(Long projectId, String holderRef, String itemKey);

    /**
     * 某物品被谁戴着（物品转手/烂掉时清账用）。
     *
     * @param projectId 项目 ID
     * @param itemKey   物品键
     *
     * @return 装备行
     */
    List<ActorItemEquipment> findByProjectIdAndItemKey(Long projectId, String itemKey);

    /**
     * 某槽位的全部装备行（对账用）。
     *
     * @param projectId 项目 ID
     * @param slot      槽位
     *
     * @return 装备行
     */
    List<ActorItemEquipment> findByProjectIdAndSlot(Long projectId, String slot);
}
