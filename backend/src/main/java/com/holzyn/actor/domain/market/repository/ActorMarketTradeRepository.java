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

package com.holzyn.actor.domain.market.repository;

import com.holzyn.actor.domain.market.entity.ActorMarketTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 交易流水仓储。
 *
 * <p>需求窗口的成交量直接从流水求和，而不是在报价行上累计计数器：
 * 计数器一旦漏加/多加就永久偏，窗口求和每次都对得上（价格可复算）。</p>
 */
@Repository
public interface ActorMarketTradeRepository extends JpaRepository<ActorMarketTrade, Long> {

    /**
     * 幂等查找（同一效果重放）。
     *
     * @param projectId 项目 ID
     * @param idempotencyKey 幂等键
     *
     * @return 已有成交
     */
    Optional<ActorMarketTrade> findByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    /**
     * 需求窗口内的成交量（某卖家某物品）。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     * @param itemKey   物品键
     * @param sinceGameSecond 窗口起点（含）
     *
     * @return 成交量（无记录=0）
     */
    @Query("select coalesce(sum(t.qty), 0) from ActorMarketTrade t "
            + "where t.projectId = :projectId and t.sellerRef = :sellerRef "
            + "and t.itemKey = :itemKey and t.gameSecond >= :sinceGameSecond")
    Long sumQtySince(@Param("projectId") Long projectId,
                     @Param("sellerRef") String sellerRef,
                     @Param("itemKey") String itemKey,
                     @Param("sinceGameSecond") Long sinceGameSecond);

    /**
     * 某买家未结清的赊账（欠款查询；还款接线前供排障/前端用）。
     *
     * @param projectId 项目 ID
     * @param buyerRef  买家
     *
     * @return 未结清流水
     */
    List<ActorMarketTrade> findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(
            Long projectId, String buyerRef);

    /**
     * 某卖家某物品的成交笔数（排障用）。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     * @param itemKey   物品键
     *
     * @return 笔数
     */
    long countByProjectIdAndSellerRefAndItemKey(Long projectId, String sellerRef, String itemKey);
}
