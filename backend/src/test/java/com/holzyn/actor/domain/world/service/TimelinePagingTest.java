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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.npc.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线分页断言（首屏 100 条 / 滚到底续加载的契约）。
 *
 * <p>钉住三件容易写错的事：①默认与上限条数（25 条这种「一批太少」的取值不能被悄悄写回来）；
 * ②{@code total} 是过滤后总条数而不是本批条数（前端「已显示 X / 共 Y」靠它）；
 * ③{@code hasMore} 在最后一页必须为 false（否则前端会无限空转请求）。</p>
 */
class TimelinePagingTest {

    private static final Long PROJECT_ID = 9L;

    private ActorEventRepository eventRepository;
    private ActorProjectRepository projectRepository;
    private TimelineService service;

    @BeforeEach
    void setUp() {
        eventRepository = Mockito.mock(ActorEventRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        Mockito.when(projectRepository.findByIdAndUserIdAndDeleted(
                        Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new ActorProject()));
        service = new TimelineService(
                eventRepository,
                projectRepository,
                Mockito.mock(ActorCharacterRepository.class),
                Mockito.mock(ActorConversationRepository.class),
                Mockito.mock(ActorMessageRepository.class),
                Mockito.mock(ActorActionPlanRepository.class),
                Mockito.mock(ActorActionLogRepository.class),
                Mockito.mock(ActorMemoryRepository.class),
                Mockito.mock(ActorWorldClockRepository.class),
                Mockito.mock(ActorWorldSettingRepository.class),
                Mockito.mock(PromptService.class),
                Mockito.mock(AiSpringChatGateway.class),
                Mockito.mock(UsageLogService.class),
                Mockito.mock(CurrentUserProvider.class),
                Mockito.mock(WorldEventBus.class),
                new ObjectMapper());
    }

    /** 造 5 条事件（id 越大越新，时间也越新） */
    private void stubEvents(int count) {
        List<ActorEvent> events = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 8, 0);
        for (int i = 1; i <= count; i++) {
            ActorEvent e = new ActorEvent();
            e.setId((long) i);
            e.setProjectId(PROJECT_ID);
            e.setKind("event");
            e.setTitle("事件" + i);
            e.setContent("内容" + i);
            e.setSource("manual");
            e.setCreatedAt(base.plusMinutes(i));
            events.add(e);
        }
        // 仓库按 id 倒序返回（与 ActorEventRepository.findByProjectIdOrderByIdDesc 一致）
        List<ActorEvent> desc = new ArrayList<>(events);
        desc.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        Mockito.when(eventRepository.findByProjectIdOrderByIdDesc(PROJECT_ID)).thenReturn(desc);
    }

    @Test
    @DisplayName("第一批：只返回 limit 条，total 是总条数，hasMore=true")
    void firstPage() {
        stubEvents(5);
        Map<String, Object> page = service.aggregatePage(PROJECT_ID, null, null, null, null, 2, 0);

        assertEquals(5, page.get("total"));
        assertEquals(2, page.get("limit"));
        assertEquals(0, page.get("offset"));
        assertEquals(Boolean.TRUE, page.get("hasMore"));
        List<?> nodes = (List<?>) page.get("nodes");
        assertNotNull(nodes);
        assertEquals(2, nodes.size());
        // 新→旧：第一条必须是 id=5 的「事件5」
        assertEquals("事件5", ((Map<?, ?>) nodes.get(0)).get("title"));
    }

    @Test
    @DisplayName("最后一页：条数不足一批，hasMore=false")
    void lastPage() {
        stubEvents(5);
        Map<String, Object> page = service.aggregatePage(PROJECT_ID, null, null, null, null, 2, 4);

        assertEquals(5, page.get("total"));
        assertEquals(4, page.get("offset"));
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        assertEquals(1, ((List<?>) page.get("nodes")).size());
    }

    @Test
    @DisplayName("offset 越界：返回空批且 hasMore=false（不抛异常、不重复给最后一条）")
    void offsetBeyondTotal() {
        stubEvents(3);
        Map<String, Object> page = service.aggregatePage(PROJECT_ID, null, null, null, null, 100, 999);

        assertEquals(3, page.get("offset"));
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        assertTrue(((List<?>) page.get("nodes")).isEmpty());
    }

    @Test
    @DisplayName("空数据：total=0、nodes 空、hasMore=false")
    void emptyTimeline() {
        stubEvents(0);
        Map<String, Object> page = service.aggregatePage(PROJECT_ID, null, null, null, null, null, null);

        assertEquals(0, page.get("total"));
        assertEquals(100, page.get("limit"));
        assertFalse((Boolean) page.get("hasMore"));
        assertTrue(((List<?>) page.get("nodes")).isEmpty());
    }

    @Test
    @DisplayName("分页条数归一化：空/≤0 → 100，超上限 → 500")
    void clampLimit() {
        assertEquals(100, TimelineService.clampLimit(null));
        assertEquals(100, TimelineService.clampLimit(0));
        assertEquals(100, TimelineService.clampLimit(-7));
        assertEquals(100, TimelineService.clampLimit(100));
        assertEquals(300, TimelineService.clampLimit(300));
        assertEquals(500, TimelineService.clampLimit(1000));
        assertEquals(500, TimelineService.clampLimit(Integer.MAX_VALUE));
    }
}
