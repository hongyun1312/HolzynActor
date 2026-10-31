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

package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import com.holzyn.actor.domain.worldstore.WorldSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分档存储（V1.1.2-beta.1 M4）后 {@link UsageService} 的"无世界挂载"形态：
 * 只能读目录库 {@code actor_project} 摘要列，<b>绝不能</b>去查世界表 {@code actor_usage_log}。
 *
 * <p>为什么这条必须单独测：无世界时 {@code actor_usage_log} 在目录库里根本不存在，
 * 一旦走到原路径就是 {@code Table "ACTOR_USAGE_LOG" not found} 的 500——而且是
 * "首页一打开就 500"，比功能缺失严重得多。</p>
 *
 * <p>所属模块：test/domain/usage</p>
 */
class UsageServiceRegistryStatsTest {

    @Test
    @DisplayName("无世界挂载：读目录库摘要列汇总，且完全不碰 actor_usage_log 仓库")
    void registryStatsReadsProjectSummaryOnly() throws Exception {
        DriverManagerDataSource registry = projectTable();
        try (Connection c = registry.getConnection(); Statement st = c.createStatement()) {
            // 两个属于用户 7 的世界 + 一个已删除 + 一个别人的（隔离校验）
            st.execute("insert into actor_project values (1, 7, '青禾村', 0, 1000, 2000, 10)");
            st.execute("insert into actor_project values (33, 7, '浮生小镇', 0, 500, 600, 5)");
            st.execute("insert into actor_project values (2, 7, '已删除世界', 1, 999, 999, 99)");
            st.execute("insert into actor_project values (3, 8, '别人的世界', 0, 777, 777, 77)");
        }

        ActorUsageLogRepository logs = mock(ActorUsageLogRepository.class);
        UsageService service = new UsageService(logs, mock(ActorProjectRepository.class),
                mock(ActorCharacterRepository.class));
        WorldSessionService sessions = mock(WorldSessionService.class);
        when(sessions.isOpen()).thenReturn(false);
        ReflectionTestUtils.setField(service, "worldSessionService", sessions);
        ReflectionTestUtils.setField(service, "registryDataSource", registry);

        Map<String, Object> result = service.stats(7L, null, null, null, null, null);

        assertEquals("registry", result.get("mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(15L, summary.get("count"), "调用次数 = 10 + 5（已删除与别人的世界不计）");
        assertEquals(1500L, summary.get("tokenIn"));
        assertEquals(2600L, summary.get("tokenOut"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byProject = (List<Map<String, Object>>) result.get("byProject");
        assertEquals(2, byProject.size());
        assertEquals("1", byProject.get(0).get("key"), "按调用次数降序：青禾村在前");
        assertEquals("青禾村", byProject.get(0).get("label"));
        assertTrue(((List<?>) result.get("detail")).isEmpty(), "无世界时没有明细可给");
        verifyNoInteractions(logs);
    }

    @Test
    @DisplayName("无世界挂载 + 指定项目：只返回该世界的摘要")
    void registryStatsFiltersByProject() throws Exception {
        DriverManagerDataSource registry = projectTable();
        try (Connection c = registry.getConnection(); Statement st = c.createStatement()) {
            st.execute("insert into actor_project values (1, 7, '青禾村', 0, 1000, 2000, 10)");
            st.execute("insert into actor_project values (33, 7, '浮生小镇', 0, 500, 600, 5)");
        }
        UsageService service = new UsageService(mock(ActorUsageLogRepository.class),
                mock(ActorProjectRepository.class), mock(ActorCharacterRepository.class));
        WorldSessionService sessions = mock(WorldSessionService.class);
        when(sessions.isOpen()).thenReturn(false);
        ReflectionTestUtils.setField(service, "worldSessionService", sessions);
        ReflectionTestUtils.setField(service, "registryDataSource", registry);

        Map<String, Object> result = service.stats(7L, 33L, null, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byProject = (List<Map<String, Object>>) result.get("byProject");
        assertEquals(1, byProject.size());
        assertEquals("浮生小镇", byProject.get(0).get("label"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(600L, summary.get("tokenOut"));
    }

    @Test
    @DisplayName("未装配世界会话（单测/未启用分档）：按有世界处理，保持旧语义")
    void worldMountedDefaultsToTrueWithoutSessionService() {
        UsageService service = new UsageService(mock(ActorUsageLogRepository.class),
                mock(ActorProjectRepository.class), mock(ActorCharacterRepository.class));
        assertTrue(service.worldMounted());
    }

    /** 建目录库最小 {@code actor_project}（含 M4 摘要列） */
    private static DriverManagerDataSource projectTable() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:usage_registry_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("create table actor_project (id bigint primary key, user_id bigint, name varchar(100), "
                    + "deleted integer, token_in_total bigint, token_out_total bigint, ai_call_count bigint)");
        }
        return ds;
    }
}
