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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldCausalityServiceTest {

    private ActorEventRepository repository;
    private WorldCausalityService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActorEventRepository.class);
        service = new WorldCausalityService(repository, mock(WorldClockService.class));
    }

    @Test
    @DisplayName("根事件：无祖先，返回直接子事件")
    void rootWithChildren() {
        ActorEvent root = event(10L, null, "根事件");
        ActorEvent child = event(11L, 10L, "子事件");
        when(repository.findByIdAndProjectId(10L, 1L)).thenReturn(Optional.of(root));
        when(repository.findByProjectIdAndCauseEventIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(child));

        Map<String, Object> out = service.causality(1L, 10L, 6, 20);

        assertNotNull(out.get("event"));
        assertTrue(((List<?>) out.get("ancestors")).isEmpty());
        assertEquals(1, ((List<?>) out.get("children")).size());
        assertFalse((Boolean) out.get("truncatedUp"));
        assertFalse((Boolean) out.get("truncatedDown"));
    }

    @Test
    @DisplayName("父链：返回 root-first 祖先，当前事件保留 cause")
    void ancestorChainRootFirst() {
        ActorEvent root = event(1L, null, "根");
        ActorEvent middle = event(2L, 1L, "中间");
        ActorEvent leaf = event(3L, 2L, "叶");
        when(repository.findByIdAndProjectId(3L, 1L)).thenReturn(Optional.of(leaf));
        when(repository.findByIdAndProjectId(2L, 1L)).thenReturn(Optional.of(middle));
        when(repository.findByIdAndProjectId(1L, 1L)).thenReturn(Optional.of(root));
        when(repository.findByProjectIdAndCauseEventIdOrderByIdAsc(1L, 3L)).thenReturn(List.of());

        Map<String, Object> out = service.causality(1L, 3L, 6, 20);
        List<?> ancestors = (List<?>) out.get("ancestors");

        assertEquals(2, ancestors.size());
        assertEquals(1L, ((Map<?, ?>) ancestors.get(0)).get("id"));
        assertEquals(2L, ((Map<?, ?>) ancestors.get(1)).get("id"));
        assertEquals(2L, ((Map<?, ?>) out.get("event")).get("causeEventId"));
    }

    @Test
    @DisplayName("父事件缺失：断链标记 truncatedUp，不伪造父节点")
    void missingParentMarksTruncated() {
        ActorEvent leaf = event(5L, 99L, "断链事件");
        when(repository.findByIdAndProjectId(5L, 1L)).thenReturn(Optional.of(leaf));
        when(repository.findByIdAndProjectId(99L, 1L)).thenReturn(Optional.empty());
        when(repository.findByProjectIdAndCauseEventIdOrderByIdAsc(1L, 5L)).thenReturn(List.of());

        Map<String, Object> out = service.causality(1L, 5L, 6, 20);

        assertTrue((Boolean) out.get("truncatedUp"));
        assertTrue(((List<?>) out.get("ancestors")).isEmpty());
    }

    @Test
    @DisplayName("自环：cycle=true，不无限回溯")
    void cycleIsFlagged() {
        ActorEvent a = event(1L, 2L, "A");
        ActorEvent b = event(2L, 1L, "B");
        when(repository.findByIdAndProjectId(1L, 1L)).thenReturn(Optional.of(a));
        when(repository.findByIdAndProjectId(2L, 1L)).thenReturn(Optional.of(b));
        when(repository.findByProjectIdAndCauseEventIdOrderByIdAsc(eq(1L), anyLong())).thenReturn(List.of());

        Map<String, Object> out = service.causality(1L, 1L, 6, 20);

        assertTrue((Boolean) out.get("cycle"));
    }

    @Test
    @DisplayName("跨项目事件：404")
    void crossProjectIsNotFound() {
        when(repository.findByIdAndProjectId(1L, 2L)).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.causality(2L, 1L, 6, 20));
    }

    @Test
    @DisplayName("子事件超过上限：truncatedDown=true")
    void childrenTruncated() {
        ActorEvent root = event(10L, null, "根");
        when(repository.findByIdAndProjectId(10L, 1L)).thenReturn(Optional.of(root));
        when(repository.findByProjectIdAndCauseEventIdOrderByIdAsc(1L, 10L)).thenReturn(List.of(
                event(11L, 10L, "1"), event(12L, 10L, "2"), event(13L, 10L, "3")));

        Map<String, Object> out = service.causality(1L, 10L, 6, 2);

        assertTrue((Boolean) out.get("truncatedDown"));
        assertEquals(2, ((List<?>) out.get("children")).size());
    }

    private ActorEvent event(Long id, Long causeId, String title) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setProjectId(1L);
        e.setCauseEventId(causeId);
        e.setKind("action");
        e.setSource("action");
        e.setTitle(title);
        e.setContent(title);
        e.setGameHour(10L);
        e.setDepth(causeId == null ? 0 : 1);
        return e;
    }
}
