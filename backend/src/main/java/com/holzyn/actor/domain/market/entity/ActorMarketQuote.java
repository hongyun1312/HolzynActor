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

package com.holzyn.actor.domain.market.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 市场报价实体，对应表 {@code actor_market_quote}。
 *
 * <p><b>一行 = 一个 (卖家, 物品) 的当前报价</b>：{@code unit_price} 是此刻的成交单价，
 * 由「基准价 × 供需因子」得出；{@code supply_qty}/{@code demand_qty} 是算这个价时
 * 用的两个观测值，写进表里是为了让价格可复算、可解释（「为什么今天米贵」）。</p>
 *
 * <p><b>历史价不在这张表</b>：每次成交都往 {@code actor_market_trade} 写一条带
 * {@code unit_price} 的流水，报价表只保留当前值——否则「当前价」要靠时间排序猜，
 * 一处读错就出现两个价。</p>
 *
 * <p>写入唯一入口：{@link com.holzyn.actor.domain.market.service.MarketService}。</p>
 */
@Data
@Entity
@Table(name = "actor_market_quote", indexes = {
        @Index(name = "ix_marketquote_seller", columnList = "project_id,seller_ref")
})
public class ActorMarketQuote {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 卖家 ref：loc:{id}（店铺货架/柜台）或 npc:{id}（个人摊贩） */
    @Column(name = "seller_ref", nullable = false, length = 64)
    private String sellerRef;

    /** 物品键 */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 基准价（来自 actor_item_def.base_price；缺失时用 market.default-base-price） */
    @Column(name = "base_price", nullable = false)
    private Integer basePrice = 0;

    /** 当前单价（基准价 × 因子，钳制在 [min-price, max-price]） */
    @Column(name = "unit_price", nullable = false)
    private Integer unitPrice = 0;

    /** 报价时的货架存量 */
    @Column(name = "supply_qty", nullable = false)
    private Integer supplyQty = 0;

    /** 需求窗口内的成交量 */
    @Column(name = "demand_qty", nullable = false)
    private Integer demandQty = 0;

    /** 价格因子（1.000 = 基准价；与宏观粮价同一套供需公式） */
    @Column(name = "price_factor", precision = 6, scale = 3, nullable = false)
    private BigDecimal priceFactor = BigDecimal.ONE;

    /** 最近一次报价的游戏秒 */
    @Column(name = "quote_game_second")
    private Long quoteGameSecond;

    /** 版本号（每次报价变化 +1，排障用） */
    @Column(nullable = false)
    private Integer version = 1;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (version == null) {
            version = 1;
        }
        if (priceFactor == null) {
            priceFactor = BigDecimal.ONE;
        }
    }

    /** 更新前回调。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
