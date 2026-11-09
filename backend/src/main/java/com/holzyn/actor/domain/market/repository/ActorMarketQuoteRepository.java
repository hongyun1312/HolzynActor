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

import com.holzyn.actor.domain.market.entity.ActorMarketQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 市场报价仓储。
 *
 * <p>只做单行读写：报价的唯一性由 {@code ux_marketquote (project_id, seller_ref, item_key)}
 * 保证，查价一律走三列精确查找，不做「取最新一条」这类时间排序猜测。</p>
 */
@Repository
public interface ActorMarketQuoteRepository extends JpaRepository<ActorMarketQuote, Long> {

    /**
     * 按 (项目, 卖家, 物品) 查当前报价。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     * @param itemKey   物品键
     *
     * @return 报价
     */
    Optional<ActorMarketQuote> findByProjectIdAndSellerRefAndItemKey(
            Long projectId, String sellerRef, String itemKey);

    /**
     * 某卖家的全部报价（前端/排障用）。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     *
     * @return 报价列表
     */
    List<ActorMarketQuote> findByProjectIdAndSellerRefOrderByItemKeyAsc(Long projectId, String sellerRef);
}
