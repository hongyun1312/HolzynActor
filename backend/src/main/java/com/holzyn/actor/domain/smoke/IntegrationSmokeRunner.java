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

package com.holzyn.actor.domain.smoke;

import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.controller.ItemController;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.provision.ItemProvisionService;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.item.wear.WearProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.adjudicate.model.IntentKind;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.adjudicate.service.DefaultActionAdjudicator;
import com.holzyn.actor.domain.world.dialog.model.DialogClaim;
import com.holzyn.actor.domain.world.dialog.service.DialogDigestService;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.effect.model.EffectResult;
import com.holzyn.actor.domain.world.effect.service.EffectApplier;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.worldstore.WorldContext;
import com.holzyn.actor.domain.worldstore.WorldLifecycleService;
import com.holzyn.actor.domain.worldstore.WorldRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
 * 对接真库冒烟（仅 {@code i4i-smoke} profile 启用）。
 *
 * <p><b>验什么</b>：九条闭环里「只有真库能证明」的那几条——</p>
 * <ol>
 *   <li>开局供给真的落到持有栈（V216 定义 + 幂等键 + 穿衣）；</li>
 *   <li>感知段真的读到随身物品（【随身物品】出现在决策提示里）；</li>
 *   <li>吃饭真的吃掉背包里那件（R-REST-01 → inventory.consume → 数量减少）；</li>
 *   <li>拿货真的改写 {@code stock:*} 事实（旁人读到的余量跟着变）；</li>
 *   <li>身上穿的真的减伤（combat.strike 的 armorAbsorbed）；</li>
 *   <li>对话里的「给你」真的转移物品（GIFT claim → inventory.transfer）；</li>
 *   <li>只读 API 真的能读出背包与装备（ItemController）。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：只在 {@code HOLOZYN_ACTOR_DATA_DIR} 指向的副本上跑（约定
 * {@code tmp/i4i-smoke/data}），绝不碰真实存档；产物写
 * {@code target/assertion-baseline/i4i/smoke-<ts>.json}。</p>
 */
@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(name = "holzyn.actor.i4i-smoke.enabled", havingValue = "true")
public class IntegrationSmokeRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 冒烟用的虚拟起点（远离真实时钟，避免与真实存档时间线混淆）。 */
    private static final long GS = 500_000L;

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    private final InventoryService inventory;

    private final InventoryPort port;

    private final EquipmentService equipment;

    private final EffectApplier effectApplier;

    /** 战斗结算（护具减伤断言要读 CombatResult） */
    private final com.holzyn.actor.domain.combat.service.CombatService combat;

    private final WorldFactQuery factQuery;

    private final ItemProvisionService provisionService;

    private final PerceptionService perceptionService;

    private final DefaultActionAdjudicator adjudicator;

    private final DialogDigestService digestService;

    private final ItemController itemController;

    private final ActorCharacterRepository characterRepository;

    private final ItemProperties itemProperties;

    private final WearProperties wearProperties;

    private final JdbcTemplate jdbc;

    /**
     * 构造（冒烟 Runner 是新类，允许构造器注入）。
     *
     * @param registry            世界索引
     * @param lifecycle           世界生命周期
     * @param inventory           物品唯一写入口
     * @param port                物品端口
     * @param equipment           装备唯一写入口
     * @param effectApplier       效果统一入口
     * @param factQuery           事实查询
     * @param provisionService    开局供给
     * @param perceptionService   感知组装
     * @param adjudicator         规则裁决
     * @param digestService       对话抽取（GIFT 落地）
     * @param itemController      只读 API
     * @param characterRepository 角色仓储
     * @param itemProperties      物品域配置
     * @param wearProperties      损耗域配置
     * @param jdbc                路由数据源
     */
    public IntegrationSmokeRunner(WorldRegistryService registry, WorldLifecycleService lifecycle,
                                  InventoryService inventory, InventoryPort port, EquipmentService equipment,
                                  EffectApplier effectApplier,
                                  com.holzyn.actor.domain.combat.service.CombatService combat,
                                  WorldFactQuery factQuery,
                                  ItemProvisionService provisionService, PerceptionService perceptionService,
                                  DefaultActionAdjudicator adjudicator, DialogDigestService digestService,
                                  ItemController itemController, ActorCharacterRepository characterRepository,
                                  ItemProperties itemProperties, WearProperties wearProperties,
                                  JdbcTemplate jdbc) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.port = port;
        this.equipment = equipment;
        this.effectApplier = effectApplier;
        this.combat = combat;
        this.factQuery = factQuery;
        this.provisionService = provisionService;
        this.perceptionService = perceptionService;
        this.adjudicator = adjudicator;
        this.digestService = digestService;
        this.itemController = itemController;
        this.characterRepository = characterRepository;
        this.itemProperties = itemProperties;
        this.wearProperties = wearProperties;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long worldId = resolveWorldId();
        log.info("[I4I-SMOKE] 开始：worldId={}", worldId);
        WorldLifecycleService.OpenOutcome opened = lifecycle.open(worldId);
        log.info("[I4I-SMOKE] 世界已打开：uid={} newlyOpened={} 耗时={}ms",
                opened.worldUid(), opened.newlyOpened(), opened.elapsedMs());
        try {
            Map<String, Object> report = WorldContext.callAsWorld(() -> runScenarios(worldId, opened.worldUid()));
            writeReport(report);
        } finally {
            try {
                lifecycle.close(worldId);
            } catch (RuntimeException e) {
                log.warn("[I4I-SMOKE] 关闭世界失败（不影响证据）：{}", e.toString());
            }
        }
    }

    // ==================== 场景 ====================

    private Map<String, Object> runScenarios(long worldId, String worldUid) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, location from actor_profile where project_id = ? and deleted = 0 "
                        + "order by importance desc, id asc limit 3", worldId);
        if (rows.size() < 2) {
            throw new IllegalStateException("I4I 冒烟需要至少 2 个角色，实际 " + rows.size());
        }
        String smith = "npc:" + ((Number) rows.get(0).get("id")).longValue();
        String other = "npc:" + ((Number) rows.get(1).get("id")).longValue();
        String smithPlace = rows.get(0).get("location") == null ? null
                : String.valueOf(rows.get(0).get("location"));

        // ① 迁移与定义（V216 把供给需要的键登记成正经定义）
        steps.add(check("V216 迁移已应用", 1,
                count("select count(*) from flyway_schema_history where version = '216' and success = true")));
        steps.add(check("全局定义：coin=currency", "currency",
                defCategory(worldId, "coin")));
        steps.add(check("全局定义：水=drink", "drink", defCategory(worldId, "水")));
        steps.add(check("全局定义：铁锤=weapon", "weapon", defCategory(worldId, "铁锤")));

        // ② 开局供给：打开世界时已经跑过一遍；再显式跑一次验幂等
        int stacksAfterOpen = count("select count(*) from actor_item_stack where project_id = ?", worldId);
        steps.add(check("开世界后已有持有栈（供给生效）", true, stacksAfterOpen > 0));
        ItemProvisionService.ProvisionReport again = provisionService.provision(worldId, GS);
        int stacksAfterAgain = count("select count(*) from actor_item_stack where project_id = ?", worldId);
        steps.add(check("供给幂等：重跑不新增持有栈", stacksAfterAgain, stacksAfterOpen));

        List<ItemView> smithItems = port.list(worldId, smith);
        steps.add(check("铁匠有随身物品", true, !smithItems.isEmpty()));
        steps.add(check("铁匠穿着粗布衣（body 槽）", true,
                equipment.equipmentOf(worldId, smith).stream()
                        .anyMatch(e -> "body".equalsIgnoreCase(e.slot()))));
        int shops = count("select count(distinct holder_ref) from actor_item_stack "
                + "where project_id = ? and holder_ref like 'loc:%'", worldId);
        steps.add(check("至少一个货架有货", true, shops > 0));
        steps.add(check("供给账本 reason=spawn", true,
                count("select count(*) from actor_item_ledger where project_id = ? and reason = 'spawn'", worldId) > 0));

        // ③ 感知：决策提示里真的读到随身物品
        String perception = perceptionService.buildPerceptionText(worldId, smith, GS);
        steps.add(check("感知含【随身物品】段", true, perception != null && perception.contains("【随身物品】")));
        steps.add(check("感知写到「身上：」", true, perception != null && perception.contains("身上：")));

        // ④ 吃饭：R-REST-01 点名背包里的面包 → 数量减 1
        String foodKey = firstFoodOf(smithItems);
        if (foodKey != null) {
            int before = port.quantityOf(worldId, smith, foodKey);
            ActionVerdict verdict = adjudicator.adjudicate(AdjudicationContext.builder()
                    .projectId(worldId).intent(IntentKind.REST).gameSecond(GS)
                    .inventory(port.list(worldId, smith)).inventoryKnown(true)
                    .decision(action("吃随身的" + foodKey + "充饥"))
                    .build());
            steps.add(check("R-REST-01 判成功", Verdict.SUCCESS, verdict.verdict()));
            steps.add(check("R-REST-01 产出消耗效果", true, !verdict.effects().isEmpty()));
            if (!verdict.effects().isEmpty()) {
                EffectReport report = effectApplier.applyWithReport(verdict.effects(),
                        new EffectContext(worldId, null, null, smith, null, null, GS));
                steps.add(check("消耗效果已落地", EffectResult.APPLIED, report.get(0).status()));
                steps.add(check("吃完少一件", before - 1, port.quantityOf(worldId, smith, foodKey)));
                steps.add(check("消耗账本 reason=consume", true,
                        count("select count(*) from actor_item_ledger where project_id = ? "
                                + "and reason = 'consume' and item_key = ?", worldId, foodKey) > 0));
            }
        } else {
            steps.add(check("铁匠身上有吃的（供给应给面包）", true, false));
        }

        // ⑤ 事实：从货架拿一件，stock:* 事实的余量跟着变
        String shelf = jdbc.queryForList("select holder_ref from actor_item_stack "
                + "where project_id = ? and holder_ref like 'loc:%' group by holder_ref "
                + "order by count(*) desc limit 1", String.class, worldId).stream().findFirst().orElse(null);
        if (shelf != null) {
            List<ItemView> shelfItems = port.list(worldId, shelf);
            String key = shelfItems.isEmpty() ? null : shelfItems.get(0).itemKey();
            if (key != null) {
                int beforeQty = port.quantityOf(worldId, shelf, key);
                ObjectNode body = MAPPER.createObjectNode();
                body.put("itemKey", key);
                body.put("qty", 1);
                body.put("from", shelf);
                body.put("to", other);
                body.put("reason", "trade");
                effectApplier.applyWithReport(List.of(new EffectOp("inventory.transfer", body, "i4i:take:1")),
                        new EffectContext(worldId, null, null, other, null, null, GS));
                steps.add(check("货架少一件", beforeQty - 1, port.quantityOf(worldId, shelf, key)));
                Optional<ActorWorldFact> stock = factQuery.byKey(worldId, "stock:" + shelf + ":" + key);
                steps.add(check("stock 事实已更新", true, stock.isPresent()));
                steps.add(check("stock 事实余量 = " + (beforeQty - 1), true,
                        stock.map(f -> f.getStateText() != null
                                && f.getStateText().contains(String.valueOf(beforeQty - 1))).orElse(false)));
            }
        }

        // ⑥ 护具：目标穿着衣服，挨打时挡掉一部分
        if (foodKey != null) {
            // 给打人者一把刀（幂等键独立）
            inventory.add(worldId, "柴刀", 1, smith, "initial", null, GS, "i4i:seed:knife");
            equipment.equip(worldId, smith, "hand", "柴刀", GS);
            List<EquipmentView> targetEq = equipment.equipmentOf(worldId, other);
            boolean armored = targetEq.stream().anyMatch(e -> "body".equalsIgnoreCase(e.slot()));
            steps.add(check("被打的人穿着衣服（供给发的粗布衣）", true, armored));
            // 距离门控：两个人必须在一处才打得着（冒烟踩过这个坑，这里显式摆位）
            if (smithPlace != null) {
                jdbc.update("update actor_profile set location = ? where id = ?", smithPlace, idOf(other));
            }
            int healthBefore = healthOf(worldId, other);
            com.holzyn.actor.domain.combat.model.CombatResult strike = combat.strike(
                    new com.holzyn.actor.domain.combat.model.CombatRequest(
                            worldId, smith, other, "柴刀", GS + 60, 9001L, "i4i:strike:1"));
            steps.add(check("一击打成了", true, strike.landed()));
            steps.add(check("护具挡下了一部分伤害", true, strike.armorAbsorbed() > 0));
            steps.add(check("伤害小于基准 8", true, strike.damage() < 8));
            steps.add(check("血确实掉了", true, healthOf(worldId, other) < healthBefore));
            steps.add(check("被打的人记住了这一下", true, memoryCount(worldId, other) > 0));
        }

        // ⑦ 赠予：对话里的「给你」真的转移
        if (foodKey != null) {
            int before = port.quantityOf(worldId, smith, foodKey);
            if (before > 0) {
                com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest req =
                        com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest.builder()
                                .channel("dialogue").projectId(worldId).userId(1L).sessionId(9L)
                                .sessionKey("smoke:gift").participantRefs(List.of(smith, other))
                                .locationName("冒烟地点").gameSecond(GS).eventId(1L)
                                .lastMessageId(1L).newDialogue(true).build();
                DialogClaim gift = new DialogClaim("GIFT", smith, other, foodKey,
                        foodKey, "把东西递过去", null, null, null, 1);
                int applied = digestService.applyClaims(req, List.of(gift), List.of());
                steps.add(check("GIFT 声明已落地", 1, applied));
                steps.add(check("赠予后送礼者少一件", before - 1, port.quantityOf(worldId, smith, foodKey)));
                steps.add(check("赠予后收礼者多一件", true, port.quantityOf(worldId, other, foodKey) > 0));
                steps.add(check("赠予账本 reason=gift", true,
                        count("select count(*) from actor_item_ledger where project_id = ? and reason = 'gift'",
                                worldId) > 0));
            }
        }

        // ⑧ 只读 API：面板能读出背包与装备
        List<Map<String, Object>> holders = itemController.holders(worldId).getData();
        steps.add(check("API holders 非空", true, holders != null && !holders.isEmpty()));
        Map<String, Object> detail = itemController.holder(worldId, smith).getData();
        steps.add(check("API 背包条目非空", true, detail != null
                && detail.get("items") instanceof List<?> list && !list.isEmpty()));
        steps.add(check("API 装备字段存在", true, detail != null && detail.containsKey("equipment")));
        steps.add(check("API 流水可读", true, itemController.ledger(worldId, smith, 20).getData() != null));

        // ⑨ 开关与收口
        steps.add(check("物品域开关为真", true, itemProperties.isEnabled()));
        steps.add(check("损耗域开关为真", true, wearProperties.isEnabled()));
        steps.add(check("没有负库存", 0,
                count("select count(*) from actor_item_stack where project_id = ? and quantity < 0", worldId)));
        steps.add(check("没有悬空装备行", 0,
                count("select count(*) from actor_item_equipment e where e.project_id = ? and not exists ("
                        + "select 1 from actor_item_stack s where s.project_id = e.project_id "
                        + "and s.holder_ref = e.holder_ref and s.item_key = e.item_key)", worldId)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("worldId", worldId);
        report.put("worldUid", worldUid);
        report.put("gameSecond", GS);
        report.put("steps", steps);
        long failed = steps.stream().filter(s -> !Boolean.TRUE.equals(s.get("ok"))).count();
        report.put("passed", steps.size() - failed);
        report.put("failed", failed);
        report.put("generatedAt", LocalDateTime.now().toString());
        log.info("[I4I-SMOKE] 完成：passed={} failed={}", steps.size() - failed, failed);
        return report;
    }

    // ==================== 工具 ====================

    private long resolveWorldId() {
        String forced = System.getenv("HOLOZYN_I4I_SMOKE_WORLD_ID");
        if (forced != null && !forced.isBlank()) {
            return Long.parseLong(forced.trim());
        }
        List<com.holzyn.actor.domain.worldstore.WorldSummaryVO> worlds = registry.list().stream()
                .filter(w -> w.mountable() || w.mounted())
                .toList();
        if (worlds.isEmpty()) {
            throw new IllegalStateException("没有可打开的世界（mountable/mounted 均为空）");
        }
        if (worlds.size() > 1) {
            log.warn("[I4I-SMOKE] 有 {} 个可打开世界，默认取第一个；可用 HOLOZYN_I4I_SMOKE_WORLD_ID 指定",
                    worlds.size());
        }
        return worlds.get(0).id();
    }

    private static String firstFoodOf(List<ItemView> items) {
        for (ItemView item : items) {
            if (item != null && item.foodLike() && item.quantity() > 0) {
                return item.itemKey();
            }
        }
        return null;
    }

    private Long idOf(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? null : Long.valueOf(ref.substring(colon + 1));
    }

    private tools.jackson.databind.JsonNode action(String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("action", text);
        return node;
    }

    private String defCategory(long worldId, String itemKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select category from actor_item_def where project_id = 0 and item_key = ?", itemKey);
        return rows.isEmpty() || rows.get(0).get("category") == null
                ? null : String.valueOf(rows.get(0).get("category"));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    /** 目标当前健康（无状态行按 100）。 */
    private int healthOf(long worldId, String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select health from actor_state where project_id = ? and npc_ref = ?", worldId, ref);
        if (rows.isEmpty() || rows.get(0).get("health") == null) {
            return 100;
        }
        return ((Number) rows.get(0).get("health")).intValue();
    }

    /** 某角色记住了多少条（L1 工作记忆 + 长期记忆都算）。 */
    private int memoryCount(long worldId, String ref) {
        return count("select (select count(*) from actor_memory where project_id = ? and character_ref = ?) "
                + "+ (select count(*) from actor_memory_working where project_id = ? and character_ref = ?)",
                worldId, ref, worldId, ref);
    }

    private static Map<String, Object> check(String name, Object expected, Object actual) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("expected", expected);
        step.put("actual", actual);
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        step.put("ok", ok);
        if (!ok) {
            log.warn("[I4I-SMOKE] 断言失败：{} 期望={} 实际={}", name, expected, actual);
        }
        return step;
    }

    private void writeReport(Map<String, Object> report) {
        try {
            Path dir = Path.of("target", "assertion-baseline", "i4i");
            Files.createDirectories(dir);
            Path file = dir.resolve("smoke-" + LocalDateTime.now().format(TS) + ".json");
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            log.info("[I4I-SMOKE] 报告：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[I4I-SMOKE] 报告写入失败：{}", e.toString());
        }
    }
}
