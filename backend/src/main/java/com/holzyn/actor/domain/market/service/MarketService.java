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

import com.holzyn.actor.domain.item.entity.ActorItemDef;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.model.ItemRefs;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.repository.ActorItemDefRepository;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.market.config.MarketProperties;
import com.holzyn.actor.domain.market.entity.ActorMarketQuote;
import com.holzyn.actor.domain.market.entity.ActorMarketTrade;
import com.holzyn.actor.domain.market.model.InsufficientFundsException;
import com.holzyn.actor.domain.market.model.MarketQuote;
import com.holzyn.actor.domain.market.model.TradeRequest;
import com.holzyn.actor.domain.market.model.TradeResult;
import com.holzyn.actor.domain.market.pricing.MarketPricing;
import com.holzyn.actor.domain.market.repository.ActorMarketQuoteRepository;
import com.holzyn.actor.domain.market.repository.ActorMarketTradeRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * 市场交易唯一写入口（交易与价格）。
 *
 * <p><b>单一写入口</b>：{@code actor_market_quote} / {@code actor_market_trade} 两张表
 * 只由本类写。物品数量变化仍走 {@link InventoryPort}（唯一写入口），本类<b>不直接</b>
 * 碰 {@code actor_item_stack}/{@code actor_item_ledger}——市场只决定「这笔买卖成不成、
 * 多少钱」，货与钱的移动由物品域记账。</p>
 *
 * <p><b>事务边界</b>：本类零 AI、零 HTTP，方法带 {@code @Transactional}；
 * 任一校验失败（货不够 / 钱不够）抛异常，由 {@code EffectApplier} 整批回滚，
 * 永不出现「货扣了钱没付」或负库存。</p>
 *
 * <p><b>价格</b>：{@link MarketPricing} 是唯一公式源（与宏观粮价同形）。
 * 报价行保存 {@code supply_qty}/{@code demand_qty}/{@code price_factor}，
 * 让「为什么是这个价」可复算、可解释。</p>
 */
@Slf4j
@Service
public class MarketService {

    /** 账本原因默认值 */
    private static final String DEFAULT_REASON = "trade";

    /** 幂等键参与拼后缀时的最大长度（InventoryService 会截断到 80，留出后缀余量） */
    private static final int IDEM_BASE_MAX = 60;

    private final MarketProperties properties;

    private final ItemProperties itemProperties;

    private final ActorMarketQuoteRepository quoteRepository;

    private final ActorMarketTradeRepository tradeRepository;

    private final ActorItemDefRepository defRepository;

    /** 物品端口（item.enabled=true 且 provider=db 才装配；未装配时市场整体不生效） */
    @Autowired(required = false)
    private InventoryPort inventoryPort;

    /** 事实层（stock:* 同步用；未装配时跳过同步，不影响成交） */
    @Autowired(required = false)
    private WorldFactService worldFactService;

    /** 地点仓储（只读：把 loc:{id} 翻成「青岳主峰」这样的地名，供事实展示） */
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    /** 记忆服务（对接：买卖双方各留一条观察记忆；未装配跳过） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.memory.AgentMemoryService agentMemoryService;

    /** 角色仓储（只读：把 npc:{id} 翻成名字写进记忆；未装配用 ref 兜底） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository characterRepository;

    /**
     * 构造（新类允许构造器注入；既有服务不改签名）。
     *
     * @param properties      市场配置
     * @param itemProperties  物品域配置（数量上限等）
     * @param quoteRepository 报价仓储
     * @param tradeRepository 流水仓储
     * @param defRepository   物品定义仓储（只读，取基准价）
     */
    public MarketService(MarketProperties properties, ItemProperties itemProperties,
                         ActorMarketQuoteRepository quoteRepository,
                         ActorMarketTradeRepository tradeRepository,
                         ActorItemDefRepository defRepository) {
        this.properties = properties;
        this.itemProperties = itemProperties;
        this.quoteRepository = quoteRepository;
        this.tradeRepository = tradeRepository;
        this.defRepository = defRepository;
    }

    // ==================== 开关与读入口 ====================

    /**
     * 市场是否生效：市场开关 + 物品端口已装配。
     *
     * @return true=可以报价/结算
     */
    public boolean enabled() {
        return properties != null && properties.isEnabled() && inventoryPort != null;
    }

    /**
     * 计价货币的物品键（默认 coin）。
     *
     * @return 归一化后的货币键
     */
    public String currencyKey() {
        String key = properties == null ? null : properties.getCurrencyItemKey();
        if (key == null || key.isBlank()) {
            return "coin";
        }
        return InventoryService.normalizeItemKey(key);
    }

    /**
     * 某卖家某物品的当前报价（缺失时按基准价懒建，价格随供需实时重算）。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     * @param itemKey   物品键
     * @param gameSecond 当前游戏秒
     *
     * @return 报价；市场未生效返回 null
     */
    @Transactional
    public MarketQuote quote(Long projectId, String sellerRef, String itemKey, long gameSecond) {
        if (!enabled()) {
            return null;
        }
        requireProject(projectId);
        String seller = ItemRefs.requireHolder(sellerRef, "seller");
        String key = InventoryService.normalizeItemKey(itemKey);

        int supply = inventoryPort.quantityOf(projectId, seller, key);
        int demand = demandOf(projectId, seller, key, gameSecond);
        int base = basePriceOf(projectId, key);
        double factor = MarketPricing.itemFactor(supply, demand, windowDays(), properties.getNeutralStock());
        int price = MarketPricing.price(base, factor, properties.getMinPrice(), properties.getMaxPrice());

        ActorMarketQuote row = quoteRepository
                .findByProjectIdAndSellerRefAndItemKey(projectId, seller, key).orElse(null);
        boolean isNew = row == null;
        if (isNew) {
            row = new ActorMarketQuote();
            row.setProjectId(projectId);
            row.setSellerRef(seller);
            row.setItemKey(key);
            row.setVersion(1);
        }
        boolean changed = !equalsInt(row.getUnitPrice(), price)
                || !equalsInt(row.getSupplyQty(), supply)
                || !equalsInt(row.getDemandQty(), demand)
                || !equalsInt(row.getBasePrice(), base);
        if (changed || isNew) {
            row.setBasePrice(base);
            row.setUnitPrice(price);
            row.setSupplyQty(supply);
            row.setDemandQty(demand);
            row.setPriceFactor(BigDecimal.valueOf(factor).setScale(3, RoundingMode.HALF_UP));
            row.setQuoteGameSecond(gameSecond);
            row.setVersion(isNew ? 1 : (row.getVersion() == null ? 1 : row.getVersion() + 1));
            row.setUpdatedAt(LocalDateTime.now());
            quoteRepository.save(row);
        }
        return new MarketQuote(seller, key, base, price, supply, demand, factor, gameSecond);
    }

    /**
     * 某卖家某物品的单价（市场未生效返回 0）。
     *
     * @param projectId 项目 ID
     * @param sellerRef 卖家
     * @param itemKey   物品键
     * @param gameSecond 当前游戏秒
     *
     * @return 单价
     */
    @Transactional
    public int priceOf(Long projectId, String sellerRef, String itemKey, long gameSecond) {
        MarketQuote quote = quote(projectId, sellerRef, itemKey, gameSecond);
        return quote == null ? 0 : quote.unitPrice();
    }

    /**
     * 买家未结清的赊账总额（欠款查询；还款接线前供排障/前端用）。
     *
     * @param projectId 项目 ID
     * @param buyerRef  买家
     *
     * @return 欠款总额（无记录=0）
     */
    @Transactional(readOnly = true)
    public long unsettledDebtOf(Long projectId, String buyerRef) {
        if (projectId == null || buyerRef == null || buyerRef.isBlank()) {
            return 0L;
        }
        long sum = 0L;
        for (ActorMarketTrade trade : tradeRepository
                .findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(projectId, buyerRef.trim())) {
            sum += trade.getTotalPrice() == null ? 0 : trade.getTotalPrice();
        }
        return sum;
    }

    /**
     * 偿还赊账（还款闭环）：按时间顺序用身上的现钱结清未结清的成交。
     *
     * <p><b>为什么必须有这条路</b>：赊账成交写的是 {@code settled=false}，而在还款接线之前
     * 没有任何一处会把它改回 {@code true}——欠条永远是欠条，{@link #unsettledDebtOf} 只会越积越多。
     * 真实世界里赊了账是要还的：手头有钱就先还最老的一笔，还不起就一笔都不动
     * （不做部分还款——{@code total_price} 是整笔金额，部分还款会让下一次重复支付）。</p>
     *
     * @param projectId  项目 ID
     *
     * @param buyerRef   还款人
     *
     * @param gameSecond 当前游戏秒
     *
     * @return 结清笔数（0=没有欠账或钱不够）
     */
    @Transactional
    public int settleDebts(Long projectId, String buyerRef, long gameSecond) {
        if (!enabled() || projectId == null || buyerRef == null || buyerRef.isBlank()) {
            return 0;
        }
        String buyer = ItemRefs.requireHolder(buyerRef, "buyer");
        String currency = currencyKey();
        int settled = 0;
        for (ActorMarketTrade trade : tradeRepository
                .findByProjectIdAndBuyerRefAndSettledFalseOrderByGameSecondAsc(projectId, buyer)) {
            long due = trade.getTotalPrice() == null ? 0 : trade.getTotalPrice();
            if (due <= 0) {
                trade.setSettled(true);
                tradeRepository.save(trade);
                settled++;
                continue;
            }
            int funds = inventoryPort.quantityOf(projectId, buyer, currency);
            if (funds < due) {
                // 先还最老的一笔；这一笔都还不起就停手（不做部分还款，避免重复支付）
                break;
            }
            inventoryPort.transfer(projectId, currency, (int) due, buyer, trade.getSellerRef(), "repay",
                    null, gameSecond, null);
            trade.setSettled(true);
            trade.setPaid(true);
            tradeRepository.save(trade);
            settled++;
        }
        if (settled > 0) {
            log.info("[市场] 赊账结清: project={} buyer={} 笔数={}", projectId, buyer, settled);
        }
        return settled;
    }

    // ==================== 成交 ====================

    /**
     * 结算一笔成交（幂等）。
     *
     * <p>顺序固定：<b>幂等 → 验货 → 定价 → 验钱 → 扣货 → 收钱 → 记流水 → 刷新报价 → 同步库存事实</b>。
     * 任何一步抛错都整批回滚（外层事务），不会留下半笔交易。</p>
     *
     * @param req 成交请求
     *
     * @return 成交结果（幂等重放时 {@code replay=true}）
     */
    @Transactional
    public TradeResult trade(TradeRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("成交请求不能为空");
        }
        if (!enabled()) {
            throw new IllegalStateException("市场未启用（需要 item.enabled=true 且 market.enabled=true）");
        }
        requireProject(req.projectId());
        String seller = ItemRefs.requireHolder(req.sellerRef(), "seller");
        String buyer = ItemRefs.requireHolder(req.buyerRef(), "buyer");
        if (seller.equals(buyer)) {
            throw new IllegalArgumentException("买家与卖家不能相同：" + seller);
        }
        String key = InventoryService.normalizeItemKey(req.itemKey());
        int qty = requireQty(req.qty());
        String idem = normalizeIdem(req.idempotencyKey());
        String currency = currencyKey();

        // ① 幂等：同一效果重放直接返回原结果，不再扣货收钱
        if (idem != null) {
            Optional<ActorMarketTrade> existing = tradeRepository
                    .findByProjectIdAndIdempotencyKey(req.projectId(), idem);
            if (existing.isPresent()) {
                ActorMarketTrade t = existing.get();
                int left = inventoryPort.quantityOf(req.projectId(), seller, key);
                MarketQuote now = quote(req.projectId(), seller, key, req.gameSecond());
                log.info("[市场] 幂等重放，跳过：project={} idem={} trade={}", req.projectId(), idem, t.getId());
                return new TradeResult(t.getId(), t.getItemKey(), intOf(t.getQty(), 0),
                        intOf(t.getUnitPrice(), 0), intOf(t.getTotalPrice(), 0),
                        Boolean.TRUE.equals(t.getPaid()), Boolean.TRUE.equals(t.getOnCredit()),
                        left, now == null ? intOf(t.getUnitPrice(), 0) : now.unitPrice(), true);
            }
        }

        // ② 验货：货架必须有货（严格「没有就是没有」）
        int stock = inventoryPort.quantityOf(req.projectId(), seller, key);
        if (stock < qty) {
            throw new InsufficientItemException(key, seller, qty, stock);
        }

        // ③ 定价：裁决层已算过就沿用同一个数（报价与收款不能是两套数）
        MarketQuote quote = quote(req.projectId(), seller, key, req.gameSecond());
        int unitPrice = req.unitPrice() != null && req.unitPrice() > 0
                ? req.unitPrice() : (quote == null ? 0 : quote.unitPrice());
        long totalLong = (long) unitPrice * qty;
        if (totalLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("成交金额超出上限：" + totalLong);
        }
        int total = (int) totalLong;

        // ④ 验钱：现金交易才验（赊账先拿货、后付款）
        if (!req.credit()) {
            int funds = inventoryPort.quantityOf(req.projectId(), buyer, currency);
            if (funds < total) {
                throw new InsufficientFundsException(buyer, currency, total, funds);
            }
        }

        // ⑤ 扣货：卖家 → 买家（原因 trade，走账本）
        String reason = normalizeReason(req.reason());
        String idemBase = idem == null ? null : truncateIdem(idem);
        inventoryPort.transfer(req.projectId(), key, qty, seller, buyer, reason,
                req.eventId(), req.gameSecond(), idemBase == null ? null : idemBase + ":item");

        // ⑥ 收钱：买家 → 卖家（赊账不付；总价为 0 时不产生空转移）
        if (!req.credit() && total > 0) {
            inventoryPort.transfer(req.projectId(), currency, total, buyer, seller, reason,
                    req.eventId(), req.gameSecond(), idemBase == null ? null : idemBase + ":coin");
        }

        // ⑦ 记流水
        ActorMarketTrade trade = new ActorMarketTrade();
        trade.setProjectId(req.projectId());
        trade.setSellerRef(seller);
        trade.setBuyerRef(buyer);
        trade.setItemKey(key);
        trade.setQty(qty);
        trade.setUnitPrice(unitPrice);
        trade.setTotalPrice(total);
        trade.setCurrencyKey(currency);
        trade.setPaid(!req.credit());
        trade.setOnCredit(req.credit());
        trade.setSettled(!req.credit());
        trade.setReason(reason);
        trade.setEventId(req.eventId());
        trade.setGameSecond(req.gameSecond());
        trade.setIdempotencyKey(idem);
        trade.setCreatedAt(LocalDateTime.now());
        tradeRepository.save(trade);

        // ⑧ 刷新报价（成交后库存下降 → 下一次更贵）
        int left = inventoryPort.quantityOf(req.projectId(), seller, key);
        MarketQuote after = quote(req.projectId(), seller, key, req.gameSecond());

        // ⑨ 同步 stock:* 事实（失败只 WARN，不让「看见」的副作用拖垮成交）
        syncStockFact(req, seller, key, left, req.gameSecond());

        // ⑩ 让买卖双方记住这件事（对接：交易不只是一行流水，是人会记得的经历）
        rememberTrade(req, seller, buyer, key, qty, unitPrice, total);

        log.info("[市场] 成交：{} 从 {} 买 {} x{}，单价={} 总价={} 赊账={} 余量={}",
                buyer, seller, key, qty, unitPrice, total, req.credit(), left);
        return new TradeResult(trade.getId(), key, qty, unitPrice, total, !req.credit(), req.credit(),
                left, after == null ? unitPrice : after.unitPrice(), false);
    }

    // ==================== 内部 ====================

    /**
     * 成交后写两条观察记忆（买家一条、卖家一条）。
     *
     * <p><b>为什么值得多写两行</b>：记忆是「这个人后来为什么会提起这件事」的唯一来源。
     * 交易只记流水的话，三天后 NPC 被问到「上次那袋面」会一脸茫然——而人不会。
     * 卖家是货架（{@code loc:}）时不写：货架不是人，没有记忆。</p>
     *
     * <p>失败只 WARN：记忆写不进去不该把已经完成的买卖回滚。</p>
     */
    private void rememberTrade(TradeRequest req, String seller, String buyer, String key,
                               int qty, int unitPrice, int total) {
        if (agentMemoryService == null || req == null) {
            return;
        }
        String name = itemName(req.projectId(), key);
        String place = locationNameOf(seller);
        try {
            String buyerText = (place == null ? "" : "在" + place)
                    + (total > 0 ? "花 " + total + " 文" : "赊账")
                    + "买了 " + qty + " 件" + name + "（单价 " + unitPrice + " 文）";
            agentMemoryService.recordObservation(req.projectId(), buyer, buyerText, 2, null);
        } catch (RuntimeException e) {
            log.warn("[市场] 买家记忆写入失败（不影响成交）：buyer={} : {}", buyer, e.getMessage());
        }
        if (!ItemRefs.isNpc(seller)) {
            return;
        }
        try {
            String sellerText = "把 " + qty + " 件" + name + "卖给了" + refName(buyer)
                    + (total > 0 ? "，收 " + total + " 文" : "，记了赊账");
            agentMemoryService.recordObservation(req.projectId(), seller, sellerText, 2, null);
        } catch (RuntimeException e) {
            log.warn("[市场] 卖家记忆写入失败（不影响成交）：seller={} : {}", seller, e.getMessage());
        }
    }

    /** ref → 可读名（角色查名字，查不到用 ref）。 */
    private String refName(String ref) {
        Long id = ItemRefs.idOf(ref);
        if (id == null || characterRepository == null) {
            return ref;
        }
        try {
            return characterRepository.findById(id)
                    .map(c -> c.getName() == null || c.getName().isBlank() ? ref : c.getName())
                    .orElse(ref);
        } catch (RuntimeException e) {
            return ref;
        }
    }

    /** 需求窗口内的成交量。 */
    private int demandOf(Long projectId, String seller, String key, long gameSecond) {
        long since = gameSecond - (long) windowDays() * 86400L;
        Long sum = tradeRepository.sumQtySince(projectId, seller, key, since);
        return sum == null ? 0 : sum.intValue();
    }

    /** 基准价：本项目定义 → 全局定义 → 默认基准价。 */
    private int basePriceOf(Long projectId, String key) {
        Integer base = defRepository.findByProjectIdAndItemKey(projectId, key)
                .map(ActorItemDef::getBasePrice)
                .orElse(null);
        if (base == null || base <= 0) {
            base = defRepository.findByProjectIdAndItemKey(ActorItemDef.GLOBAL_PROJECT, key)
                    .map(ActorItemDef::getBasePrice)
                    .orElse(null);
        }
        if (base == null || base <= 0) {
            return Math.max(1, properties.getDefaultBasePrice());
        }
        return base;
    }

    /**
     * 成交后把「货架还剩多少」写成 {@code stock:*} 事实（§4.6 副作用）。
     *
     * <p>为什么必须 try/catch：事实层在 ENGINE 权威下会被 {@code AuthorityWriteGuard}
     * 拒绝，而「货已经交易完成」不该因为一条观察事实写不进去就整笔回滚。</p>
     */
    private void syncStockFact(TradeRequest req, String seller, String key, int left, long gameSecond) {
        if (properties == null || !properties.isStockFactSync() || worldFactService == null) {
            return;
        }
        try {
            String factKey = "stock:" + seller + ":" + key;
            String name = itemName(req.projectId(), key);
            String stateText = left <= 0 ? name + "卖完了" : name + "还剩 " + left + " 件";
            String attrs = "{\"qty\":" + left + ",\"sellerRef\":\"" + seller + "\",\"itemKey\":\"" + key + "\"}";
            Long locationId = ItemRefs.isLocation(seller) ? ItemRefs.idOf(seller) : null;
            FactCommand cmd = new FactCommand(req.projectId(), "stock", factKey, name, stateText, attrs,
                    locationId, locationNameOf(seller), "location", FactAuthority.BRAIN,
                    gameSecond, null, gameSecond, req.buyerRef(), req.eventId(), 2, null);
            worldFactService.upsert(cmd);
        } catch (RuntimeException e) {
            log.warn("[市场] stock 事实同步失败（不影响成交）：seller={} item={} : {}",
                    seller, key, e.getMessage());
        }
    }

    /** 物品展示名（定义缺失时用键本身）。 */
    private String itemName(Long projectId, String key) {
        return defRepository.findByProjectIdAndItemKey(projectId, key)
                .map(ActorItemDef::getName)
                .or(() -> defRepository.findByProjectIdAndItemKey(ActorItemDef.GLOBAL_PROJECT, key)
                        .map(ActorItemDef::getName))
                .orElse(key);
    }

    /**
     * 卖家的地名（{@code loc:{id}} → 「青岳主峰」）。
     *
     * <p>事实是给人看的：{@code locationName=loc:65} 这种内部 ref 写进事实台账，
     * 玩家和提示词都读不懂。查不到就返回 null（不编地名）。</p>
     */
    private String locationNameOf(String sellerRef) {
        if (!ItemRefs.isLocation(sellerRef) || locationRepository == null) {
            return null;
        }
        Long id = ItemRefs.idOf(sellerRef);
        if (id == null) {
            return null;
        }
        try {
            return locationRepository.findById(id).map(ActorWorldLocation::getName).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int windowDays() {
        return Math.max(1, properties.getDemandWindowDays());
    }

    private int requireQty(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("成交数量必须为正数：" + qty);
        }
        int max = Math.max(1, properties.getMaxQtyPerTrade());
        if (itemProperties != null) {
            max = Math.min(max, Math.max(1, itemProperties.getMaxQtyPerTransfer()));
        }
        if (qty > max) {
            throw new IllegalArgumentException("单笔成交数量超过上限（" + max + "）：" + qty);
        }
        return qty;
    }

    private static void requireProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("市场操作缺少 projectId");
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        String value = reason.trim().toLowerCase(Locale.ROOT);
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static String normalizeIdem(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    /** 幂等键截到 60，给 {@code :item}/{@code :coin} 后缀留出空间（账本列宽 80）。 */
    private static String truncateIdem(String key) {
        return key.length() > IDEM_BASE_MAX ? key.substring(0, IDEM_BASE_MAX) : key;
    }

    private static boolean equalsInt(Integer a, int b) {
        return a != null && a == b;
    }

    private static int intOf(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
