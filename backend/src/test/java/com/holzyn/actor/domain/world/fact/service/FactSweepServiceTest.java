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

package com.holzyn.actor.domain.world.fact.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FactSweepServiceTest {

    @Test
    @DisplayName("小时 sweep：先到期扫描，再依次调用腐坏钩子；单个钩子失败不阻断后续")
    void hourlyRunsSweepAndIsolatesHooks() {
        WorldFactService facts = mock(WorldFactService.class);
        FactDecayHook bad = mock(FactDecayHook.class);
        FactDecayHook good = mock(FactDecayHook.class);
        doThrow(new IllegalStateException("boom")).when(bad).onHour(7L, 72000L);
        FactSweepService service = new FactSweepService(facts);
        ReflectionTestUtils.setField(service, "decayHooks", List.of(bad, good));

        service.hourly(7L, 72000L);

        verify(facts).sweepExpired(7L, 72000L);
        verify(bad).onHour(7L, 72000L);
        verify(good).onHour(7L, 72000L);
    }

    @Test
    @DisplayName("projectId 为空时直接返回（不扫库）")
    void nullProjectNoop() {
        WorldFactService facts = mock(WorldFactService.class);
        new FactSweepService(facts).hourly(null, 1L);
        verify(facts, org.mockito.Mockito.never()).sweepExpired(null, 1L);
    }
}
