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

package com.holzyn.actor.domain.market.smoke;

import com.holzyn.actor.domain.item.config.ItemDbProviderCondition;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.market.model.InsufficientFundsException;
import com.holzyn.actor.domain.market.model.MarketQuote;
import com.holzyn.actor.domain.market.model.TradeRequest;
import com.holzyn.actor.domain.market.model.TradeResult;
import com.holzyn.actor.domain.market.service.MarketService;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.worldstore.WorldContext;
import com.holzyn.actor.domain.worldstore.WorldLifecycleService;
import com.holzyn.actor.domain.worldstore.WorldRegistryService;
import com.holzyn.actor.domain.worldstore.WorldSummaryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 真库冒烟（仅 {@code i2-smoke} profile 启用）。
 *
 * <p><b>验什么</b>：真实 H2 文件库 + 真实 Flyway 下的报价、成交、幂等、赊账、
 * 价格随供需变化、{@code stock:*} 事实同步——这些都不是 Mockito 能证明的
 * （唯一索引、事务回滚、事实日志都要真库）。</p>
 *
 * <p><b>安全边界</b>：只在 {@code HOLOZYN_ACTOR_DATA_DIR} 指向的副本上跑
 * （约定 {@code tmp/i2-smoke/data}），绝不碰真实存档；产物写
 * {@code target/assertion-baseline/i2/smoke-<ts>.json}。</p>
 *
 * <p>启用方式：</p>
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=i2-smoke
 *   （env：HOLOZYN_ACTOR_DATA_DIR=tmp/i2-smoke/data、HOLOZYN_ACTOR_ITEM_ENABLED=true、
 *     HOLZYN_ACTOR_SERVER_PORT=18083）
 * </pre>
 */
@Slf4j
@Component
@Profile("i2-smoke")
@Order(Ordered.LOWEST_PRECEDENCE)
@Conditional(ItemDbProviderCondition.class)
public class MarketSmokeRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    private final InventoryService inventory;

    /** 生产链路走端口（裁决/效果读的就是它），冒烟也必须走同一条 */
    private final InventoryPort port;

    private final MarketService market;

    private final WorldFactQuery factQuery;

    private final JdbcTemplate jdbc;

    /**
     * 构造。
     *
     * @param registry  世界索引
     * @param lifecycle 世界生命周期（打开/关闭唯一入口）
     * @param inventory 物品唯一写入口（备货场景用）
     * @param port      物品端口（数量断言用）
     * @param market    市场唯一写入口（报价/成交场景）
     * @param factQuery 事实查询（stock 事实断言）
     * @param jdbc      路由数据源（打开世界后指向世界库）
     */
    public MarketSmokeRunner(WorldRegistryService registry, WorldLifecycleService lifecycle,
                             InventoryService inventory, InventoryPort port, MarketService market,
                             WorldFactQuery factQuery, JdbcTemplate jdbc) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.port = port;
        this.market = market;
        this.factQuery = factQuery;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long worldId = resolveWorldId();
        log.info("[I2-SMOKE] 开始：worldId={}", worldId);
        WorldLifecycleService.OpenOutcome opened = lifecycle.open(worldId);
        log.info("[I2-SMOKE] 世界已打开：uid={} newlyOpened={} 耗时={}ms",
                opened.worldUid(), opened.newlyOpened(), opened.elapsedMs());
        try {
            Map<String, Object> report = WorldContext.callAsWorld(() -> runScenarios(worldId, opened.worldUid()));
            writeReport(report);
        } finally {
            try {
                WorldLifecycleService.CloseOutcome closed = lifecycle.close(worldId);
                log.info("[I2-SMOKE] 世界已关闭：uid={} 耗时={}ms", closed.worldUid(), closed.elapsedMs());
            } catch (RuntimeException e) {
                log.warn("[I2-SMOKE] 关闭世界失败（不影响证据）：{}", e.toString());
            }
        }
    }

    // ==================== 场景 ====================

    private Map<String, Object> runScenarios(long worldId, String worldUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name from actor_profile where project_id = ? and deleted = 0 "
                        + "order by importance desc, id asc limit 3", worldId);
        if (rows.size() < 2) {
            throw new IllegalStateException("I2 冒烟需要至少 2 个角色，实际 " + rows.size()
                    + "（世界 " + worldUid + "）");
        }
        String buyer = "npc:" + ((Number) rows.get(0).get("id")).longValue();
        String poor = "npc:" + ((Number) rows.get(1).get("id")).longValue();

        List<Map<String, Object>> locations = jdbc.queryForList(
                "select id, name from actor_world_location where project_id = ? order by id asc limit 1", worldId);
        if (locations.isEmpty()) {
            throw new IllegalStateException("I2 冒烟需要至少 1 个地点（世界 " + worldUid + "）");
        }
        String shelf = "loc:" + ((Number) locations.get(0).get("id")).longValue();
        String locationName = String.valueOf(locations.get(0).get("name"));

        List<Map<String, Object>> steps = new ArrayList<>();
        long gs = 100_000L;

        // ① 备货：货架 10 件面包（正好是中性库存）、买家 100 文
        inventory.add(worldId, "bread", 10, shelf, "initial", null, gs, "i2:seed:shelf");
        inventory.add(worldId, "coin", 100, buyer, "initial", null, gs, "i2:seed:buyer");
        steps.add(check("货架备货 10 件面包", 10, port.quantityOf(worldId, shelf, "bread")));
        steps.add(check("买家备钱 100 文", 100, port.quantityOf(worldId, buyer, "coin")));

        // ② 初始报价：无定义 → 默认基准价 10，库存=中性库存 → 单价 10
        MarketQuote before = market.quote(worldId, shelf, "bread", gs);
        steps.add(check("初始报价非空", true, before != null));
        steps.add(check("初始单价 = 默认基准价 10", 10, before == null ? -1 : before.unitPrice()));
        steps.add(check("报价记录货架存量 10", 10, before == null ? -1 : before.supplyQty()));

        // ③ 现金成交 3 件
        TradeResult cash = market.trade(new TradeRequest(worldId, shelf, buyer, "bread", 3,
                before == null ? null : before.unitPrice(), false, "trade", null, gs, "i2:trade:1"));
        steps.add(check("成交后买家有 3 件", 3, port.quantityOf(worldId, buyer, "bread")));
        steps.add(check("成交后货架剩 7 件", 7, port.quantityOf(worldId, shelf, "bread")));
        steps.add(check("买家付 30 文后剩 70", 70, port.quantityOf(worldId, buyer, "coin")));
        steps.add(check("卖家收到 30 文", 30, port.quantityOf(worldId, shelf, "coin")));
        steps.add(check("流水总价 30", 30, cash.totalPrice()));
        steps.add(check("现金流水已付款", true, cash.paid()));

        // ④ 价格随供需变：库存从 10 降到 7 → 单价上涨
        steps.add(check("成交后单价上涨（供需）", true, cash.priceAfter() > (before == null ? 0 : before.unitPrice())));
        steps.add(check("成交后新价可解释（>10）", true, cash.priceAfter() > 10));

        // ⑤ 幂等重放：不重复扣货/收钱
        TradeResult replay = market.trade(new TradeRequest(worldId, shelf, buyer, "bread", 3,
                before == null ? null : before.unitPrice(), false, "trade", null, gs, "i2:trade:1"));
        steps.add(check("同幂等键重放标记 replay", true, replay.replay()));
        steps.add(check("重放后买家仍 3 件", 3, port.quantityOf(worldId, buyer, "bread")));
        steps.add(check("重放后买家仍 70 文", 70, port.quantityOf(worldId, buyer, "coin")));
        steps.add(check("同幂等键流水只有 1 条", 1, countTrades(worldId, "i2:trade:1")));

        // ⑥ 资金不足：穷买家（0 文）被拒绝，货与钱都不动
        boolean noFunds = false;
        try {
            market.trade(new TradeRequest(worldId, shelf, poor, "bread", 1, 10, false, "trade",
                    null, gs, "i2:trade:poor"));
        } catch (InsufficientFundsException e) {
            noFunds = true;
        }
        steps.add(check("没钱买家被拒绝（NO_FUNDS）", true, noFunds));
        steps.add(check("拒绝后货架仍 7 件", 7, port.quantityOf(worldId, shelf, "bread")));

        // ⑦ 库存不足：超量被拒绝，无负库存
        boolean noStock = false;
        try {
            market.trade(new TradeRequest(worldId, shelf, buyer, "bread", 99, 10, false, "trade",
                    null, gs, "i2:trade:over"));
        } catch (InsufficientItemException e) {
            noStock = true;
        }
        steps.add(check("超量成交被拒绝（NO_STOCK）", true, noStock));
        steps.add(check("拒绝后货架仍 7 件（回滚）", 7, port.quantityOf(worldId, shelf, "bread")));

        // ⑧ 赊账：先拿货、不付钱、记欠款
        int creditPrice = cash.priceAfter();
        TradeResult credit = market.trade(new TradeRequest(worldId, shelf, buyer, "bread", 1,
                creditPrice, true, "trade", null, gs, "i2:trade:credit"));
        steps.add(check("赊账流水未付款", false, credit.paid()));
        steps.add(check("赊账后买家有 4 件", 4, port.quantityOf(worldId, buyer, "bread")));
        steps.add(check("赊账不扣钱（买家仍 70 文）", 70, port.quantityOf(worldId, buyer, "coin")));
        steps.add(check("赊账记欠款 = 单价", (long) creditPrice, market.unsettledDebtOf(worldId, buyer)));
        steps.add(check("赊账后货架剩 6 件", 6, port.quantityOf(worldId, shelf, "bread")));

        // ⑨ stock:* 事实同步（旁人能「看见」货变少）
        Optional<ActorWorldFact> stockFact = factQuery.byKey(worldId, "stock:" + shelf + ":bread");
        steps.add(check("stock 事实已写入", true, stockFact.isPresent()));
        steps.add(check("stock 事实可见性=location", "location",
                stockFact.map(ActorWorldFact::getVisibility).orElse("-")));
        steps.add(check("stock 事实记剩余 6 件", true,
                stockFact.map(f -> f.getAttributesJson() != null && f.getAttributesJson().contains("\"qty\":6"))
                        .orElse(false)));
        steps.add(check("stock 事实带地点名", locationName,
                stockFact.map(ActorWorldFact::getLocationName).orElse("-")));

        // ⑩ 库级不变量
        steps.add(check("全库无负库存", 0, count(worldId,
                "select count(*) from actor_item_stack where project_id = ? and quantity < 0")));
        steps.add(check("报价表只有 1 行（一个卖家一种货一个价）", 1, count(worldId,
                "select count(*) from actor_market_quote where project_id = ?")));
        steps.add(check("流水共 2 条（现金 + 赊账）", 2, count(worldId,
                "select count(*) from actor_market_trade where project_id = ?")));
        steps.add(check("V213 迁移已应用", 1, countAll(
                "select count(*) from flyway_schema_history where version = '213' and success = true")));

        long passed = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count();
        long failed = steps.size() - passed;
        log.info("[I2-SMOKE] 场景完成：{} 通过 / {} 失败", passed, failed);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ts", LocalDateTime.now().toString());
        report.put("worldId", worldId);
        report.put("worldUid", worldUid);
        report.put("buyer", buyer);
        report.put("poorBuyer", poor);
        report.put("shelf", shelf);
        report.put("passed", passed);
        report.put("failed", failed);
        report.put("steps", steps);
        return report;
    }

    // ==================== 辅助 ====================

    private Integer countTrades(long worldId, String idem) {
        return jdbc.queryForObject(
                "select count(*) from actor_market_trade where project_id = ? and idempotency_key = ?",
                Integer.class, worldId, idem);
    }

    private Integer count(long worldId, String sql) {
        return jdbc.queryForObject(sql, Integer.class, worldId);
    }

    private Integer countAll(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static Map<String, Object> check(String name, Object expected, Object actual) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("expected", expected);
        step.put("actual", actual);
        step.put("pass", expected == null ? actual == null : expected.equals(actual));
        if (!Boolean.TRUE.equals(step.get("pass"))) {
            log.error("[I2-SMOKE] 断言失败：{} 期望={} 实际={}", name, expected, actual);
        }
        return step;
    }

    private long resolveWorldId() {
        String forced = System.getenv("HOLOZYN_I2_SMOKE_WORLD_ID");
        if (forced != null && !forced.isBlank()) {
            return Long.parseLong(forced.trim());
        }
        List<WorldSummaryVO> worlds = registry.list().stream()
                .filter(w -> w.mountable() || w.mounted())
                .toList();
        if (worlds.isEmpty()) {
            throw new IllegalStateException("没有可打开的世界（mountable/mounted 均为空）");
        }
        if (worlds.size() > 1) {
            log.warn("[I2-SMOKE] 有 {} 个可打开世界，默认取第一个；可用 HOLOZYN_I2_SMOKE_WORLD_ID 指定",
                    worlds.size());
        }
        return worlds.get(0).id();
    }

    private static void writeReport(Map<String, Object> report) {
        try {
            Path dir = Path.of("target", "assertion-baseline", "i2");
            Files.createDirectories(dir);
            Path file = dir.resolve("smoke-" + LocalDateTime.now().format(TS) + ".json");
            Files.writeString(file, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
            log.info("[I2-SMOKE] 证据已写入：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.error("[I2-SMOKE] 写证据失败：{}", e.toString());
        }
    }
}
