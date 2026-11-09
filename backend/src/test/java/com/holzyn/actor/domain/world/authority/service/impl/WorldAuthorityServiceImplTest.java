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

package com.holzyn.actor.domain.world.authority.service.impl;

import com.holzyn.actor.domain.world.authority.entity.ActorWorldAuthorityState;
import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import com.holzyn.actor.domain.world.authority.repository.ActorWorldAuthorityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权威状态写入口（{@code actor_world_authority_state} 唯一写者）。
 */
class WorldAuthorityServiceImplTest {

    private ActorWorldAuthorityStateRepository repository;
    private WorldAuthorityServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ActorWorldAuthorityStateRepository.class);
        service = new WorldAuthorityServiceImpl(repository);
        when(repository.save(any(ActorWorldAuthorityState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("stateOf：null 项目/无记录返回 null，不抛异常")
    void stateOfIsLenient() {
        assertNull(service.stateOf(null));
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertNull(service.stateOf(1L));
    }

    @Test
    @DisplayName("settle 新建：mode 小写入库、lastGameSecond/updatedAt 落全")
    void settleCreatesState() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        ActorWorldAuthorityState state = service.settle(1L, WorldAuthorityMode.BRAIN, 1234L);

        assertEquals(Long.valueOf(1L), state.getProjectId());
        assertEquals("brain", state.getMode());
        assertEquals(Long.valueOf(1234L), state.getLastGameSecond());
        assertNotNull(state.getUpdatedAt());
    }

    @Test
    @DisplayName("settle 已有：覆盖 mode 与 lastGameSecond，保留其余字段")
    void settleUpdatesExistingState() {
        ActorWorldAuthorityState existing = new ActorWorldAuthorityState();
        existing.setProjectId(1L);
        existing.setMode("brain");
        existing.setLastEngineSeq(88L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        ActorWorldAuthorityState state = service.settle(1L, WorldAuthorityMode.ENGINE, 2000L);

        assertEquals("engine", state.getMode());
        assertEquals(Long.valueOf(2000L), state.getLastGameSecond());
        assertEquals(Long.valueOf(88L), state.getLastEngineSeq(), "settle 不应清掉引擎序号");
    }

    @Test
    @DisplayName("settle 缺 projectId：拒绝（状态表主键不可为空）")
    void settleRejectsNullProject() {
        assertThrows(IllegalArgumentException.class,
                () -> service.settle(null, WorldAuthorityMode.BRAIN, 1L));
    }

    @Test
    @DisplayName("markEngineConnected：新状态默认 mode=brain，记录连接时刻")
    void markEngineConnectedCreatesBrainState() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        LocalDateTime at = LocalDateTime.of(2026, 11, 6, 19, 0);

        service.markEngineConnected(1L, at);

        ArgumentCaptor<ActorWorldAuthorityState> captor = ArgumentCaptor.forClass(ActorWorldAuthorityState.class);
        verify(repository).save(captor.capture());
        assertEquals("brain", captor.getValue().getMode(), "未 settle 前默认 brain");
        assertEquals(at, captor.getValue().getLastEngineConnectedAt());
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    @DisplayName("recordHandover：lastEngineSeq/driftJson/lastHandoverAt 落库")
    void recordHandoverWritesDrift() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        service.recordHandover(1L, 77L, "{\"drift\":0}");

        ArgumentCaptor<ActorWorldAuthorityState> captor = ArgumentCaptor.forClass(ActorWorldAuthorityState.class);
        verify(repository).save(captor.capture());
        assertEquals(Long.valueOf(77L), captor.getValue().getLastEngineSeq());
        assertEquals("{\"drift\":0}", captor.getValue().getDriftJson());
        assertNotNull(captor.getValue().getLastHandoverAt());
        assertEquals("brain", captor.getValue().getMode());
    }

    @Test
    @DisplayName("null 项目的连接/交接记录：静默忽略，不写库")
    void nullProjectIsNoop() {
        service.markEngineConnected(null, LocalDateTime.now());
        service.recordHandover(null, 1L, "{}");
        verify(repository, org.mockito.Mockito.never()).save(any(ActorWorldAuthorityState.class));
    }
}
