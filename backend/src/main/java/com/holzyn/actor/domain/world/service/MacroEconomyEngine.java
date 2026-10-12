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

package com.holzyn.actor.domain.world.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 宏观社会演化 · 经济数值引擎。
 * <p>职责：经济子系统每游戏日供需模型：
 * ① 产量 = 基准 ×（1 + 确定性波动 ±10%）× 季节因子；
 * ② <b>粮价 = 基准 × 流量因子 × 缓冲因子</b>——流量因子=当日产量/需求（需求随市集活跃度微调，
 * 贸易入账→市集→需求→价格， 可归因链），缓冲因子=库存可支撑天数（≥30 日充足缓降、
 * &lt;30 日告急陡升[每少 1 日 +5%，0 日 → 2.5 倍]），钳制 [0.6, 3.0]；
 * ③ 库存 = 库存 + 产量 - 消费 - 外销（盈余时 2%）；
 * ④ 市集供需活跃度 = 前值 + 入账市场增量 + 抖动 - 恐慌惩罚（粮价暴涨 &gt;20% 时 -5）；
 * ⑤ 税收 = 人口 × 人均税 × 活跃度因子；钱庄存款 = 留存 + 商队收益 - 支出 - 恐慌提现；
 * ⑥ 货币流通 = 基准 × 活跃度因子 + 抖动；
 * ⑦ 经济压力（MacroScheduler 传给人口引擎） = max(粮价/基准-1, 0) + 库存告急补充分
 * （库存 &lt; 30 日消费 → +缺口）；
 * ⑧ 规则事件：丰年/歉收（产量偏差 &gt;15%）、粮价暴涨（波动 &gt;20% 阈值，重大→AI 叙事）、
 * 商队到来（确定性概率）、钱庄挤兑（存款单日跌幅 &gt;15%）。
 * 全部确定性（daySeed 哈希抖动），可复现可单测。</p>
 */
public final class MacroEconomyEngine {

    private MacroEconomyEngine() {
    }

    /** 经济 tick 输入 */
    public record EconomyInput(long population, double grainStock, double grainProductionBase,
                               double priceBase, double perCapitaConsumption, double taxPerCapita,
                               double marketDelta, Double seasonFactor, double prevPrice,
                               double prevMarketActivity, double prevBankDeposit,
                               double caravanChance, long daySeed) {
    }

    /** 经济 tick 结果（含六指标新值与规则事件） */
    public record EconomyResult(double grainStock, double grainProduction, double grainPrice,
                                double marketActivity, double taxRevenue, double bankDeposit,
                                double currencySupply, double priceChange, double economicStress,
                                List<String> events) {
    }

    /** 粮价因子钳制区间 */
    private static final double PRICE_FACTOR_MIN = 0.6;
    private static final double PRICE_FACTOR_MAX = 3.0;

    /** 库存缓冲中性天数（≥此值=充足，&lt;此值=告急陡升；与 famine-price-factor 阈值同口径） */
    private static final double BUFFER_NEUTRAL_DAYS = 30.0;

    /**
     * 经济每日推进（确定性）。
     */
    public static EconomyResult step(EconomyInput in) {
        List<String> events = new ArrayList<>();
        double jitter = MacroPopulationEngine.jitter(in.daySeed(), 17) - 0.5; // [-0.5, 0.5)

        // ① 产量
        double seasonFactor = in.seasonFactor() == null ? 1.0 : in.seasonFactor();
        double production = in.grainProductionBase() * (1.0 + jitter * 0.2) * seasonFactor;

        // ② 粮价（流量因子 × 缓冲因子；需求随市集活跃度微调——贸易入账→需求→价格可归因链）
        double consumption = in.population() * in.perCapitaConsumption();
        double demand = consumption * (0.95 + in.prevMarketActivity() / 100.0 * 0.1);
        double flowCover = demand <= 0 ? 1.0 : production / demand;
        double flowFactor = clamp(1.0 + (1.0 - flowCover) * 0.8, 0.85, 1.6);
        double bufferDays = demand <= 0 ? BUFFER_NEUTRAL_DAYS : in.grainStock() / demand;
        double bufferFactor;
        if (bufferDays >= BUFFER_NEUTRAL_DAYS) {
            bufferFactor = clamp(1.0 - (bufferDays - BUFFER_NEUTRAL_DAYS) / 500.0, 0.7, 1.0);
        } else {
            // 告急：每少 1 日缓冲 +5%（0 日 → 2.5 倍）
            bufferFactor = 1.0 + (BUFFER_NEUTRAL_DAYS - bufferDays) / 20.0;
        }
        double priceFactor = clamp(flowFactor * bufferFactor, PRICE_FACTOR_MIN, PRICE_FACTOR_MAX);
        double price = in.priceBase() * priceFactor;
        double priceChange = in.prevPrice() > 0 ? Math.abs(price - in.prevPrice()) / in.prevPrice() : 0.0;

        // ③ 库存（+产量 - 消费 - 盈余外销）
        double stock = in.grainStock() + production - consumption;
        if (stock > 0 && flowCover > 1.05) {
            stock -= stock * 0.02; // 常备外销 2%
        }
        stock = Math.max(0.0, stock);

        // ④ 市集供需活跃度（+入账市场增量 - 恐慌惩罚）
        double panic = priceChange > 0.2 ? 5.0 : 0.0;
        double market = clamp(in.prevMarketActivity() + in.marketDelta() + jitter * 6.0 - panic, 5.0, 100.0);

        // ⑤ 税收与钱庄
        double tax = in.population() * in.taxPerCapita() * (market / 100.0);
        double tradeIncome = stock > in.grainStock() ? (stock - in.grainStock()) * 0.5 : 0.0;
        double expense = in.population() * 0.05;
        double bank = in.prevBankDeposit() + tax * 0.3 + tradeIncome - expense;
        double bankChange = in.prevBankDeposit() > 0
                ? (bank - in.prevBankDeposit()) / in.prevBankDeposit() : 0.0;

        // ⑥ 货币流通
        double currency = 20000.0 * (market / 50.0) + jitter * 200.0;

        // ⑦ 经济压力（传人口引擎）
        double priceStress = Math.max(0.0, price / in.priceBase() - 1.0);
        double shortageDays = in.perCapitaConsumption() <= 0 ? 0
                : stock / (in.population() * in.perCapitaConsumption());
        double shortageStress = Math.max(0.0, 1.0 - shortageDays / 30.0);
        double stress = Math.max(priceStress, shortageStress);

        // ⑧ 规则事件
        if (production > in.grainProductionBase() * 1.15) {
            events.add("harvest_good");
        } else if (production < in.grainProductionBase() * 0.85) {
            events.add("harvest_bad");
        }
        if (priceChange > 0.20) {
            events.add("price_surge");
        }
        if (MacroPopulationEngine.jitter(in.daySeed(), 19) < in.caravanChance()) {
            events.add("caravan");
            market = clamp(market + 8.0, 5.0, 100.0);
            bank += 200.0;
        }
        if (bankChange < -0.15) {
            events.add("bank_run");
        }

        // 四舍五入到 2 位小数（防 15.0 抖动式噪音）
        return new EconomyResult(round2(stock), round2(production), round2(price),
                round2(market), round2(tax), round2(bank), round2(currency),
                round2(priceChange), round2(stress), events);
    }

    /** 钳制到 [min, max]。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 两位小数舍入（稳定快照口径）。 */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
