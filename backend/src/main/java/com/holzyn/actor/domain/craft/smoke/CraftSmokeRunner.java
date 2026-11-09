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

package com.holzyn.actor.domain.craft.smoke;

import com.holzyn.actor.domain.craft.config.CraftProperties;
import com.holzyn.actor.domain.craft.entity.ActorCraftJob;
import com.holzyn.actor.domain.craft.model.CraftJobResult;
import com.holzyn.actor.domain.craft.model.CraftRecipeView;
import com.holzyn.actor.domain.craft.model.CraftRejectedException;
import com.holzyn.actor.domain.craft.model.CraftStartRequest;
import com.holzyn.actor.domain.craft.model.InsufficientMaterialException;
import com.holzyn.actor.domain.craft.service.CraftService;
import com.holzyn.actor.domain.item.config.ItemDbProviderCondition;
import com.holzyn.actor.domain.item.port.InventoryPort;
import com.holzyn.actor.domain.item.service.InventoryService;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.service.FactSweepService;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
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
 * 真库冒烟（仅 {@code i3-smoke} profile 启用）。
 *
 * <p><b>验什么</b>：真实 H2 文件库 + 真实 Flyway 下的配方目录播种、开工扣料、到点产出、
 * 设施占用与释放、建造落事实、幂等重放、离场判废——这些都不是 Mockito 能证明的
 * （唯一索引、迁移播种、事实台账都要真库）。</p>
 *
 * <p><b>安全边界</b>：只在 {@code HOLOZYN_ACTOR_DATA_DIR} 指向的副本上跑
 * （约定 {@code tmp/i3-smoke/data}），绝不碰真实存档；产物写
 * {@code target/assertion-baseline/i3/smoke-<ts>.json}。</p>
 *
 * <p>启用方式：</p>
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=i3-smoke
 *   （env：HOLOZYN_ACTOR_DATA_DIR=tmp/i3-smoke/data、HOLOZYN_ACTOR_ITEM_ENABLED=true、
 *     HOLOZYN_ACTOR_CRAFT_ENABLED=true、HOLOZYN_ACTOR_SERVER_PORT=18084）
 * </pre>
 */
@Slf4j
@Component
@Profile("i3-smoke")
@Order(Ordered.LOWEST_PRECEDENCE)
@Conditional(ItemDbProviderCondition.class)
public class CraftSmokeRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 冒烟用的虚拟起点（远离真实时钟，避免与真实存档的时间线混淆）。 */
    private static final long GS = 200_000L;

    private final WorldRegistryService registry;

    private final WorldLifecycleService lifecycle;

    private final InventoryService inventory;

    /** 生产链路走端口（效果/裁决读的就是它），冒烟也必须走同一条 */
    private final InventoryPort port;

    private final CraftService craft;

    private final CraftProperties craftProperties;

    private final WorldFactService factService;

    private final WorldFactQuery factQuery;

    /** 世界引擎每游戏小时调的 sweep（用它结算建造，顺便证明 FactDecayHook 真的挂上了） */
    private final FactSweepService factSweepService;

    private final JdbcTemplate jdbc;

    /**
     * 构造。
     *
     * @param registry        世界索引
     * @param lifecycle       世界生命周期（打开/关闭唯一入口）
     * @param inventory       物品唯一写入口（备料用）
     * @param port            物品端口（数量断言用）
     * @param craft           制作唯一写入口（开工/结算场景）
     * @param craftProperties 制作配置（断言开关已生效）
     * @param factService     事实唯一写入口（准备设施、断言建造产物）
     * @param factQuery       事实查询
     * @param factSweepService 事实小时 sweep（引擎真实调用路径）
     * @param jdbc            路由数据源（打开世界后指向世界库）
     */
    public CraftSmokeRunner(WorldRegistryService registry, WorldLifecycleService lifecycle,
                            InventoryService inventory, InventoryPort port, CraftService craft,
                            CraftProperties craftProperties, WorldFactService factService,
                            WorldFactQuery factQuery, FactSweepService factSweepService,
                            JdbcTemplate jdbc) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.port = port;
        this.craft = craft;
        this.craftProperties = craftProperties;
        this.factService = factService;
        this.factQuery = factQuery;
        this.factSweepService = factSweepService;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long worldId = resolveWorldId();
        log.info("[I3-SMOKE] 开始：worldId={}", worldId);
        WorldLifecycleService.OpenOutcome opened = lifecycle.open(worldId);
        log.info("[I3-SMOKE] 世界已打开：uid={} newlyOpened={} 耗时={}ms",
                opened.worldUid(), opened.newlyOpened(), opened.elapsedMs());
        try {
            Map<String, Object> report = WorldContext.callAsWorld(() -> runScenarios(worldId, opened.worldUid()));
            writeReport(report);
        } finally {
            try {
                WorldLifecycleService.CloseOutcome closed = lifecycle.close(worldId);
                log.info("[I3-SMOKE] 世界已关闭：uid={} 耗时={}ms", closed.worldUid(), closed.elapsedMs());
            } catch (RuntimeException e) {
                log.warn("[I3-SMOKE] 关闭世界失败（不影响证据）：{}", e.toString());
            }
        }
    }

    // ==================== 场景 ====================

    private Map<String, Object> runScenarios(long worldId, String worldUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, location from actor_profile where project_id = ? and deleted = 0 "
                        + "order by importance desc, id asc limit 3", worldId);
        if (rows.size() < 2) {
            throw new IllegalStateException("I3 冒烟需要至少 2 个角色，实际 " + rows.size()
                    + "（世界 " + worldUid + "）");
        }
        String smith = "npc:" + ((Number) rows.get(0).get("id")).longValue();
        String other = "npc:" + ((Number) rows.get(1).get("id")).longValue();
        String siteName = rows.get(0).get("location") == null ? null
                : String.valueOf(rows.get(0).get("location"));

        List<Map<String, Object>> locations = jdbc.queryForList(
                "select id, name from actor_world_location where project_id = ? order by id asc limit 1", worldId);
        if (locations.isEmpty()) {
            throw new IllegalStateException("I3 冒烟需要至少 1 个地点（世界 " + worldUid + "）");
        }
        long locationId = ((Number) locations.get(0).get("id")).longValue();
        String locationName = String.valueOf(locations.get(0).get("name"));
        String facilityRef = "facility:loc:" + locationId;

        List<Map<String, Object>> steps = new ArrayList<>();

        // ① 配方目录：V214 播种了 8 条全局模板，本项目无覆盖 → 回退全局
        steps.add(check("全局配方目录 8 条", 8,
                count("select count(*) from actor_item_recipe where project_id = 0")));
        CraftRecipeView dagger = craft.recipeOf(worldId, "打柴刀");
        steps.add(check("回退全局模板：打柴刀 可解析", true, dagger != null));
        steps.add(check("打柴刀 产出=柴刀", "柴刀", dagger == null ? "-" : dagger.outputItemKey()));
        steps.add(check("打柴刀 要铁匠炉", "铁匠炉", dagger == null ? "-" : dagger.facilityType()));
        steps.add(check("打柴刀 耗时 60 分钟", 60, dagger == null ? -1 : dagger.durationMinutes()));

        // ② 准备一处铁匠铺事实（世界生成早期可能还没有设施事实）
        upsertFacility(worldId, facilityRef, "铁匠炉", true, locationId, locationName);
        Optional<ActorWorldFact> forge = factQuery.byKey(worldId, facilityRef);
        steps.add(check("铁匠铺事实已就绪", true, forge.isPresent()));

        // ③ 备料：铁匠 3 铁锭 + 3 木头，另一个角色 0
        inventory.add(worldId, "铁锭", 3, smith, "initial", null, GS, "i3:seed:iron");
        inventory.add(worldId, "木头", 3, smith, "initial", null, GS, "i3:seed:wood");
        steps.add(check("备料：铁锭 3", 3, port.quantityOf(worldId, smith, "铁锭")));
        steps.add(check("备料：木头 3", 3, port.quantityOf(worldId, smith, "木头")));

        // ④ 开工：材料在开工时就被扣掉，产物还没出现
        CraftJobResult job = craft.start(new CraftStartRequest(worldId, smith, "打柴刀", 1,
                facilityRef, locationId, siteName, null, GS, "i3:craft:1"));
        steps.add(check("开工返回作业 id", true, job.jobId() != null));
        steps.add(check("开工未重放", false, job.replay()));
        steps.add(check("完工 = 开工 + 60 分钟", GS + 3600L, job.finishGameSecond()));
        steps.add(check("开工即扣料：铁锭 3→2", 2, port.quantityOf(worldId, smith, "铁锭")));
        steps.add(check("开工即扣料：木头 3→2", 2, port.quantityOf(worldId, smith, "木头")));
        steps.add(check("开工后还没有产物（柴刀 0）", 0, port.quantityOf(worldId, smith, "柴刀")));
        steps.add(check("作业状态 in_progress", "in_progress", jobStatus(worldId, job.jobId())));
        steps.add(check("设施被占（occupied=true）", true, facilityOccupied(worldId, facilityRef)));
        steps.add(check("设施记了占用者", smith, facilityOccupant(worldId, facilityRef)));

        // ⑤ 未到点：什么都不结算
        steps.add(check("未到点结算 0 件", 0, craft.finishDue(worldId, GS + 60)));
        steps.add(check("未到点仍无产物", 0, port.quantityOf(worldId, smith, "柴刀")));

        // ⑥ 到点：产物入背包、作业完成、设施释放
        steps.add(check("到点结算 1 件", 1, craft.finishDue(worldId, GS + 3600L)));
        steps.add(check("到点后柴刀 1 把", 1, port.quantityOf(worldId, smith, "柴刀")));
        steps.add(check("作业状态 done", "done", jobStatus(worldId, job.jobId())));
        steps.add(check("设施已释放（occupied=false）", false, facilityOccupied(worldId, facilityRef)));
        steps.add(check("材料没有回来（铁锭仍 2）", 2, port.quantityOf(worldId, smith, "铁锭")));

        // ⑦ 幂等：同一幂等键重放不再扣料、不再开工
        CraftJobResult replay = craft.start(new CraftStartRequest(worldId, smith, "打柴刀", 1,
                facilityRef, locationId, siteName, null, GS, "i3:craft:1"));
        steps.add(check("同幂等键重放标记 replay", true, replay.replay()));
        steps.add(check("重放后铁锭仍 2", 2, port.quantityOf(worldId, smith, "铁锭")));
        steps.add(check("同幂等键作业只有 1 条", 1, count(
                "select count(*) from actor_craft_job where project_id = ? and idempotency_key = ?",
                worldId, "i3:craft:1")));

        // ⑧ 材料不齐：拒绝，且一份料都不扣
        boolean shortMaterial = false;
        try {
            craft.start(new CraftStartRequest(worldId, other, "打柴刀", 1, facilityRef, locationId,
                    siteName, null, GS, "i3:craft:poor"));
        } catch (InsufficientMaterialException e) {
            shortMaterial = true;
        }
        steps.add(check("没材料被拒绝（NO_MATERIAL）", true, shortMaterial));
        steps.add(check("拒绝后铁匠铁锭仍 2", 2, port.quantityOf(worldId, smith, "铁锭")));

        // ⑨ 一人一件：手上还有活时再开一件被拒（BUSY）
        CraftJobResult busyJob = craft.start(new CraftStartRequest(worldId, smith, "打柴刀", 1,
                facilityRef, locationId, siteName, null, GS + 4000L, "i3:craft:2"));
        boolean busy = false;
        try {
            craft.start(new CraftStartRequest(worldId, smith, "打柴刀", 1, facilityRef, locationId,
                    siteName, null, GS + 4100L, "i3:craft:3"));
        } catch (CraftRejectedException e) {
            busy = "BUSY".equals(e.reasonCode());
        }
        steps.add(check("同时开第二件被拒（BUSY）", true, busy));

        // ⑩ 中途离场：人走了活就废，材料不退
        jdbc.update("update actor_profile set location = ? where project_id = ? and id = ?",
                "别处", worldId, ((Number) rows.get(0).get("id")).longValue());
        int settled = craft.finishDue(worldId, GS + 4000L + 3600L);
        steps.add(check("离场后结算 1 件（判废）", 1, settled));
        steps.add(check("离场作业 failed", "failed", jobStatus(worldId, busyJob.jobId())));
        steps.add(check("离场原因 LEFT_SITE", "LEFT_SITE", jobReason(worldId, busyJob.jobId())));
        steps.add(check("离场不产出（柴刀仍 1 把）", 1, port.quantityOf(worldId, smith, "柴刀")));
        // 还原位置（后续建造场景要在原地）
        jdbc.update("update actor_profile set location = ? where project_id = ? and id = ?",
                siteName, worldId, ((Number) rows.get(0).get("id")).longValue());

        // ⑪ 建造：产物不是背包里的东西，而是世界里的一处设施
        //    地点名必须用「他本人所在地」（结算时据此判「人还在不在原地」），
        //    地点 ID 用新工地（设施事实落在这里）——这正是裁决层传给 craft.make 的组合。
        inventory.add(worldId, "石头", 5, smith, "initial", null, GS, "i3:seed:stone");
        inventory.add(worldId, "铁锭", 2, smith, "initial", null, GS, "i3:seed:iron2");
        long forgeSiteId = locationId + 1000L;
        CraftJobResult build = craft.start(new CraftStartRequest(worldId, smith, "建铁匠炉", 1,
                null, forgeSiteId, siteName, null, GS + 9000L, "i3:build:1"));
        steps.add(check("建造开工后石头 5→0", 0, port.quantityOf(worldId, smith, "石头")));
        steps.add(check("建造未到点不产出设施", false,
                factQuery.byKey(worldId, "facility:loc:" + forgeSiteId).isPresent()));
        // 走引擎真实路径：每游戏小时边界 factSweepService.hourly → CraftJobSweepHook → finishDue。
        // 用 hook 结算而不是直接调 craft.finishDue，是为了证明「到点才出活」接的是世界时钟，
        // 而不是某个只有冒烟会调的私有方法。
        factSweepService.hourly(worldId, GS + 9000L + 240 * 60L);
        Optional<ActorWorldFact> built = factQuery.byKey(worldId, "facility:loc:" + forgeSiteId);
        steps.add(check("建造到点落成设施事实", true, built.isPresent()));
        steps.add(check("落成设施类型=铁匠炉", true,
                built.map(f -> f.getAttributesJson() != null && f.getAttributesJson().contains("\"type\":\"铁匠炉\""))
                        .orElse(false)));
        steps.add(check("建造产物不进背包（铁匠炉 0 件）", 0, port.quantityOf(worldId, smith, "铁匠炉")));

        // ⑫ 库级不变量
        steps.add(check("全库无负库存", 0, count(
                "select count(*) from actor_item_stack where project_id = ? and quantity < 0", worldId)));
        steps.add(check("作业表共 3 条（完工/判废/建造）", 3, count(
                "select count(*) from actor_craft_job where project_id = ?", worldId)));
        steps.add(check("没有卡在 in_progress 的作业", 0L, craft.countByStatus(worldId,
                ActorCraftJob.STATUS_IN_PROGRESS)));
        steps.add(check("制作开关已生效", true, craftProperties.isEnabled()));
        steps.add(check("V214 迁移已应用", 1, count(
                "select count(*) from flyway_schema_history where version = '214' and success = true")));

        long passed = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count();
        long failed = steps.size() - passed;
        log.info("[I3-SMOKE] 场景完成：{} 通过 / {} 失败", passed, failed);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ts", LocalDateTime.now().toString());
        report.put("worldId", worldId);
        report.put("worldUid", worldUid);
        report.put("smith", smith);
        report.put("other", other);
        report.put("location", locationName);
        report.put("passed", passed);
        report.put("failed", failed);
        report.put("steps", steps);
        return report;
    }

    // ==================== 辅助 ====================

    private void upsertFacility(long worldId, String factKey, String type, boolean open,
                                long locationId, String locationName) {
        String attrs = "{\"type\":\"" + type + "\",\"open\":" + open + ",\"occupied\":false}";
        FactCommand cmd = new FactCommand(worldId, "facility", factKey, locationName + "的" + type,
                open ? "开着门" : "关着门", attrs, locationId, locationName, "public",
                FactAuthority.BRAIN, GS, null, GS, null, null, 3, "i3:smoke:facility");
        factService.upsert(cmd);
    }

    private boolean facilityOccupied(long worldId, String factKey) {
        return factQuery.byKey(worldId, factKey)
                .map(f -> f.getAttributesJson() != null && f.getAttributesJson().contains("\"occupied\":true"))
                .orElse(false);
    }

    private String facilityOccupant(long worldId, String factKey) {
        Optional<ActorWorldFact> fact = factQuery.byKey(worldId, factKey);
        if (fact.isEmpty() || fact.get().getAttributesJson() == null) {
            return "-";
        }
        try {
            var attrs = MAPPER.readTree(fact.get().getAttributesJson());
            return attrs.path("occupiedBy").asText("-");
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private String jobStatus(long worldId, Long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status from actor_craft_job where project_id = ? and id = ?", worldId, jobId);
        return rows.isEmpty() ? "-" : String.valueOf(rows.get(0).get("status"));
    }

    private String jobReason(long worldId, Long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select reason from actor_craft_job where project_id = ? and id = ?", worldId, jobId);
        return rows.isEmpty() ? "-" : String.valueOf(rows.get(0).get("reason"));
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
            log.error("[I3-SMOKE] 断言失败：{} 期望={} 实际={}", name, expected, actual);
        }
        return step;
    }

    private long resolveWorldId() {
        String forced = System.getenv("HOLOZYN_I3_SMOKE_WORLD_ID");
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
            log.warn("[I3-SMOKE] 有 {} 个可打开世界，默认取第一个；可用 HOLOZYN_I3_SMOKE_WORLD_ID 指定",
                    worlds.size());
        }
        return worlds.get(0).id();
    }

    private static void writeReport(Map<String, Object> report) {
        try {
            Path dir = Path.of("target", "assertion-baseline", "i3");
            Files.createDirectories(dir);
            Path file = dir.resolve("smoke-" + LocalDateTime.now().format(TS) + ".json");
            Files.writeString(file, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
            log.info("[I3-SMOKE] 证据已写入：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.error("[I3-SMOKE] 写证据失败：{}", e.toString());
        }
    }
}
