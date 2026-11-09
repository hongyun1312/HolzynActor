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

package com.holzyn.actor.domain.item.repository;

import com.holzyn.actor.domain.item.entity.ActorItemStack;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 物品持有栈仓储（§4.4.5）。
 *
 * <p><b>为什么带悲观锁</b>：转移是「读数量 → 判断够不够 → 扣减」的复合操作，
 * H2 默认读已提交，不加锁会出现两个事务同时看到 5 袋、各扣 3 袋的经典丢失更新。
 * 加锁范围只在一行/一个品质层，事务极短（无 AI、无 HTTP），不会造成锁超时。</p>
 */
@Repository
public interface ActorItemStackRepository extends JpaRepository<ActorItemStack, Long> {

    /**
     * 锁定某持有者某物品的全部品质层。
     *
     * <p>刻意不带 {@code order by}：H2 对「FOR UPDATE + ORDER BY」的支持随版本变化，
     * 排序放到 Java 侧做（{@code InventoryService#debit}），锁语句保持最朴素的形态。</p>
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 品质层列表（顺序不保证）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ActorItemStack s "
            + "where s.projectId = :projectId and s.holderRef = :holderRef and s.itemKey = :itemKey")
    List<ActorItemStack> lockByHolderAndItem(@Param("projectId") Long projectId,
                                             @Param("holderRef") String holderRef,
                                             @Param("itemKey") String itemKey);

    /**
     * 锁定某一状态层（目标入账用）。
     *
     * <p>起层键含 {@code durability}：同一品质下「全新」与「用钝了」是两层，
     * 入账必须落回同磨损层，否则两件不同状态的东西会被并成一件。</p>
     *
     * @param projectId  项目 ID
     * @param holderRef  持有者
     * @param itemKey    物品键
     * @param quality    品质
     * @param durability 耐久
     *
     * @return 栈
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ActorItemStack> findLockedByProjectIdAndHolderRefAndItemKeyAndQualityAndDurability(
            Long projectId, String holderRef, String itemKey, Integer quality, Integer durability);

    /**
     * 某持有者的全部物品（只读，裁决/提示词用）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 栈列表
     */
    List<ActorItemStack> findByProjectIdAndHolderRefOrderByItemKeyAscQualityAsc(Long projectId, String holderRef);

    /**
     * 某持有者某物品的总量（跨品质求和）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     * @param itemKey   物品键
     *
     * @return 总量（无记录=0）
     */
    @Query("select coalesce(sum(s.quantity), 0) from ActorItemStack s "
            + "where s.projectId = :projectId and s.holderRef = :holderRef and s.itemKey = :itemKey")
    Long sumQuantity(@Param("projectId") Long projectId,
                     @Param("holderRef") String holderRef,
                     @Param("itemKey") String itemKey);

    /**
     * 某持有者的栈行数（冒烟/对账用）。
     *
     * @param projectId 项目 ID
     * @param holderRef 持有者
     *
     * @return 行数
     */
    long countByProjectIdAndHolderRef(Long projectId, String holderRef);

    /**
     * 负库存行数（不变量自检：必须恒为 0）。
     *
     * @param projectId 项目 ID
     *
     * @return 行数
     */
    @Query("select count(s) from ActorItemStack s where s.projectId = :projectId and s.quantity < 0")
    long countNegative(@Param("projectId") Long projectId);

    /**
     * 全库状态层（腐坏 tick 用；只读，按 id 升序保证结算顺序稳定）。
     *
     * <p>为什么不带 {@code holder_ref}：腐坏与「谁拿着」无关——货架上的面包、
     * 背包里的面包、仓廪里的麦子都会坏，按持有者扫会漏掉货架与容器。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 状态层列表
     */
    List<ActorItemStack> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 某项目里「有东西的持有者」清单（去重、按 ref 排序）。
     *
     * <p>物品面板的左栏：先知道世界上有哪些持有者（人/货架）有物品，再按需拉明细。
     * 不这么做的代价是前端要为每个角色各发一次请求。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 持有者 ref 列表
     */
    @Query("select distinct s.holderRef from ActorItemStack s "
            + "where s.projectId = :projectId order by s.holderRef")
    List<String> findHolderRefs(@Param("projectId") Long projectId);
}
