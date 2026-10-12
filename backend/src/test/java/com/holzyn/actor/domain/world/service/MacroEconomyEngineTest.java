package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经济数值引擎单元测试（宏观社会演化系统 M1，domain/world/service，纯静态）。
 * <p>职责：验证供需模型数学正确性（cover=1 基准价、短缺涨价、盈余降价、钳制）、
 * 六指标联动（库存/税收/钱庄/货币）、规则事件（harvest_good/harvest_bad/price_surge/caravan/bank_run）、确定性抖动。</p>
 */
class MacroEconomyEngineTest {

    /** 构造标准输入（300 口人/库存 800 石/基准价 100/日消费 2.46 石）。 */
    private MacroEconomyEngine.EconomyInput input(double stock, double prevPrice, double marketDelta) {
        return new MacroEconomyEngine.EconomyInput(300L, stock, 2.7, 100.0, 0.0082, 0.6,
                marketDelta, null, prevPrice, 50.0, 5000.0, 0.0, 5L);
    }

    /**
     * 供需平衡（库存≈3 个月缓冲、流量≈消费）→ 粮价接近基准略低（库存充足略压价）。
     */
    @Test
    void balancedSupplyKeepsPriceNearBase() {
        // 库存 800 石 ≈ 325 日缓冲 → 缓冲因子 0.7；流量因子≈0.9 → 价格≈基准 60~75%
        MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(input(800.0, 100.0, 0.0));
        assertTrue(r.grainPrice() >= 55.0 && r.grainPrice() < 100.0,
                "库存充足时粮价应低于基准但不失真，实际 " + r.grainPrice());
        assertTrue(r.grainStock() >= 0);
    }

    /**
     * 库存短缺（&lt;30 日缓冲）→ 缓冲因子陡升 → 粮价上涨；库存 0 → 钳制在 3 倍基准内。
     */
    @Test
    void shortageRaisesPrice() {
        // 库存 50 石 ≈ 20 日缓冲 → 缓冲因子 1.5 → 粮价显著高于基准
        MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(input(50.0, 100.0, 0.0));
        assertTrue(r.grainPrice() > 100.0, "短缺时粮价应高于基准，实际 " + r.grainPrice());
        assertTrue(r.economicStress() > 0, "库存告急应推高经济压力");
        // 库存跌至 0 → 缓冲因子 2.5×流量 → 价格触顶钳制 3.0 倍
        MacroEconomyEngine.EconomyResult zero = MacroEconomyEngine.step(input(0.0, 100.0, 0.0));
        assertTrue(zero.grainPrice() <= 300.0, "粮价应被钳制在 3 倍基准内，实际 " + zero.grainPrice());
        assertTrue(zero.events().contains("price_surge"), "库存归零应触发粮价暴涨事件");
    }

    /**
     * 盈余充足（库存极大）→ 缓冲因子触底 0.7 → 粮价下探但不低于钳制下限 0.6。
     */
    @Test
    void surplusLowersPrice() {
        MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(input(5000.0, 100.0, 0.0));
        assertTrue(r.grainPrice() < 100.0, "盈余时粮价应低于基准，实际 " + r.grainPrice());
        assertTrue(r.grainPrice() >= 60.0, "粮价钳制下限 0.6 倍基准");
    }

    /**
     * 入账市场增量（M2 经商）→ 市集活跃度上升；粮价暴涨（库存告急）→ 恐慌惩罚 -5。
     */
    @Test
    void marketDeltaRaisesActivityAndPanicPenalty() {
        MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(input(800.0, 100.0, 8.0));
        assertTrue(r.marketActivity() > 50.0, "市集增量应推高活跃度，实际 " + r.marketActivity());
        // 库存 10 石（告急）→ 价格暴涨 → 恐慌惩罚 → 活跃度低于前值
        MacroEconomyEngine.EconomyResult panic = MacroEconomyEngine.step(input(10.0, 100.0, 0.0));
        assertTrue(panic.priceChange() > 0.2, "库存告急应触发价格暴涨（波动 " + panic.priceChange() + "）");
        assertTrue(panic.marketActivity() < 50.0, "恐慌惩罚应压低活跃度，实际 " + panic.marketActivity());
    }

    /**
     * 规则事件：产量偏差&gt;15% → 丰年/歉收；商队概率=1.0 必触发；极端短缺 → 粮价暴涨。
     */
    @Test
    void ruleEventsTriggeredByMagnitudes() {
        // 商队概率 1.0 → 必触发商队到来
        MacroEconomyEngine.EconomyInput caravan = new MacroEconomyEngine.EconomyInput(
                300L, 800.0, 2.7, 100.0, 0.0082, 0.6, 0.0, null, 100.0, 50.0, 5000.0, 1.0, 5L);
        MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(caravan);
        assertTrue(r.events().contains("caravan"));
        // 库存 0 → 粮价暴涨必然触发
        MacroEconomyEngine.EconomyInput drain = new MacroEconomyEngine.EconomyInput(
                300L, 0.0, 2.7, 100.0, 0.0082, 0.6, -30.0, null, 100.0, 10.0, 1000.0, 0.0, 5L);
        MacroEconomyEngine.EconomyResult r2 = MacroEconomyEngine.step(drain);
        assertTrue(r2.events().contains("price_surge"), "极端输入应触发暴涨类事件，实际 " + r2.events());
    }

    /**
     * 确定性：同输入同输出（可复现）；round2 两位小数稳定。
     */
    @Test
    void deterministicAndRounded() {
        MacroEconomyEngine.EconomyResult a = MacroEconomyEngine.step(input(800.0, 100.0, 0.0));
        MacroEconomyEngine.EconomyResult b = MacroEconomyEngine.step(input(800.0, 100.0, 0.0));
        assertEquals(a.grainPrice(), b.grainPrice(), 0.0001);
        assertEquals(a.grainStock(), b.grainStock(), 0.0001);
        assertEquals(a.grainPrice(), Math.round(a.grainPrice() * 100.0) / 100.0, 0.0001);
    }

    /**
     * 季节因子（M3 预留给：产量 = 基准 × 季节因子）。
     */
    @Test
    void seasonFactorScalesProduction() {
        MacroEconomyEngine.EconomyInput summer = new MacroEconomyEngine.EconomyInput(
                300L, 800.0, 2.7, 100.0, 0.0082, 0.6, 0.0, 1.3, 100.0, 50.0, 5000.0, 0.0, 5L);
        MacroEconomyEngine.EconomyInput base = new MacroEconomyEngine.EconomyInput(
                300L, 800.0, 2.7, 100.0, 0.0082, 0.6, 0.0, 1.0, 100.0, 50.0, 5000.0, 0.0, 5L);
        MacroEconomyEngine.EconomyResult r1 = MacroEconomyEngine.step(summer);
        MacroEconomyEngine.EconomyResult r2 = MacroEconomyEngine.step(base);
        assertTrue(r1.grainProduction() > r2.grainProduction(), "季节丰产因子应提升产量");
        assertTrue(r1.grainPrice() <= r2.grainPrice() + 0.001, "产量提升应压低粮价");
    }

    /**
     * 钳制与舍入静态工具。
     */
    @Test
    void clampAndRoundHelpers() {
        assertEquals(5.0, MacroEconomyEngine.clamp(1.0, 5.0, 100.0), 0.0001);
        assertEquals(100.0, MacroEconomyEngine.clamp(200.0, 5.0, 100.0), 0.0001);
        assertEquals(15.0, MacroEconomyEngine.round2(15.0), 0.0001);
        assertEquals(15.13, MacroEconomyEngine.round2(15.125), 0.0001);
    }
}
