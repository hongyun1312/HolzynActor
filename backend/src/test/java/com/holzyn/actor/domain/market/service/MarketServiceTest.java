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

package com.holzyn.actor.domain.market.service;

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.repository.ActorItemDefRepository;
import com.holzyn.actor.domain.market.config.MarketProperties;
import com.holzyn.actor.domain.market.entity.ActorMarketTrade;
import com.holzyn.actor.domain.market.model.InsufficientFundsException;
import com.holzyn.actor.domain.market.model.MarketQuote;
import com.holzyn.actor.domain.market.model.TradeRequest;
import com.holzyn.actor.domain.market.model.TradeResult;
import com.holzyn.actor.domain.market.repository.ActorMarketQuoteRepository;
import com.holzyn.actor.domain.market.repository.ActorMarketTradeRepository;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 市场唯一写入口单测（报价结算幂等赊账）。
 *
 * <p>锁四条硬规则：</p>
 * <ol>
 *   <li>报价随供需变（缺货贵、过剩便宜、钳制上下限）；</li>
 *   <li>结算必须<b>先验货再验钱</b>，任一不足整批拒绝（不写半笔）；</li>
 *   <li>幂等重放不重复扣货收钱；</li>
 *   <li>赊账只扣货不付钱，流水记 paid=false / settled=false。</li>
 * </ol>
 */
class MarketServiceTest {

    private static final Long PROJECT = 1L;

    private static final String SELLER = "loc:9";

    private static final String BUYER = "npc:7";

    private static final long GS = 1000L;

    private static final Long EVENT = 99L;

    private MarketProperties props;

    private ItemProperties itemProps;

    private ActorMarketQuoteRepository quoteRepo;

    private ActorMarketTradeRepository tradeRepo;

    private ActorItemDefRepository defRepo;

    private InventoryPort port;

    private MarketService service;

    @BeforeEach
    void setUp() {
        props = new MarketProperties();
        itemProps = new ItemProperties();
        itemProps.setEnabled(true);
        quoteRepo = mock(ActorMarketQuoteRepository.class);
        tradeRepo = mock(ActorMarketTradeRepository.class);
        defRepo = mock(ActorItemDefRepository.class);
        port = mock(InventoryPort.class);
        service = new MarketService(props, itemProps, quoteRepo, tradeRepo, defRepo);
        ReflectionTestUtils.setField(service, "inventoryPort", port);
    }

    // ==================== 报价 ====================

    @Test
    @DisplayName("报价：无定义时用默认基准价 10，库存中性 → 单价 10")
    void quoteUsesDefaultBasePrice() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(10);

        MarketQuote quote = service.quote(PROJECT, SELLER, "bread", GS);

        assertEquals(10, quote.basePrice());
        assertEquals(10, quote.unitPrice());
        assertEquals(10, quote.supplyQty());
        assertEquals(1.0, quote.priceFactor(), 1e-9);
    }

    @Test
    @DisplayName("报价：物品定义 base_price=40 时按 40 计价")
    void quoteUsesItemDefinitionBasePrice() {
        ActorItemDef def = new ActorItemDef();
        def.setItemKey("bread");
        def.setName("面包");
        def.setBasePrice(40);
        when(defRepo.findByProjectIdAndItemKey(PROJECT, "bread")).thenReturn(Optional.of(def));
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(10);

        assertEquals(40, service.quote(PROJECT, SELLER, "bread", GS).unitPrice());
    }

    @Test
    @DisplayName("报价随供需变：库存 5 → 1.5 倍；库存 100 → 0.7 倍")
    void quoteFollowsSupply() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(5);
        assertEquals(15, service.quote(PROJECT, SELLER, "bread", GS).unitPrice());

        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(100);
        assertEquals(7, service.quote(PROJECT, SELLER, "bread", GS).unitPrice());
    }

    @Test
    @DisplayName("报价钳制：超过 max-price 时按上限")
    void quoteClampsToMaxPrice() {
        props.setMaxPrice(12);
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(5);

        assertEquals(12, service.quote(PROJECT, SELLER, "bread", GS).unitPrice());
    }

    @Test
    @DisplayName("市场关闭 / 端口未装配 → 不报价（返回 null）")
    void quoteReturnsNullWhenDisabled() {
        props.setEnabled(false);
        assertNull(service.quote(PROJECT, SELLER, "bread", GS));

        props.setEnabled(true);
        MarketService noPort = new MarketService(props, itemProps, quoteRepo, tradeRepo, defRepo);
        assertFalse(noPort.enabled());
        assertNull(noPort.quote(PROJECT, SELLER, "bread", GS));
    }

    // ==================== 结算 ====================

    @Test
    @DisplayName("现金成交：扣货 + 收钱两条转移，流水 paid=true/settled=true")
    void cashTradeMovesItemAndCoin() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(3);
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(100);

        TradeResult result = service.trade(request(2, 15, false, "trade:1"));

        verify(port).transfer(PROJECT, "bread", 2, SELLER, BUYER, "trade", EVENT, GS, "trade:1:item");
        verify(port).transfer(PROJECT, "coin", 30, BUYER, SELLER, "trade", EVENT, GS, "trade:1:coin");
        ArgumentCaptor<ActorMarketTrade> captor = ArgumentCaptor.forClass(ActorMarketTrade.class);
        verify(tradeRepo).save(captor.capture());
        assertEquals(30, captor.getValue().getTotalPrice());
        assertEquals(Boolean.TRUE, captor.getValue().getPaid());
        assertEquals(Boolean.FALSE, captor.getValue().getOnCredit());
        assertEquals(Boolean.TRUE, captor.getValue().getSettled());
        assertEquals(15, result.unitPrice());
        assertEquals(30, result.totalPrice());
        assertTrue(result.paid());
        assertFalse(result.replay());
    }

    @Test
    @DisplayName("库存不足 → InsufficientItemException，且一条转移都不发")
    void tradeRejectsWhenStockShort() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(1);

        assertThrows(InsufficientItemException.class,
                () -> service.trade(request(2, 15, false, "trade:2")));
        verify(port, never()).transfer(any(), anyString(), anyInt(), anyString(), anyString(),
                any(), any(), anyLong(), anyString());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    @DisplayName("资金不足 → InsufficientFundsException，货与钱都不动")
    void tradeRejectsWhenFundsShort() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(3);
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(10);

        assertThrows(InsufficientFundsException.class,
                () -> service.trade(request(2, 15, false, "trade:3")));
        verify(port, never()).transfer(any(), anyString(), anyInt(), anyString(), anyString(),
                any(), any(), anyLong(), anyString());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    @DisplayName("赊账成交：只扣货不付钱，流水 paid=false/on_credit=true/settled=false")
    void creditTradeSkipsPayment() {
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(3);
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(0);

        TradeResult result = service.trade(request(2, 15, true, "trade:4"));

        verify(port).transfer(PROJECT, "bread", 2, SELLER, BUYER, "trade", EVENT, GS, "trade:4:item");
        verify(port, never()).transfer(PROJECT, "coin", 30, BUYER, SELLER, "trade", EVENT, GS, "trade:4:coin");
        ArgumentCaptor<ActorMarketTrade> captor = ArgumentCaptor.forClass(ActorMarketTrade.class);
        verify(tradeRepo).save(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getPaid());
        assertEquals(Boolean.TRUE, captor.getValue().getOnCredit());
        assertEquals(Boolean.FALSE, captor.getValue().getSettled());
        assertFalse(result.paid());
        assertTrue(result.credit());
    }

    @Test
    @DisplayName("幂等重放：返回原流水，不再扣货收钱")
    void tradeReplayDoesNotMoveAgain() {
        ActorMarketTrade existing = new ActorMarketTrade();
        existing.setId(42L);
        existing.setItemKey("bread");
        existing.setQty(2);
        existing.setUnitPrice(15);
        existing.setTotalPrice(30);
        existing.setPaid(true);
        existing.setOnCredit(false);
        when(tradeRepo.findByProjectIdAndIdempotencyKey(PROJECT, "trade:5")).thenReturn(Optional.of(existing));
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(1);

        TradeResult result = service.trade(request(2, 15, false, "trade:5"));

        assertTrue(result.replay());
        assertEquals(42L, result.tradeId());
        assertEquals(30, result.totalPrice());
        verify(port, never()).transfer(any(), anyString(), anyInt(), anyString(), anyString(),
                any(), any(), anyLong(), anyString());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    @DisplayName("买家与卖家相同 / 数量非法 / 超上限 → 直接拒绝")
    void tradeRejectsInvalidRequests() {
        assertThrows(IllegalArgumentException.class, () -> service.trade(
                new TradeRequest(PROJECT, SELLER, SELLER, "bread", 1, 10, false, "trade", EVENT, GS, "k1")));
        assertThrows(IllegalArgumentException.class,
                () -> service.trade(request(0, 10, false, "k2")));
        props.setMaxQtyPerTrade(2);
        assertThrows(IllegalArgumentException.class,
                () -> service.trade(request(3, 10, false, "k3")));
    }

    @Test
    @DisplayName("市场关闭时成交抛 IllegalStateException（不是静默成功）")
    void tradeThrowsWhenDisabled() {
        props.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> service.trade(request(1, 10, false, "k4")));
    }

    @Test
    @DisplayName("成交后同步 stock:* 事实（旁人能看见货变少）")
    void tradeSyncsStockFact() {
        WorldFactService factService = mock(WorldFactService.class);
        ReflectionTestUtils.setField(service, "worldFactService", factService);
        // 第 1 次=验货 3、第 2 次=报价 3、第 3 次=成交后余量 2、第 4 次=成交后报价 2
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(3, 3, 2, 2);
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(100);

        service.trade(request(1, 15, false, "trade:6"));

        ArgumentCaptor<FactCommand> captor = ArgumentCaptor.forClass(FactCommand.class);
        verify(factService).upsert(captor.capture());
        assertEquals("stock:loc:9:bread", captor.getValue().factKey());
        assertEquals("stock", captor.getValue().factType());
        assertEquals("location", captor.getValue().visibility());
        assertTrue(captor.getValue().attributesJson().contains("\"qty\":2"));
    }

    @Test
    @DisplayName("事实层失败不拖垮成交（只 WARN）")
    void stockFactFailureDoesNotRollback() {
        WorldFactService factService = mock(WorldFactService.class);
        when(factService.upsert(any(FactCommand.class))).thenThrow(new IllegalStateException("engine 权威"));
        ReflectionTestUtils.setField(service, "worldFactService", factService);
        when(port.quantityOf(PROJECT, SELLER, "bread")).thenReturn(3);
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(100);

        TradeResult result = service.trade(request(1, 15, false, "trade:7"));

        assertEquals(15, result.unitPrice());
        verify(tradeRepo).save(any());
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("欠款查询：未结清的赊账按流水求和")
    void unsettledDebtSumsCreditTrades() {
        ActorMarketTrade a = new ActorMarketTrade();
        a.setTotalPrice(10);
        ActorMarketTrade b = new ActorMarketTrade();
        b.setTotalPrice(20);
        when(tradeRepo.findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(PROJECT, BUYER))
                .thenReturn(List.of(a, b));

        assertEquals(30L, service.unsettledDebtOf(PROJECT, BUYER));
    }

    @Test
    @DisplayName("货币键归一化：空值回退 coin，大小写/空白折叠")
    void currencyKeyIsNormalized() {
        assertEquals("coin", service.currencyKey());
        props.setCurrencyItemKey("  Coin  ");
        assertEquals("coin", service.currencyKey());
        props.setCurrencyItemKey("silver-coin");
        assertEquals("silver-coin", service.currencyKey());
    }

    // ==================== 还款（闭环） ====================

    /**
     * 还款：先还最老的一笔，钱够就结清并真的转账，钱不够就一笔都不动。
     *
     * <p>实测缺口：赊账成交写 {@code settled=false}，接线前没有任何一处会把它改回 true，
     * 欠条只会越积越多（{@code unsettledDebtOf} 永远不降）。</p>
     */
    @Test
    @DisplayName("还款：按时间顺序结清，钱不够时停手（不做部分还款）")
    void settleDebtsPaysOldestFirst() {
        ActorMarketTrade first = new ActorMarketTrade();
        first.setTotalPrice(10);
        first.setSellerRef(SELLER);
        first.setBuyerRef(BUYER);
        first.setSettled(false);
        ActorMarketTrade second = new ActorMarketTrade();
        second.setTotalPrice(20);
        second.setSellerRef(SELLER);
        second.setBuyerRef(BUYER);
        second.setSettled(false);
        when(tradeRepo.findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(PROJECT, BUYER))
                .thenReturn(List.of(first, second));
        // 第一笔后剩 25，第二笔 20 够；再查一次剩 5
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(35, 25, 5);

        assertEquals(2, service.settleDebts(PROJECT, BUYER, GS));

        assertTrue(Boolean.TRUE.equals(first.getSettled()), "第一笔要结清");
        assertTrue(Boolean.TRUE.equals(second.getSettled()), "第二笔也要结清");
        verify(port).transfer(PROJECT, "coin", 10, BUYER, SELLER, "repay", null, GS, null);
        verify(port).transfer(PROJECT, "coin", 20, BUYER, SELLER, "repay", null, GS, null);
    }

    @Test
    @DisplayName("还款：身上没钱 → 一笔都不动（返回 0）")
    void settleDebtsStopsWhenBroke() {
        ActorMarketTrade debt = new ActorMarketTrade();
        debt.setTotalPrice(50);
        debt.setSellerRef(SELLER);
        debt.setBuyerRef(BUYER);
        // 赊账流水：settled 的实体缺省是 TRUE，只有成交时显式写 false（trade.setSettled(!credit)）
        debt.setSettled(false);
        when(tradeRepo.findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(PROJECT, BUYER))
                .thenReturn(List.of(debt));
        when(port.quantityOf(PROJECT, BUYER, "coin")).thenReturn(10);

        assertEquals(0, service.settleDebts(PROJECT, BUYER, GS));
        assertFalse(Boolean.TRUE.equals(debt.getSettled()), "钱不够不能假装还清");
        verify(port, never()).transfer(any(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("还款：没有欠账时返回 0，市场关闭时也不动账")
    void settleDebtsNoop() {
        when(tradeRepo.findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(PROJECT, BUYER))
                .thenReturn(List.of());
        assertEquals(0, service.settleDebts(PROJECT, BUYER, GS));

        props.setEnabled(false);
        assertEquals(0, service.settleDebts(PROJECT, BUYER, GS));
    }

    // ==================== 工具 ====================

    private static TradeRequest request(int qty, int unitPrice, boolean credit, String idem) {
        return new TradeRequest(PROJECT, SELLER, BUYER, "bread", qty, unitPrice, credit,
                "trade", EVENT, GS, idem);
    }
}
