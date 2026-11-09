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

package com.holzyn.actor.domain.market.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 市场交易配置（前缀 {@code holzyn.actor.market}）。
 *
 * <p><b>从属于物品域</b>：本开关只在 {@code holzyn.actor.item.enabled=true} 时有意义——
 * 物品域关闭时 {@code InventoryPort} 不装配，市场没有可结算的货与钱，{@code MarketService}
 * 直接拒绝（效果层记 SKIPPED，不抛异常）。</p>
 *
 * <p>env 覆盖：yml 已写显式占位符，按占位符名精确匹配。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.market")
public class MarketProperties {

    /** 市场开关（false=不报价、不结算；R-TRADE 回落到「未知」） */
    private boolean enabled = true;

    /** 计价货币的物品键（货币就是物品，走栈与账本） */
    private String currencyItemKey = "coin";

    /** 物品定义没写基准价（base_price≤0）时使用的默认基准价 */
    private int defaultBasePrice = 10;

    /** 单价下限（不允许出现 0 元购） */
    private int minPrice = 1;

    /** 单价上限（防公式/数据异常把价格抬到天文数字） */
    private int maxPrice = 100000;

    /** 中性库存（件）：视为「正常备货」，价格≈基准价 */
    private int neutralStock = 10;

    /** 需求窗口（游戏日）：窗口内成交量作为需求 */
    private int demandWindowDays = 7;

    /** 报价有效期（游戏分钟）：超过则重算 */
    private int quoteTtlGameMinutes = 60;

    /** 成交后是否同步 {@code stock:*} 事实（让旁人「看见」货变少了） */
    private boolean stockFactSync = true;

    /** 单笔成交数量上限（防 AI 幻觉一次搬空柜台） */
    private int maxQtyPerTrade = 99;
}
