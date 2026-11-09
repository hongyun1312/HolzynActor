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
 * 一次成交请求。
 *
 * <p>{@code unitPrice} 为空时由 {@code MarketService} 按当前报价计算；裁决层已经算过价时
 * 直接带过来——这样「掌柜报的价」和「实际收的钱」是同一个数，不会出现
 * 判定说 10 文、结算收 12 文的账实不符。</p>
 *
 * @param projectId      项目/世界 ID
 * @param sellerRef      卖家（loc:{id} 店铺柜台 / npc:{id} 个人）
 * @param buyerRef       买家（npc:{id}）
 * @param itemKey        物品键
 * @param qty            数量（正数）
 * @param unitPrice      约定单价（null=按当前报价）
 * @param credit         是否赊账（true=先拿货、不付钱、记欠款）
 * @param reason         原因（trade/gift/…）
 * @param eventId        来源事件（因果挂点）
 * @param gameSecond     成交时的游戏秒
 * @param idempotencyKey 幂等键（同一效果重放不重复扣货/收钱）
 */
public record TradeRequest(Long projectId, String sellerRef, String buyerRef, String itemKey, int qty,
                           Integer unitPrice, boolean credit, String reason, Long eventId,
                           long gameSecond, String idempotencyKey) {
}
