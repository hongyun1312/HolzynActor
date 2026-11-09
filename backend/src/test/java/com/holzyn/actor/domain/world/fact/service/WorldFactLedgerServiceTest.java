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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactLogRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldFactLedgerServiceTest {

    private ActorWorldFactRepository factRepository;
    private ActorWorldFactLogRepository logRepository;
    private WorldFactLedgerService service;

    @BeforeEach
    void setUp() {
        factRepository = mock(ActorWorldFactRepository.class);
        logRepository = mock(ActorWorldFactLogRepository.class);
        service = new WorldFactLedgerService(factRepository, logRepository);
    }

    @Test
    @DisplayName("列表：按状态/类型/关键词过滤，并受 limit 上限约束")
    void listFiltersAndClamps() {
        ActorWorldFact contract = fact(1L, "contract:a", "contract", "码头契约", "三天后还钱");
        ActorWorldFact facility = fact(2L, "facility:inn", "facility", "旅馆", "正常营业");
        ActorWorldFact expired = fact(3L, "contract:b", "contract", "旧契约", "已到期");
        expired.setStatus("expired");
        when(factRepository.findByProjectIdOrderByIdDesc(7L)).thenReturn(List.of(expired, facility, contract));

        List<ActorWorldFact> rows = service.list(7L, "active", "contract", "还钱", 10);

        assertEquals(1, rows.size());
        assertEquals("contract:a", rows.get(0).getFactKey());
    }

    @Test
    @DisplayName("列表：limit 钳到 1..500，空项目返回空")
    void listLimitBounds() {
        when(factRepository.findByProjectIdOrderByIdDesc(7L)).thenReturn(List.of(fact(1L, "a", "prop", "甲", "乙")));

        assertEquals(1, service.list(7L, null, null, null, 0).size());
        assertEquals(0, service.list(null, null, null, null, 10).size());
    }

    @Test
    @DisplayName("版本链：事实归属当前项目时返回日志")
    void versionsReturnsOwnedFactLogs() {
        ActorWorldFact f = fact(11L, "contract:a", "contract", "契约", "生效");
        ActorWorldFactLog log = new ActorWorldFactLog();
        log.setId(5L);
        log.setFactId(11L);
        log.setVersion(2);
        when(factRepository.findById(11L)).thenReturn(Optional.of(f));
        when(logRepository.findByFactIdOrderByVersionDescIdDesc(11L)).thenReturn(List.of(log));

        List<ActorWorldFactLog> rows = service.versions(7L, 11L);

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).getVersion());
    }

    @Test
    @DisplayName("版本链：跨项目事实按 404 处理")
    void versionsRejectsCrossProject() {
        ActorWorldFact other = fact(12L, "contract:x", "contract", "别人家契约", "生效");
        other.setProjectId(99L);
        when(factRepository.findById(12L)).thenReturn(Optional.of(other));

        assertThrows(BizException.class, () -> service.versions(7L, 12L));
    }

    private ActorWorldFact fact(Long id, String key, String type, String name, String state) {
        ActorWorldFact f = new ActorWorldFact();
        f.setId(id);
        f.setProjectId(7L);
        f.setFactKey(key);
        f.setFactType(type);
        f.setName(name);
        f.setStateText(state);
        f.setStatus("active");
        f.setVisibility("public");
        f.setAuthority("brain");
        f.setVersion(1);
        return f;
    }
}
