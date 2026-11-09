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

package com.holzyn.actor.domain.market.model;

/**
 * 一次报价的只读视图（裁决/提示词用）。
 *
 * <p>为什么把 {@code supplyQty}/{@code demandQty}/{@code priceFactor} 一起带出去：
 * 价格必须可解释——AI 与玩家看到的不该只是「10 文」，而是「因为柜台只剩 3 件、
 * 这七天卖了 7 件，所以是 1.5 倍价」。这也是「让世界更真实」在数据层的落点。</p>
 *
 * @param sellerRef   卖家 ref
 * @param itemKey     物品键
 * @param basePrice   基准价
 * @param unitPrice   当前单价
 * @param supplyQty   报价时货架存量
 * @param demandQty   需求窗口内成交量
 * @param priceFactor 价格因子
 * @param gameSecond  报价时的游戏秒
 */
public record MarketQuote(String sellerRef, String itemKey, int basePrice, int unitPrice,
                          int supplyQty, int demandQty, double priceFactor, long gameSecond) {

    /**
     * 指定数量的总价（可能超出 int，故返回 long）。
     *
     * @param qty 数量
     *
     * @return 总价
     */
    public long totalFor(int qty) {
        return (long) unitPrice * Math.max(0, qty);
    }
}
