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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易流水实体，对应表 {@code actor_market_trade}。
 *
 * <p><b>一笔成交一条</b>，记录「谁把什么以什么价卖给谁、付没付钱」。物品本身的
 * 数量变化仍在 {@code actor_item_ledger}（账本），本表只记交易语义——
 * 两张表分工明确：物品账本回答「东西去哪了」，交易流水回答「这笔买卖成不成、多少钱」。</p>
 *
 * <p><b>三种支付状态</b>：现金（paid=true / onCredit=false / settled=true）、
 * 赊账（paid=false / onCredit=true / settled=false）。赊账的还款由后续阶段接线，
 * 本轮只保证「欠了谁多少」在库里可查、可被感知。</p>
 *
 * <p><b>幂等</b>：{@code (project_id, idempotency_key)} 唯一；重放同一效果不重复扣货、
 * 不重复收钱（与账本同一口径）。</p>
 *
 * <p>写入唯一入口：{@link com.holzyn.actor.domain.market.service.MarketService}。</p>
 */
@Data
@Entity
@Table(name = "actor_market_trade", indexes = {
        @Index(name = "ix_markettrade_seller", columnList = "project_id,seller_ref,game_second"),
        @Index(name = "ix_markettrade_buyer", columnList = "project_id,buyer_ref,game_second")
})
public class ActorMarketTrade {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 卖家 ref */
    @Column(name = "seller_ref", nullable = false, length = 64)
    private String sellerRef;

    /** 买家 ref */
    @Column(name = "buyer_ref", nullable = false, length = 64)
    private String buyerRef;

    /** 成交物品键 */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** 成交数量 */
    @Column(nullable = false)
    private Integer qty;

    /** 成交单价 */
    @Column(name = "unit_price", nullable = false)
    private Integer unitPrice = 0;

    /** 成交总价（unit_price × qty） */
    @Column(name = "total_price", nullable = false)
    private Integer totalPrice = 0;

    /** 计价货币物品键（默认 coin） */
    @Column(name = "currency_key", nullable = false, length = 60)
    private String currencyKey;

    /** 是否已付款（赊账为 false） */
    @Column(nullable = false)
    private Boolean paid = Boolean.TRUE;

    /** 是否赊账 */
    @Column(name = "on_credit", nullable = false)
    private Boolean onCredit = Boolean.FALSE;

    /** 欠款是否已结清（现金恒 true；赊账初始 false） */
    @Column(nullable = false)
    private Boolean settled = Boolean.TRUE;

    /** 原因（trade / gift / …） */
    @Column(length = 32)
    private String reason;

    /** 来源事件 ID（因果挂点） */
    @Column(name = "event_id")
    private Long eventId;

    /** 成交时的游戏秒 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 幂等键 */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (paid == null) {
            paid = Boolean.TRUE;
        }
        if (onCredit == null) {
            onCredit = Boolean.FALSE;
        }
        if (settled == null) {
            settled = Boolean.TRUE;
        }
    }
}
