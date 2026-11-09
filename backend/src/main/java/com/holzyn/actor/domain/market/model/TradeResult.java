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
 * 一次成交的结果。
 *
 * @param tradeId    流水 ID
 * @param itemKey    物品键
 * @param qty        成交数量
 * @param unitPrice  成交单价
 * @param totalPrice 成交总价
 * @param paid       是否已付款（赊账=false）
 * @param credit     是否赊账
 * @param stockLeft  成交后货架余量
 * @param priceAfter 成交后的新单价（供需变化后的下一次报价）
 * @param replay     是否幂等重放（true=本次没有真正扣货收钱）
 */
public record TradeResult(Long tradeId, String itemKey, int qty, int unitPrice, int totalPrice,
                          boolean paid, boolean credit, int stockLeft, int priceAfter, boolean replay) {
}
