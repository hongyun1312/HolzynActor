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

package com.holzyn.actor.domain.item.smoke;

import com.holzyn.actor.domain.item.config.ItemDbProviderCondition;
import com.holzyn.actor.domain.item.model.InsufficientItemException;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.service.InventoryService;
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

/**
 * 真库冒烟（仅 {@code i1-smoke} profile 启用）。
 *
 * <p><b>为什么放在主代码而不是测试里</b>：本仓库没有任何 {@code @SpringBootTest}，
 * 而要验的恰恰是「真实 H2 文件库 + 真实 Flyway + 真实世界数据」下的
 * 唯一索引、幂等与堆叠合并。用一个 profile 门控的 Runner 跑真实链路，
 * 既不动测试套件惯例，也能在复用。</p>
 *
 * <p><b>安全边界</b>：只在 {@code HOLOZYN_ACTOR_DATA_DIR} 指向的副本上跑
 * （约定 {@code tmp/i1-smoke/data}），绝不碰真实存档；产物写
 * {@code target/assertion-baseline/i1/smoke-<ts>.json}。</p>
 *
 * <p>启用方式：</p>
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=i1-smoke
 *   （env：HOLOZYN_ACTOR_DATA_DIR=tmp/i1-smoke/data、HOLOZYN_ACTOR_ITEM_ENABLED=true）
 * </pre>
 */
@Slf4j
@Component
@Profile("i1-smoke")
@Order(Ordered.LOWEST_PRECEDENCE)
@Conditional(ItemDbProviderCondition.class)
public class ItemSmokeRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    private final InventoryService inventory;

    /** 生产链路走端口（EffectApplier/裁决读的就是它），冒烟也必须走同一条 */
    private final InventoryPort port;

    private final JdbcTemplate jdbc;

    /**
     * 构造。
     *
     * @param registry  世界索引
     * @param lifecycle 世界生命周期（打开/关闭唯一入口）
     * @param inventory 物品唯一写入口（产出场景用）
     * @param port      物品端口（转移/查询场景用，验证 DbInventoryService 装配）
     * @param jdbc      路由数据源（打开世界后指向世界库）
     */
    public ItemSmokeRunner(WorldRegistryService registry, WorldLifecycleService lifecycle,
                           InventoryService inventory, InventoryPort port, JdbcTemplate jdbc) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.port = port;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long worldId = resolveWorldId();
        log.info("[I1-SMOKE] 开始：worldId={}", worldId);
        WorldLifecycleService.OpenOutcome opened = lifecycle.open(worldId);
        log.info("[I1-SMOKE] 世界已打开：uid={} newlyOpened={} 耗时={}ms",
                opened.worldUid(), opened.newlyOpened(), opened.elapsedMs());
        try {
            Map<String, Object> report = WorldContext.callAsWorld(() -> runScenarios(worldId, opened.worldUid()));
            writeReport(report);
        } finally {
            try {
                WorldLifecycleService.CloseOutcome closed = lifecycle.close(worldId);
                log.info("[I1-SMOKE] 世界已关闭：uid={} 耗时={}ms", closed.worldUid(), closed.elapsedMs());
            } catch (RuntimeException e) {
                log.warn("[I1-SMOKE] 关闭世界失败（不影响证据）：{}", e.toString());
            }
        }
    }

    // ==================== 场景 ====================

    private Map<String, Object> runScenarios(long worldId, String worldUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name from actor_profile where project_id = ? and deleted = 0 "
                        + "order by importance desc, id asc limit 4", worldId);
        if (rows.size() < 2) {
            throw new IllegalStateException("I1 冒烟需要至少 2 个角色，实际 " + rows.size()
                    + "（世界 " + worldUid + "）");
        }
        long actorAId = ((Number) rows.get(0).get("id")).longValue();
        long actorBId = ((Number) rows.get(1).get("id")).longValue();
        String actorA = "npc:" + actorAId;
        String actorB = "npc:" + actorBId;

        List<Map<String, Object>> locations = jdbc.queryForList(
                "select id, name from actor_world_location where project_id = ? order by id asc limit 1", worldId);
        String shelf = locations.isEmpty() ? null
                : "loc:" + ((Number) locations.get(0).get("id")).longValue();

        List<Map<String, Object>> steps = new ArrayList<>();
        long gs = 100_000L;

        // ① 产出 5 袋麦子给 A
        inventory.add(worldId, "grain", 5, actorA, "initial", null, gs, "i1:add:a");
        steps.add(check("产出 5 袋麦子给 A", 5, port.quantityOf(worldId, actorA, "grain")));

        // ② 转移 3 袋 A → B
        port.transfer(worldId, "grain", 3, actorA, actorB, "trade", null, gs, "i1:tr:1");
        steps.add(check("转移 3 袋后 A 剩 2", 2, port.quantityOf(worldId, actorA, "grain")));
        steps.add(check("转移 3 袋后 B 有 3", 3, port.quantityOf(worldId, actorB, "grain")));

        // ③ 同幂等键重放：数量不变、账本只一条
        port.transfer(worldId, "grain", 3, actorA, actorB, "trade", null, gs, "i1:tr:1");
        steps.add(check("重放后 A 仍 2（幂等）", 2, port.quantityOf(worldId, actorA, "grain")));
        steps.add(check("重放后 B 仍 3（幂等）", 3, port.quantityOf(worldId, actorB, "grain")));
        Integer ledgerRows = jdbc.queryForObject(
                "select count(*) from actor_item_ledger where project_id = ? and idempotency_key = ?",
                Integer.class, worldId, "i1:tr:1");
        steps.add(check("同幂等键账本只有 1 条", 1, ledgerRows == null ? -1 : ledgerRows));

        // ④ 数量不足：必须抛异常且数量不变
        boolean rejected = false;
        try {
            port.transfer(worldId, "grain", 10, actorA, actorB, "trade", null, gs, "i1:tr:insufficient");
        } catch (InsufficientItemException e) {
            rejected = true;
        }
        steps.add(check("超量转移被拒绝（无负库存）", true, rejected));
        steps.add(check("拒绝后 A 仍 2", 2, port.quantityOf(worldId, actorA, "grain")));

        // ⑤ 再转移 1 袋：堆叠合并（同一 (holder,item,quality) 只有一行）
        port.transfer(worldId, "grain", 1, actorA, actorB, "trade", null, gs, "i1:tr:2");
        steps.add(check("再转 1 袋后 A 剩 1", 1, port.quantityOf(worldId, actorA, "grain")));
        steps.add(check("再转 1 袋后 B 有 4", 4, port.quantityOf(worldId, actorB, "grain")));
        Integer bRows = jdbc.queryForObject(
                "select count(*) from actor_item_stack where project_id = ? and holder_ref = ? and item_key = 'grain'",
                Integer.class, worldId, actorB);
        steps.add(check("B 的麦子只有 1 个栈行（合并）", 1, bRows == null ? -1 : bRows));

        // ⑥ 未知物品：懒建最小定义
        inventory.add(worldId, "短刀", 1, actorA, "initial", null, gs, "i1:add:knife");
        Integer defRows = jdbc.queryForObject(
                "select count(*) from actor_item_def where project_id = ? and item_key = ?",
                Integer.class, worldId, "短刀");
        steps.add(check("未知物品懒建了 1 条定义", 1, defRows == null ? -1 : defRows));
        String category = jdbc.queryForObject(
                "select category from actor_item_def where project_id = ? and item_key = ?",
                String.class, worldId, "短刀");
        steps.add(check("懒建分类为 material", "material", category));

        // ⑦ 货架持有者 loc:{id}
        if (shelf != null) {
            port.transfer(worldId, "短刀", 1, actorA, shelf, "trade", null, gs, "i1:tr:shelf");
            steps.add(check("刀转到货架 " + shelf, 1, port.quantityOf(worldId, shelf, "短刀")));
            steps.add(check("A 身上刀已清空", 0, port.quantityOf(worldId, actorA, "短刀")));
        }

        // ⑧ 自己转给自己：无操作
        port.transfer(worldId, "grain", 1, actorA, actorA, "trade", null, gs, "i1:tr:self");
        steps.add(check("自转不改变数量", 1, port.quantityOf(worldId, actorA, "grain")));

        // ⑨ 读模型
        List<ItemView> bItems = port.list(worldId, actorB);
        steps.add(check("B 的清单可读且含麦子", true,
                bItems.stream().anyMatch(i -> "grain".equals(i.itemKey()) && i.quantity() == 4)));

        // ⑩ 库级不变量
        Integer negative = jdbc.queryForObject(
                "select count(*) from actor_item_stack where project_id = ? and quantity < 0",
                Integer.class, worldId);
        steps.add(check("全库无负库存", 0, negative == null ? -1 : negative));
        Integer v212 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '212' and success = true",
                Integer.class);
        steps.add(check("V212 迁移已应用", 1, v212 == null ? -1 : v212));

        long passed = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count();
        long failed = steps.size() - passed;
        log.info("[I1-SMOKE] 场景完成：{} 通过 / {} 失败", passed, failed);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ts", LocalDateTime.now().toString());
        report.put("worldId", worldId);
        report.put("worldUid", worldUid);
        report.put("actorA", actorA);
        report.put("actorB", actorB);
        report.put("shelf", shelf);
        report.put("passed", passed);
        report.put("failed", failed);
        report.put("steps", steps);
        return report;
    }

    private static Map<String, Object> check(String name, Object expected, Object actual) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("expected", expected);
        step.put("actual", actual);
        step.put("pass", expected == null ? actual == null : expected.equals(actual));
        if (!Boolean.TRUE.equals(step.get("pass"))) {
            log.error("[I1-SMOKE] 断言失败：{} 期望={} 实际={}", name, expected, actual);
        }
        return step;
    }

    // ==================== 辅助 ====================

    private long resolveWorldId() {
        String forced = System.getenv("HOLOZYN_I1_SMOKE_WORLD_ID");
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
            log.warn("[I1-SMOKE] 有 {} 个可打开世界，默认取第一个；可用 HOLOZYN_I1_SMOKE_WORLD_ID 指定",
                    worlds.size());
        }
        return worlds.get(0).id();
    }

    private static void writeReport(Map<String, Object> report) {
        try {
            Path dir = Path.of("target", "assertion-baseline", "i1");
            Files.createDirectories(dir);
            Path file = dir.resolve("smoke-" + LocalDateTime.now().format(TS) + ".json");
            Files.writeString(file, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
            log.info("[I1-SMOKE] 证据已写入：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.error("[I1-SMOKE] 写证据失败：{}", e.toString());
        }
    }
}
