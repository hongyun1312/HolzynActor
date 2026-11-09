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

package com.holzyn.actor.domain.market.pricing;

/**
 * 供需定价公式（「价格随供需变」）。
 *
 * <p><b>同源</b>：本类是价格因子的唯一实现。宏观粮价
 * （{@code MacroEconomyEngine}）与单件物品的市场报价都调这里，避免出现
 * 「粮食按流量×缓冲定价、货物按另一套系数定价」的两套经济学——同一份世界
 * 里两套价格公式，玩家一定会发现某个方向可以无风险套利。</p>
 *
 * <p><b>公式</b>（与宏观粮价同形）：</p>
 * <pre>
 *   flowCover   = 供给 / 需求            （需求=窗口内成交量）
 *   flowFactor  = clamp(1 + (1 - flowCover) × 0.8, 0.85, 1.6)   // 缺货抬价、过剩压价
 *   bufferDays  = 供给 / 日均需求        （库存还能撑几天）
 *   bufferFactor= 充足(≥中性天数) → clamp(1 - (天数-中性)/500, 0.7, 1.0)
 *                 告急(&lt;中性天数)  → 1 + (中性-天数)/20            // 每少一天 +5%
 *   factor      = clamp(flowFactor × bufferFactor, 0.6, 3.0)
 *   price       = clamp(round(基准价 × factor), 最低价, 最高价)
 * </pre>
 *
 * <p>全部确定性、无随机、无时钟依赖：同一组输入永远同一价格，单测与冒烟可复算。</p>
 */
public final class MarketPricing {

    /** 价格因子下限（与宏观粮价一致：最便宜 0.6 倍） */
    public static final double FACTOR_MIN = 0.6;

    /** 价格因子上限（与宏观粮价一致：最贵 3.0 倍） */
    public static final double FACTOR_MAX = 3.0;

    /** 流量因子权重 */
    public static final double FLOW_WEIGHT = 0.8;

    /** 流量因子下限 */
    public static final double FLOW_MIN = 0.85;

    /** 流量因子上限 */
    public static final double FLOW_MAX = 1.6;

    /** 库存充足时的价格下限（再多的货也不低于 0.7 倍） */
    public static final double BUFFER_ABUNDANT_FLOOR = 0.7;

    /** 每少一天库存，价格上涨 1/20（即 +5%） */
    public static final double BUFFER_SHORTAGE_SLOPE_DAYS = 20.0;

    /** 库存充足时，每多一天库存降价 1/500 */
    public static final double BUFFER_ABUNDANT_SPAN_DAYS = 500.0;

    /**
     * 日均需求下限（件/游戏日）。
     *
     * <p>没有任何成交记录时不能把「日均需求」当 0——那会让库存天数除零、价格无穷大。
     * 取 0.5（两天卖一件）作为「冷淡市场」的默认需求，价格不会因此暴涨。</p>
     */
    public static final double MIN_DEMAND_PER_DAY = 0.5;

    private MarketPricing() {
    }

    /**
     * 价格因子（供需比 + 库存天数 → 倍数）。
     *
     * @param flowCover  供给/需求（需求≤0 时传 1.0 表示平衡）
     * @param bufferDays 库存可支撑天数
     * @param neutralDays 中性天数（≥=充足，&lt;=告急）
     *
     * @return 因子，钳制在 [{@value #FACTOR_MIN}, {@value #FACTOR_MAX}]
     */
    public static double priceFactor(double flowCover, double bufferDays, double neutralDays) {
        double flowFactor = clamp(1.0 + (1.0 - flowCover) * FLOW_WEIGHT, FLOW_MIN, FLOW_MAX);
        double bufferFactor;
        if (bufferDays >= neutralDays) {
            bufferFactor = clamp(1.0 - (bufferDays - neutralDays) / BUFFER_ABUNDANT_SPAN_DAYS,
                    BUFFER_ABUNDANT_FLOOR, 1.0);
        } else {
            bufferFactor = 1.0 + (neutralDays - bufferDays) / BUFFER_SHORTAGE_SLOPE_DAYS;
        }
        return clamp(flowFactor * bufferFactor, FACTOR_MIN, FACTOR_MAX);
    }

    /**
     * 单件物品的供需因子。
     *
     * @param supplyQty   货架存量
     * @param demandQty   需求窗口内的成交量
     * @param windowDays  需求窗口（游戏日，≥1）
     * @param neutralStock 中性库存（件；视为「正常备货」）
     *
     * @return 因子
     */
    public static double itemFactor(int supplyQty, int demandQty, int windowDays, int neutralStock) {
        double flowCover = demandQty <= 0 ? 1.0 : Math.max(0, supplyQty) / (double) demandQty;
        double demandPerDay = demandPerDay(demandQty, windowDays);
        double bufferDays = Math.max(0, supplyQty) / demandPerDay;
        return priceFactor(flowCover, bufferDays, neutralDays(neutralStock));
    }

    /**
     * 日均需求（件/游戏日；无成交时取 {@link #MIN_DEMAND_PER_DAY}）。
     *
     * @param demandQty  窗口内成交量
     * @param windowDays 窗口天数（≤0 按 1 天算）
     *
     * @return 日均需求
     */
    public static double demandPerDay(int demandQty, int windowDays) {
        int days = Math.max(1, windowDays);
        return Math.max(MIN_DEMAND_PER_DAY, Math.max(0, demandQty) / (double) days);
    }

    /**
     * 中性天数：中性库存 ÷ 日均需求下限。
     *
     * <p>把「多少件算正常备货」翻译成公式需要的「能撑多少天」，两个口径只在这里换算一次。</p>
     *
     * @param neutralStock 中性库存（件，≤0 按 1 件算）
     *
     * @return 中性天数
     */
    public static double neutralDays(int neutralStock) {
        return Math.max(1, neutralStock) / MIN_DEMAND_PER_DAY;
    }

    /**
     * 由基准价与因子算单价。
     *
     * @param basePrice 基准价
     * @param factor    因子
     * @param minPrice  最低价
     * @param maxPrice  最高价
     *
     * @return 单价（整数，钳制在 [minPrice, maxPrice]）
     */
    public static int price(int basePrice, double factor, int minPrice, int maxPrice) {
        long raw = Math.round(Math.max(0, basePrice) * factor);
        long low = Math.max(0, minPrice);
        long high = Math.max(low, maxPrice);
        return (int) Math.max(low, Math.min(high, raw));
    }

    /** 钳制到 [min, max]。 */
    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
