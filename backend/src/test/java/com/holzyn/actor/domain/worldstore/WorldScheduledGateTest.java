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

package com.holzyn.actor.domain.worldstore;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.agent.runtime.EngineScheduler;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallLogRetentionService;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldCommonKnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC12 反向守卫：任何 {@code @Scheduled} 后台任务在世界未挂载时<b>零 DB 写入、零 AI 调用</b>。
 *
 * <p>为什么必须"源码扫描 + 行为断言"两条腿：</p>
 * <ul>
 *   <li><b>源码扫描</b>拦的是"新增了一个定时任务但忘了挂闸门"——这种漏法行为测试永远看不到，
 *       因为没人会去调那个新方法；而它在真实运行里每 30 分钟就往目录库写一次。</li>
 *   <li><b>行为断言</b>拦的是"挂了闸门但挂错了位置"——比如把 {@code worldGate} 调用塞在
 *       {@code findAll()} 之后，源码扫描照样绿，实际已经扫过库了。</li>
 * </ul>
 *
 * <p>{@code UnityWsHub} 进白名单：它只跑 WebSocket 心跳，不碰任何 DB，也没有世界语义。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldScheduledGateTest {

    /** 不碰 DB 的定时任务（只发 WebSocket 心跳） */
    private static final Set<String> WHITELIST = Set.of("UnityWsHub.java");

    /** 受闸门保护的定时任务类数量（新增 {@code @Scheduled} 类必须同步此数，否则这条断言先红） */
    private static final int EXPECTED_GATED_CLASSES = 8;

    /**
     * 启动 Runner 里必须挂闸门的那些（2026-10-25 真实启动实测：不挂就会刷
     * {@code Table "actor_profile" not found} 并白扫一遍目录库）。
     */
    private static final List<String> GATED_RUNNERS = List.of(
            "BrainProfileBackfillRunner.java",
            "MarriageWidowBackfillRunner.java",
            "LocationBackfillRunner.java",
            "WorldGenTaskRecoveryRunner.java",
            "MemoryTierMigrationRunner.java");

    @Test
    @DisplayName("源码扫描：每个含 @Scheduled 的类都必须经 WorldGate（白名单仅 UnityWsHub 心跳）")
    void everyScheduledClassGoesThroughWorldGate() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "源码目录不存在：" + root.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        List<String> gated = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (WHITELIST.contains(name)) {
                    continue;
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                // 注意匹配 "@Scheduled("（注解用法），而不是 javadoc 里的 "{@code @Scheduled}"
                if (!src.contains("@Scheduled(")) {
                    continue;
                }
                gated.add(name);
                boolean callsGate = src.contains("worldGate.")
                        || src.contains("WorldGate.tryMaintenance")
                        || src.contains("worldGate != null");
                // tryMaintenance(...) / tryMaintenanceWithResult(...) / tryGlobalMaintenance(...) 都算挂门
                boolean usesWrapper = src.contains("tryMaintenance(")
                        || src.contains("tryMaintenanceWithResult(")
                        || src.contains("tryGlobalMaintenance(");
                if (!callsGate || !usesWrapper) {
                    violations.add(name + "（" + (callsGate ? "" : "没有 worldGate 调用；")
                            + (usesWrapper ? "" : "没有 tryMaintenance 包装") + "）");
                }
            }
        }
        assertFalse(gated.isEmpty(), "未扫描到任何 @Scheduled 类（扫描逻辑失效，这条测试会假绿）");
        assertTrue(violations.isEmpty(),
                "以下定时任务没有接 WorldGate：世界未挂载时它们会对着目录库执行世界表 SQL（AC12 违规）："
                        + violations);
        assertEquals(EXPECTED_GATED_CLASSES, gated.size(),
                "受闸门保护的定时任务类数量变了（新增 @Scheduled 必须挂闸门并更新本断言）：" + gated);
    }

    @Test
    @DisplayName("源码扫描：启动 Runner 也经 WorldGate（否则启动就扫目录库报 Table not found）")
    void startupRunnersGoThroughWorldGate() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "源码目录不存在：" + root.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (!GATED_RUNNERS.contains(name)) {
                    continue;
                }
                found++;
                String src = Files.readString(p, StandardCharsets.UTF_8);
                boolean callsGate = src.contains("worldGate.")
                        || src.contains("worldGate == null");
                boolean usesWrapper = src.contains("tryMaintenance(");
                if (!callsGate || !usesWrapper) {
                    violations.add(name);
                }
            }
        }
        assertEquals(GATED_RUNNERS.size(), found, "有 Runner 被改名/删除，本断言需同步：" + violations);
        assertTrue(violations.isEmpty(),
                "以下启动 Runner 没有挂 WorldGate（世界未挂载时会扫目录库并刷 Table not found）：" + violations);
    }

    @Test
    @DisplayName("行为：世界未挂载时 EngineScheduler 两个入口都不碰仓库/引擎")
    void engineSchedulerSkipsEverythingWhenNoWorldMounted() {
        WorldEngine engine = mock(WorldEngine.class);
        ActorActionPlanRepository plans = mock(ActorActionPlanRepository.class);
        EngineScheduler scheduler = new EngineScheduler(engine, plans, mock(AgentActionService.class),
                mock(ActorCharacterRepository.class), mock(ActorWorldClockRepository.class));
        ReflectionTestUtils.setField(scheduler, "worldScanEnabled", true);
        ReflectionTestUtils.setField(scheduler, "actionExecuteEnabled", true);
        ReflectionTestUtils.setField(scheduler, "worldGate", gate(false));

        scheduler.worldScan();
        scheduler.runDuePlans();

        verifyNoInteractions(engine, plans);
    }

    @Test
    @DisplayName("行为：世界挂载后 EngineScheduler 才推进/查计划")
    void engineSchedulerRunsWhenWorldMounted() {
        WorldEngine engine = mock(WorldEngine.class);
        ActorActionPlanRepository plans = mock(ActorActionPlanRepository.class);
        when(plans.findByStatusAndPlannedTimeLessThanEqual(any(), any())).thenReturn(List.of());
        EngineScheduler scheduler = new EngineScheduler(engine, plans, mock(AgentActionService.class),
                mock(ActorCharacterRepository.class), mock(ActorWorldClockRepository.class));
        ReflectionTestUtils.setField(scheduler, "worldScanEnabled", true);
        ReflectionTestUtils.setField(scheduler, "actionExecuteEnabled", true);
        ReflectionTestUtils.setField(scheduler, "worldGate", gate(true));

        scheduler.worldScan();
        scheduler.runDuePlans();

        verify(engine).scanAndAdvance();
        verify(plans).findByStatusAndPlannedTimeLessThanEqual(any(), any());
    }

    @Test
    @DisplayName("行为：未挂载时「会调 AI」的维护任务零仓库交互（WorldCommonKnowledgeService 为代表）")
    void aiMaintenanceSkipsWhenNoWorldMounted() {
        ActorWorldSettingRepository settings = mock(ActorWorldSettingRepository.class);
        PromptTemplateService templates = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        WorldCommonKnowledgeService service = new WorldCommonKnowledgeService(settings, templates, gateway);
        ReflectionTestUtils.setField(service, "worldGate", gate(false));

        service.scanAndBackfill();

        verifyNoInteractions(settings, templates, gateway);
    }

    @Test
    @DisplayName("行为：AI 日志保留两趟——未挂载不删世界库行，但目录库的全局行照清")
    void aiLogRetentionTwoPasses() throws Exception {
        ActorAiCallLogRepository repository = mock(ActorAiCallLogRepository.class);

        DriverManagerDataSource registry = new DriverManagerDataSource(
                "jdbc:h2:mem:retention_gate_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = registry.getConnection(); Statement st = c.createStatement()) {
            st.execute("create table actor_ai_call_log (id bigint primary key, project_id bigint, created_at timestamp)");
            // 两条都超期：一条属于世界（必须留着，世界库才是权威）、一条全局（必须清）
            st.execute("insert into actor_ai_call_log values (1, 33, timestamp '2020-01-01 00:00:00')");
            st.execute("insert into actor_ai_call_log values (2, null, timestamp '2020-01-01 00:00:00')");
        }

        AiCallLogRetentionService service = new AiCallLogRetentionService(repository);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "retentionDays", 7);
        ReflectionTestUtils.setField(service, "dataDir", null);
        ReflectionTestUtils.setField(service, "worldGate", gate(false));
        ReflectionTestUtils.setField(service, "registryDataSource", registry);

        service.scheduledMaintenance();

        // 未挂载：世界库那一趟必须连仓库都不碰
        verify(repository, never()).deleteByCreatedAtBefore(any());
        try (Connection c = registry.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("select id from actor_ai_call_log order by id")) {
            assertTrue(rs.next(), "目录库必须还有那行属于世界的记录");
            assertEquals(1L, rs.getLong(1), "被删的只能是 project_id 为空的全局行");
            assertFalse(rs.next(), "全局行（id=2）必须被清掉");
        }
    }

    /** 构造闸门：mounted=false 表示无挂载世界 */
    private static WorldGate gate(boolean mounted) {
        WorldSessionService sessions = mock(WorldSessionService.class);
        when(sessions.isOpen()).thenReturn(mounted);
        return new WorldGate(sessions);
    }
}
