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

package com.holzyn.actor.domain.item.wear;

import com.holzyn.actor.domain.combat.model.CombatRequest;
import com.holzyn.actor.domain.combat.model.CombatResult;
import com.holzyn.actor.domain.combat.service.CombatService;
import com.holzyn.actor.domain.equip.model.EquipmentView;
import com.holzyn.actor.domain.equip.service.EquipmentService;
import com.holzyn.actor.domain.item.config.ItemDbProviderCondition;
import com.holzyn.actor.domain.item.model.DurabilityChange;
import com.holzyn.actor.domain.item.model.ItemView;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.effect.model.EffectResult;
import com.holzyn.actor.domain.world.effect.model.EffectOps;
import com.holzyn.actor.domain.world.effect.service.EffectApplier;
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
 * 真库冒烟（仅 {@code i4-smoke} profile 启用）。
 *
 * <p><b>验什么</b>：真实 H2 文件库 + 真实 Flyway 下的耐久分层（唯一索引含 durability）、
 * 磨损/断裂/修复、按 {@code decay_hours} 的腐坏推进、装备行与 {@code equip:*} 事实、
 * 一击的「扣血 + 磨损 + 顺手抽刀」全链路、以及效果层的幂等重放——这些都不是 Mockito
 * 能证明的（唯一索引、迁移播种、事实台账、事务边界都要真库）。</p>
 *
 * <p><b>安全边界</b>：只在 {@code HOLOZYN_ACTOR_DATA_DIR} 指向的副本上跑
 * （约定 {@code tmp/i4-smoke/data}），绝不碰真实存档；产物写
 * {@code target/assertion-baseline/i4/smoke-<ts>.json}。</p>
 *
 * <p>启用方式：</p>
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=i4-smoke
 *   （env：HOLOZYN_ACTOR_DATA_DIR=绝对路径\tmp\i4-smoke\data、HOLOZYN_ACTOR_ITEM_ENABLED=true、
 *     HOLOZYN_ACTOR_WEAR_ENABLED=true、HOLOZYN_ACTOR_SERVER_PORT=18085）
 * </pre>
 */
@Slf4j
@Component
@Profile("i4-smoke")
@Order(Ordered.LOWEST_PRECEDENCE)
@Conditional(ItemDbProviderCondition.class)
public class WearSmokeRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 冒烟用的虚拟起点（远离真实时钟，避免与真实存档的时间线混淆）。 */
    private static final long GS = 300_000L;

    /** 一小时多少游戏秒 */
    private static final long HOUR = 3600L;

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    /** 物品唯一写入口（备料/腐坏结算用） */
    private final InventoryService inventory;

    /** 生产链路走端口（效果/裁决读的就是它） */
    private final InventoryPort port;

    /** 装备唯一写入口 */
    private final EquipmentService equipment;

    /** 战斗结算 */
    private final CombatService combat;

    /** 效果统一入口（验 combat.strike 的真实落地与幂等） */
    private final EffectApplier effectApplier;

    private final WearProperties wearProperties;

    private final WorldFactQuery factQuery;

    private final JdbcTemplate jdbc;

    /**
     * 构造。
     *
     * @param registry       世界索引
     * @param lifecycle      世界生命周期（打开/关闭唯一入口）
     * @param inventory      物品唯一写入口
     * @param port           物品端口
     * @param equipment      装备唯一写入口
     * @param combat         战斗结算
     * @param effectApplier  效果统一入口
     * @param wearProperties 损耗配置（断言开关已生效）
     * @param factQuery      事实查询（装备事实断言）
     * @param jdbc           路由数据源（打开世界后指向世界库）
     */
    public WearSmokeRunner(WorldRegistryService registry, WorldLifecycleService lifecycle,
                           InventoryService inventory, InventoryPort port, EquipmentService equipment,
                           CombatService combat, EffectApplier effectApplier,
                           WearProperties wearProperties, WorldFactQuery factQuery, JdbcTemplate jdbc) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.port = port;
        this.equipment = equipment;
        this.combat = combat;
        this.effectApplier = effectApplier;
        this.wearProperties = wearProperties;
        this.factQuery = factQuery;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long worldId = resolveWorldId();
        log.info("[I4-SMOKE] 开始：worldId={}", worldId);
        WorldLifecycleService.OpenOutcome opened = lifecycle.open(worldId);
        log.info("[I4-SMOKE] 世界已打开：uid={} newlyOpened={} 耗时={}ms",
                opened.worldUid(), opened.newlyOpened(), opened.elapsedMs());
        try {
            Map<String, Object> report = WorldContext.callAsWorld(() -> runScenarios(worldId, opened.worldUid()));
            writeReport(report);
        } finally {
            try {
                WorldLifecycleService.CloseOutcome closed = lifecycle.close(worldId);
                log.info("[I4-SMOKE] 世界已关闭：uid={} 耗时={}ms", closed.worldUid(), closed.elapsedMs());
            } catch (RuntimeException e) {
                log.warn("[I4-SMOKE] 关闭世界失败（不影响证据）：{}", e.toString());
            }
        }
    }

    // ==================== 场景 ====================

    private Map<String, Object> runScenarios(long worldId, String worldUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, location from actor_profile where project_id = ? and deleted = 0 "
                        + "order by importance desc, id asc limit 3", worldId);
        if (rows.size() < 2) {
            throw new IllegalStateException("I4 冒烟需要至少 2 个角色，实际 " + rows.size()
                    + "（世界 " + worldUid + "）");
        }
        String smith = "npc:" + ((Number) rows.get(0).get("id")).longValue();
        String other = "npc:" + ((Number) rows.get(1).get("id")).longValue();
        String place = rows.get(0).get("location") == null ? null
                : String.valueOf(rows.get(0).get("location"));

        List<Map<String, Object>> locations = jdbc.queryForList(
                "select id, name from actor_world_location where project_id = ? order by id asc limit 1", worldId);
        String locationName = locations.isEmpty() ? place : String.valueOf(locations.get(0).get("name"));

        List<Map<String, Object>> steps = new ArrayList<>();

        // ① 迁移与目录：V215 建了装备表，并把柴刀/面包升级成真定义
        steps.add(check("V215 迁移已应用", 1,
                count("select count(*) from flyway_schema_history where version = '215' and success = true")));
        steps.add(check("装备表存在", 1,
                count("select count(*) from information_schema.tables "
                        + "where upper(table_schema)='PUBLIC' and lower(table_name)='actor_item_equipment'")));
        steps.add(check("全局定义：柴刀=weapon/hand", "weapon/hand", defTraits(worldId, "柴刀")));
        steps.add(check("全局定义：面包=food/72h", "food/72", defTraits(worldId, "面包")));
        steps.add(check("耐久列已收紧为 not null", "NO",
                columnNullable("ACTOR_ITEM_STACK", "DURABILITY")));

        // ② 备料：铁匠 2 把柴刀（同一状态层），另一个角色 3 个面包
        inventory.add(worldId, "柴刀", 2, smith, "initial", null, GS, "i4:seed:knife");
        inventory.add(worldId, "面包", 3, other, "initial", null, GS, "i4:seed:bread");
        steps.add(check("备料：柴刀 2 把", 2, port.quantityOf(worldId, smith, "柴刀")));
        steps.add(check("备料：面包 3 个", 3, port.quantityOf(worldId, other, "面包")));
        steps.add(check("新刀耐久 100", 100, maxDurability(worldId, smith, "柴刀")));

        // ③ 磨损：只磨一件，层分裂成 100 / 98 两层，总数不变
        DurabilityChange worn = port.wear(worldId, smith, "柴刀", 2, null, GS + 10, "i4:wear:1");
        steps.add(check("磨损前耐久 100", 100, worn.before()));
        steps.add(check("磨损后耐久 98", 98, worn.after()));
        steps.add(check("磨损没有打断", false, worn.broke()));
        steps.add(check("总数仍是 2（只磨一件）", 2, port.quantityOf(worldId, smith, "柴刀")));
        steps.add(check("库里出现 2 个状态层", 2, stackLayers(worldId, smith, "柴刀")));
        steps.add(check("最低耐久 98", 98, minDurability(worldId, smith, "柴刀")));
        steps.add(check("磨损账本记 0 差额", 1,
                count("select count(*) from actor_item_ledger where project_id = ? "
                        + "and idempotency_key = 'i4:wear:1' and qty_delta = 0 and reason = 'wear'", worldId)));

        // ④ 磨损到断：最完好那把（100）被打断，总数 2 → 1
        DurabilityChange broke = port.wear(worldId, smith, "柴刀", 200, null, GS + 20, "i4:wear:2");
        steps.add(check("打断前耐久 100", 100, broke.before()));
        steps.add(check("打断后耐久 0", 0, broke.after()));
        steps.add(check("判定为打断", true, broke.broke()));
        steps.add(check("断掉一把后剩 1", 1, port.quantityOf(worldId, smith, "柴刀")));
        steps.add(check("断裂账本记 -1", 1,
                count("select count(*) from actor_item_ledger where project_id = ? "
                        + "and idempotency_key = 'i4:wear:2' and qty_delta = -1 and reason = 'break'", worldId)));

        // ⑤ 磨损幂等：同一键重放不再扣耐久
        DurabilityChange replay = port.wear(worldId, smith, "柴刀", 2, null, GS + 30, "i4:wear:1");
        steps.add(check("重放被识别", true, replay.replayed()));
        steps.add(check("重放后耐久不变（仍 98）", 98, maxDurability(worldId, smith, "柴刀")));

        // ⑥ 装备：拿刀 → 一行装备 + 一条 equip:* 事实
        EquipmentView equipped = equipment.equip(worldId, smith, "hand", "柴刀", GS + 40);
        steps.add(check("装备槽位 hand", "hand", equipped.slot()));
        steps.add(check("装备成色 98", 98, equipped.condition()));
        steps.add(check("装备行 1 条", 1,
                count("select count(*) from actor_item_equipment where project_id = ? and holder_ref = ?",
                        worldId, smith)));
        Optional<ActorWorldFact> equipFact = factQuery.byKey(worldId, EquipmentService.FACT_KEY_PREFIX + smith);
        steps.add(check("equip 事实已落", true, equipFact.isPresent()));
        steps.add(check("事实里写着手上是柴刀", true, equipFact.map(f -> f.getAttributesJson() != null
                && f.getAttributesJson().contains("\"hand\":\"柴刀\"")).orElse(false)));
        steps.add(check("装备事实类型 equip", "equip",
                equipFact.map(ActorWorldFact::getFactType).orElse("-")));

        // ⑦ 换装：同一槽位只有一行（唯一索引兜住）
        equipment.equip(worldId, smith, "hand", "柴刀", GS + 50);
        steps.add(check("同槽位仍只有 1 行", 1,
                count("select count(*) from actor_item_equipment where project_id = ? and holder_ref = ?",
                        worldId, smith)));

        // ⑧ 修复：耐久 98 → 100（封顶），账本记 repair
        DurabilityChange fixed = port.restore(worldId, smith, "柴刀", 25, null, GS + 60, "i4:fix:1");
        steps.add(check("修复前 98", 98, fixed.before()));
        steps.add(check("修复后 100（封顶）", 100, fixed.after()));
        steps.add(check("修复账本 reason=repair", 1,
                count("select count(*) from actor_item_ledger where project_id = ? "
                        + "and idempotency_key = 'i4:fix:1' and reason = 'repair'", worldId)));

        // ⑨ 腐坏：面包 3 个，36 小时后品质掉一半，72 小时后烂透消失
        InventoryService.DecayReport half = inventory.decayTick(worldId, GS + 36 * HOUR);
        steps.add(check("腐坏 36 小时：有降质层", true, half.movedLayers() >= 1));
        steps.add(check("面包品质掉到 50", 50, qualityOf(worldId, other, "面包")));
        InventoryService.DecayReport gone = inventory.decayTick(worldId, GS + 72 * HOUR);
        steps.add(check("腐坏 72 小时：有烂透层", true, gone.spoiledLayers() >= 1));
        steps.add(check("面包已消失", 0, port.quantityOf(worldId, other, "面包")));
        steps.add(check("腐坏账本 reason=decay", true,
                count("select count(*) from actor_item_ledger where project_id = ? "
                        + "and reason = 'decay' and qty_delta < 0", worldId) >= 1));

        // ⑩ 一击（走效果层真实链路）：先摆位——两人同处才打得到（不依赖存档里恰好同处）
        long otherId = ((Number) rows.get(1).get("id")).longValue();
        String here = locationName == null || locationName.isBlank() ? "冒烟测试地" : locationName;
        String far = "城外（冒烟摆位）";
        jdbc.update("update actor_profile set location = ? where id in (?, ?)", here,
                ((Number) rows.get(0).get("id")).longValue(), otherId);
        int healthBefore = healthOf(worldId, other);

        ObjectNode strike = MAPPER.createObjectNode();
        strike.put("attacker", smith);
        strike.put("target", other);
        EffectContext strikeCtx = new EffectContext(worldId, null, "i4-smoke", smith, null, here, GS + 100);

        // ⑩-1 距离门控：把人挪开 → 打不着，血量不变（R-ATTACK-01 的「距离≤1 格」）
        jdbc.update("update actor_profile set location = ? where id = ?", far, otherId);
        EffectReport tooFar = effectApplier.applyWithReport(
                List.of(new EffectOp(EffectOps.COMBAT_STRIKE, strike, "i4:strike:far")), strikeCtx);
        steps.add(check("效果落地 applied（距离门控）", EffectResult.APPLIED,
                tooFar.results().get(0).status()));
        steps.add(check("不在跟前打不着（血量未变）", healthBefore, healthOf(worldId, other)));

        // ⑩-2 摆回来 → 真打：扣血 + 磨损 + 顺手抽刀
        jdbc.update("update actor_profile set location = ? where id = ?", here, otherId);
        EffectReport report = effectApplier.applyWithReport(
                List.of(new EffectOp(EffectOps.COMBAT_STRIKE, strike, "i4:strike:1")), strikeCtx);
        steps.add(check("效果落地 applied", EffectResult.APPLIED, report.results().get(0).status()));
        int healthAfter = healthOf(worldId, other);
        steps.add(check("目标掉血了", true, healthAfter < healthBefore));
        steps.add(check("掉血量 = 基准 8（满成色）", 8, healthBefore - healthAfter));
        steps.add(check("武器被磨了（100→98）", 98, maxDurability(worldId, smith, "柴刀")));
        // 记忆可能落 L2（actor_memory）也可能先落 L1（actor_memory_working），两边都认
        int memoryRows = count("select count(*) from actor_memory where project_id = ? "
                + "and character_ref = ? and content like '%砍伤%'", worldId, other)
                + count("select count(*) from actor_memory_working where project_id = ? "
                + "and character_ref = ? and content like '%砍伤%'", worldId, other);
        steps.add(check("被打的人记住了", true, memoryRows >= 1));

        // ⑪ 一击幂等：同一幂等键重放，血不再掉
        effectApplier.applyWithReport(
                List.of(new EffectOp(EffectOps.COMBAT_STRIKE, strike, "i4:strike:1")), strikeCtx);
        steps.add(check("重放后血量不变", healthAfter, healthOf(worldId, other)));

        // ⑫ 库级不变量
        steps.add(check("全库无负库存", 0,
                count("select count(*) from actor_item_stack where project_id = ? and quantity < 0", worldId)));
        steps.add(check("耐久都在 0..100", 0,
                count("select count(*) from actor_item_stack where project_id = ? "
                        + "and (durability < 0 or durability > 100)", worldId)));
        steps.add(check("没有悬空装备行（物品还在）", 0,
                count("select count(*) from actor_item_equipment e where e.project_id = ? "
                        + "and not exists (select 1 from actor_item_stack s where s.project_id = e.project_id "
                        + "and s.holder_ref = e.holder_ref and s.item_key = e.item_key and s.quantity > 0)", worldId)));

        long passed = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count();
        long failed = steps.size() - passed;
        log.info("[I4-SMOKE] 场景完成：{} 通过 / {} 失败", passed, failed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ts", LocalDateTime.now().toString());
        result.put("worldId", worldId);
        result.put("worldUid", worldUid);
        result.put("smith", smith);
        result.put("other", other);
        result.put("location", locationName);
        result.put("wearEnabled", wearProperties.isEnabled());
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("steps", steps);
        return result;
    }

    // ==================== 辅助 ====================

    /** 物品定义的两项关键特征（分类/装备槽 或 分类/腐坏小时）。 */
    private String defTraits(long worldId, String itemKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select category, equip_slot, decay_hours from actor_item_def "
                        + "where project_id = 0 and item_key = ?", itemKey);
        if (rows.isEmpty()) {
            return "-";
        }
        Map<String, Object> row = rows.get(0);
        Object decay = row.get("decay_hours");
        return row.get("category") + "/" + (decay != null ? decay : row.get("equip_slot"));
    }

    /** 列的 nullable 标记（YES/NO）。 */
    private String columnNullable(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select is_nullable from information_schema.columns "
                        + "where upper(table_schema)='PUBLIC' and upper(table_name) = ? and upper(column_name) = ?",
                table, column);
        return rows.isEmpty() ? "-" : String.valueOf(rows.get(0).get("is_nullable"));
    }

    private int maxDurability(long worldId, String holder, String itemKey) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(durability), -1) from actor_item_stack "
                        + "where project_id = ? and holder_ref = ? and item_key = ?",
                Integer.class, worldId, holder, itemKey);
        return value == null ? -1 : value;
    }

    private int minDurability(long worldId, String holder, String itemKey) {
        Integer value = jdbc.queryForObject(
                "select coalesce(min(durability), -1) from actor_item_stack "
                        + "where project_id = ? and holder_ref = ? and item_key = ?",
                Integer.class, worldId, holder, itemKey);
        return value == null ? -1 : value;
    }

    private int qualityOf(long worldId, String holder, String itemKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select quality from actor_item_stack where project_id = ? and holder_ref = ? "
                        + "and item_key = ? order by quality asc", worldId, holder, itemKey);
        return rows.isEmpty() ? -1 : ((Number) rows.get(0).get("quality")).intValue();
    }

    private int stackLayers(long worldId, String holder, String itemKey) {
        return count("select count(*) from actor_item_stack where project_id = ? "
                + "and holder_ref = ? and item_key = ?", worldId, holder, itemKey);
    }

    private int healthOf(long worldId, String npcRef) {
        // 状态表的表名是 actor_state（实体 ActorNpcState），不是 actor_npc_state
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select health from actor_state where project_id = ? and npc_ref = ?", worldId, npcRef);
        return rows.isEmpty() || rows.get(0).get("health") == null
                ? -1 : ((Number) rows.get(0).get("health")).intValue();
    }

    /**
     * 计数查询（参数由调用方按占位符顺序给，本方法不自动注入 projectId——
     * 自动注入会让「没有占位符的查询」多出一个参数，H2 直接报 parameterIndex 非法）。
     */
    private Integer count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Map<String, Object> check(String name, Object expected, Object actual) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("expected", expected);
        step.put("actual", actual);
        step.put("pass", expected == null ? actual == null : expected.equals(actual));
        if (!Boolean.TRUE.equals(step.get("pass"))) {
            log.error("[I4-SMOKE] 断言失败：{} 期望={} 实际={}", name, expected, actual);
        }
        return step;
    }

    private long resolveWorldId() {
        String forced = System.getenv("HOLOZYN_I4_SMOKE_WORLD_ID");
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
            log.warn("[I4-SMOKE] 有 {} 个可打开世界，默认取第一个；可用 HOLOZYN_I4_SMOKE_WORLD_ID 指定",
                    worlds.size());
        }
        return worlds.get(0).id();
    }

    private static void writeReport(Map<String, Object> report) {
        try {
            Path dir = Path.of("target", "assertion-baseline", "i4");
            Files.createDirectories(dir);
            Path file = dir.resolve("smoke-" + LocalDateTime.now().format(TS) + ".json");
            Files.writeString(file, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
            log.info("[I4-SMOKE] 证据已写入：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.error("[I4-SMOKE] 写证据失败：{}", e.toString());
        }
    }
}
