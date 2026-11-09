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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供需定价公式单测（「价格随供需变」）。
 *
 * <p>锁的是「价格的方向与边界」而不是某个魔法数：缺货一定更贵、过剩一定更便宜、
 * 因子永远在 [0.6, 3.0]、单价永远在 [min, max]。方向错了，世界里的商人就会
 * 越缺货越便宜，那比价格不准更糟。</p>
 */
class MarketPricingTest {

    @Test
    @DisplayName("中性：供需平衡且库存正好等于中性天数 → 因子 1.0（价格≈基准价）")
    void neutralFactorIsOne() {
        assertEquals(1.0, MarketPricing.priceFactor(1.0, 20.0, 20.0), 1e-9);
        assertEquals(1.0, MarketPricing.itemFactor(10, 0, 7, 10), 1e-9);
    }

    @Test
    @DisplayName("缺货更贵：库存 5（中性 10）→ 1.5 倍")
    void scarceIsMoreExpensive() {
        assertEquals(1.5, MarketPricing.itemFactor(5, 0, 7, 10), 1e-9);
        assertEquals(15, MarketPricing.price(10, 1.5, 1, 100000));
    }

    @Test
    @DisplayName("过剩更便宜：库存 100 → 0.7 倍（有下限，不会白送）")
    void abundantIsCheaper() {
        assertEquals(0.7, MarketPricing.itemFactor(100, 0, 7, 10), 1e-9);
        assertEquals(7, MarketPricing.price(10, 0.7, 1, 100000));
    }

    @Test
    @DisplayName("需求抬高价格：同样 10 件，七天卖 7 件比一件没卖贵")
    void demandRaisesPrice() {
        double noDemand = MarketPricing.itemFactor(10, 0, 7, 10);
        double withDemand = MarketPricing.itemFactor(10, 7, 7, 10);
        assertTrue(withDemand > noDemand, "有成交时必须更贵：" + withDemand + " vs " + noDemand);
        assertEquals(1.275, withDemand, 1e-9);
    }

    @Test
    @DisplayName("因子永远钳制在 [0.6, 3.0]")
    void factorIsClamped() {
        assertEquals(MarketPricing.FACTOR_MAX, MarketPricing.itemFactor(1, 1000, 7, 10), 1e-9);
        assertTrue(MarketPricing.itemFactor(0, 0, 7, 10) >= MarketPricing.FACTOR_MIN);
        assertTrue(MarketPricing.itemFactor(1, 1000, 7, 10) <= MarketPricing.FACTOR_MAX);
    }

    @Test
    @DisplayName("单价钳制：最低价兜住 0 元购，最高价兜住天文数字")
    void priceIsClamped() {
        assertEquals(1, MarketPricing.price(0, 1.0, 1, 100000), "0 基准价也要至少 1 文");
        assertEquals(100000, MarketPricing.price(100000, 3.0, 1, 100000));
        assertEquals(5, MarketPricing.price(10, 0.5, 5, 100000), "低于最低价时按最低价");
    }

    @Test
    @DisplayName("日均需求有下限：没有成交记录时不会除零")
    void demandPerDayHasFloor() {
        assertEquals(MarketPricing.MIN_DEMAND_PER_DAY, MarketPricing.demandPerDay(0, 7), 1e-9);
        assertEquals(1.0, MarketPricing.demandPerDay(7, 7), 1e-9);
        assertEquals(MarketPricing.MIN_DEMAND_PER_DAY, MarketPricing.demandPerDay(0, 0), 1e-9,
                "窗口天数非法按 1 天算，仍不能低于需求下限");
    }

    @Test
    @DisplayName("中性天数：中性库存 10 件 ÷ 日均下限 0.5 = 20 天")
    void neutralDaysTranslatesStock() {
        assertEquals(20.0, MarketPricing.neutralDays(10), 1e-9);
        assertEquals(40.0, MarketPricing.neutralDays(20), 1e-9);
        assertEquals(2.0, MarketPricing.neutralDays(0), 1e-9, "0 库存按 1 件算，不能除零");
    }
}
