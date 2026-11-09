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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.authority.model.AuthorityViolationException;
import com.holzyn.actor.domain.world.authority.service.AuthorityWriteGuard;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactLogRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事实写入口单元测试（§4.5）。
 *
 * <p>覆盖四条不变量：① 新建 version=1、更新 version+1；② 每次变化都写日志且 version 对齐；
 * ③ 幂等键重复命令不落库、不重复写日志；④ 写前过权威守卫（越权被拒）。</p>
 */
class WorldFactServiceImplTest {

    private ActorWorldFactRepository factRepository;
    private ActorWorldFactLogRepository logRepository;
    private AuthorityWriteGuard writeGuard;
    private WorldEventBus worldEventBus;
    private WorldFactServiceImpl service;

    @BeforeEach
    void setUp() {
        factRepository = mock(ActorWorldFactRepository.class);
        logRepository = mock(ActorWorldFactLogRepository.class);
        writeGuard = mock(AuthorityWriteGuard.class);
        worldEventBus = mock(WorldEventBus.class);
        service = new WorldFactServiceImpl(factRepository, logRepository, writeGuard);
        ReflectionTestUtils.setField(service, "worldEventBus", worldEventBus);
        when(factRepository.save(any(ActorWorldFact.class))).thenAnswer(inv -> {
            ActorWorldFact fact = inv.getArgument(0);
            if (fact.getId() == null) {
                fact.setId(100L);
            }
            return fact;
        });
    }

    @Test
    @DisplayName("新建：version 0→1、默认可见性按类型、日志 op=create 且 version 对齐")
    void createFactWritesVersionOneAndLog() {
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.empty());

        FactChange change = service.upsert(cmd("shop:1", "facility", "今日歇业", null, null, null));

        assertEquals("create", change.op());
        assertEquals(0, change.fromVersion());
        assertEquals(1, change.toVersion());
        assertEquals(Long.valueOf(100L), change.fact().getId());
        assertEquals("public", change.fact().getVisibility(), "facility 缺省 public");
        assertEquals("brain", change.fact().getAuthority(), "authority 缺省 brain");
        assertEquals("active", change.fact().getStatus());
        verify(writeGuard).assertCanWrite(1L, FactAuthority.BRAIN, "brain");

        ArgumentCaptor<ActorWorldFactLog> logCaptor = ArgumentCaptor.forClass(ActorWorldFactLog.class);
        verify(logRepository).save(logCaptor.capture());
        ActorWorldFactLog row = logCaptor.getValue();
        assertEquals("create", row.getOp());
        assertEquals(Integer.valueOf(1), row.getVersion());
        assertEquals("brain", row.getWriter());
        assertEquals(Long.valueOf(55L), row.getEventId(), "R14：日志必须带来源事件");
        assertEquals(Long.valueOf(500L), row.getGameSecond());
        assertEquals("npc:3", row.getActorRef());
        assertNull(row.getBeforeJson(), "新建无变更前快照");
        assertNotNull(row.getAfterJson(), "变更后快照必须落库");
        assertTrue(row.getAfterJson().contains("\"version\":1"), "快照版本应与事实版本一致");

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(worldEventBus).publish(eq(null), eq(1L), spec.capture());
        assertEquals("fact", spec.getValue().getKind());
        assertEquals("fact", spec.getValue().getSource());
        assertEquals("global", spec.getValue().getVisibility(), "public 事实事件应为 global");
        assertEquals("fact:100:v1", spec.getValue().getSourceKey());
        assertEquals(Long.valueOf(55L), spec.getValue().getCauseEventId(), "事实事件应挂到来源事件");
    }

    @Test
    @DisplayName("更新：version 3→4、before/after 快照都在、日志 op=update")
    void updateFactBumpsVersionAndKeepsBeforeSnapshot() {
        ActorWorldFact existing = fact(100L, "shop:1", 3);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));

        FactChange change = service.upsert(cmd("shop:1", "facility", "恢复营业", "location", null, null));

        assertEquals("update", change.op());
        assertEquals(3, change.fromVersion());
        assertEquals(4, change.toVersion());
        assertEquals("location", change.fact().getVisibility());

        ArgumentCaptor<ActorWorldFactLog> logCaptor = ArgumentCaptor.forClass(ActorWorldFactLog.class);
        verify(logRepository).save(logCaptor.capture());
        ActorWorldFactLog row = logCaptor.getValue();
        assertEquals("update", row.getOp());
        assertEquals(Integer.valueOf(4), row.getVersion());
        assertNotNull(row.getBeforeJson());
        assertTrue(row.getBeforeJson().contains("\"version\":3"), "变更前快照应保留旧版本号");
        assertTrue(row.getAfterJson().contains("\"version\":4"));
    }

    @Test
    @DisplayName("幂等：同事实同幂等键重复命令 → op=duplicate，不落库不写日志")
    void duplicateCommandIsIdempotent() {
        ActorWorldFact existing = fact(100L, "shop:1", 2);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));
        ActorWorldFactLog applied = new ActorWorldFactLog();
        applied.setFactKey("shop:1");
        when(logRepository.findTopByProjectIdAndIdempotencyKey(1L, "idem-1"))
                .thenReturn(Optional.of(applied));

        FactChange change = service.upsert(cmd("shop:1", "facility", "今日歇业", null, null, "idem-1"));

        assertEquals("duplicate", change.op());
        assertEquals(2, change.fromVersion());
        assertEquals(2, change.toVersion());
        verify(factRepository, never()).save(any(ActorWorldFact.class));
        verify(logRepository, never()).save(any(ActorWorldFactLog.class));
    }

    @Test
    @DisplayName("幂等键被别的事实用过：不能吞掉当前事实的更新")
    void idempotencyKeyScopedToSameFact() {
        ActorWorldFact existing = fact(100L, "shop:1", 2);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));
        ActorWorldFactLog otherFactRow = new ActorWorldFactLog();
        otherFactRow.setFactKey("shop:2");
        when(logRepository.findTopByProjectIdAndIdempotencyKey(1L, "idem-1"))
                .thenReturn(Optional.of(otherFactRow));

        FactChange change = service.upsert(cmd("shop:1", "facility", "恢复营业", null, null, "idem-1"));

        assertEquals("update", change.op(), "幂等键只对同一条事实生效");
        verify(factRepository).save(any(ActorWorldFact.class));
    }

    @Test
    @DisplayName("expire：status=expired、version+1、日志 op=expire 且 delta 带原因")
    void expireMarksFactTerminal() {
        ActorWorldFact existing = fact(100L, "shop:1", 2);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));

        FactChange change = service.expire(cmd("shop:1", "facility", "今日歇业", null, null, null, 900L),
                "火灾烧毁");

        assertEquals("expire", change.op());
        assertEquals(2, change.fromVersion());
        assertEquals(3, change.toVersion());
        assertEquals("expired", change.fact().getStatus());
        assertEquals(Long.valueOf(900L), change.fact().getValidToGameSecond());

        ArgumentCaptor<ActorWorldFactLog> logCaptor = ArgumentCaptor.forClass(ActorWorldFactLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals("expire", logCaptor.getValue().getOp());
        assertTrue(logCaptor.getValue().getDeltaJson().contains("火灾烧毁"), "终止原因必须留痕");
    }

    @Test
    @DisplayName("void：status=void、日志 op=void")
    void voidMarksFactVoid() {
        ActorWorldFact existing = fact(100L, "shop:1", 1);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));

        FactChange change = service.voidFact(cmd("shop:1", "facility", "今日歇业", null, null, null),
                "记载有误");

        assertEquals("void", change.op());
        assertEquals("void", change.fact().getStatus());
        ArgumentCaptor<ActorWorldFactLog> logCaptor = ArgumentCaptor.forClass(ActorWorldFactLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals("void", logCaptor.getValue().getOp());
    }

    @Test
    @DisplayName("校验：缺 projectId/factKey/stateText、非法类型/可见性一律拒绝")
    void rejectsInvalidCommands() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(null));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(new FactCommand(
                null, "facility", "shop:1", "杂货铺", "歇业", null, null, null,
                null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(cmd(" ", "facility", "歇业", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(cmd("shop:1", "facility", " ", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(cmd("shop:1", "planet", "歇业", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(cmd("shop:1", "facility", "歇业", "world", null, null)));
        verify(factRepository, never()).save(any(ActorWorldFact.class));
    }

    @Test
    @DisplayName("权威守卫拒绝时：抛 AUTHORITY_DENIED，事实与日志都不落库")
    void authorityDeniedWritesNothing() {
        ActorWorldFact existing = fact(100L, "shop:1", 1);
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new AuthorityViolationException("AUTHORITY_DENIED"))
                .when(writeGuard).assertCanWrite(1L, FactAuthority.ENGINE, "brain");

        assertThrows(AuthorityViolationException.class,
                () -> service.upsert(cmd("shop:1", "facility", "引擎事实", null, FactAuthority.ENGINE, null)));

        verify(factRepository, never()).save(any(ActorWorldFact.class));
        verify(logRepository, never()).save(any(ActorWorldFactLog.class));
    }

    @Test
    @DisplayName("到期扫描：逐条 expire，每条都写日志（I2）")
    void sweepExpiresAllDueFacts() {
        ActorWorldFact first = fact(100L, "shop:1", 1);
        first.setStatus("active");
        ActorWorldFact second = fact(101L, "shop:2", 2);
        second.setStatus("active");
        when(factRepository.findByProjectIdAndStatusAndValidToGameSecondLessThanEqualOrderByIdAsc(1L, "active", 999L))
                .thenReturn(List.of(first, second));
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:1")).thenReturn(Optional.of(first));
        when(factRepository.findByProjectIdAndFactKey(1L, "shop:2")).thenReturn(Optional.of(second));

        service.sweepExpired(1L, 999L);

        assertEquals("expired", first.getStatus());
        assertEquals("expired", second.getStatus());
        verify(factRepository, times(2)).save(any(ActorWorldFact.class));
        verify(logRepository, times(2)).save(any(ActorWorldFactLog.class));
    }

    // ==================== 辅助 ====================

    private static ActorWorldFact fact(Long id, String factKey, int version) {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(id);
        fact.setProjectId(1L);
        fact.setFactType("facility");
        fact.setFactKey(factKey);
        fact.setName("杂货铺");
        fact.setStateText("营业中");
        fact.setVisibility("public");
        fact.setAuthority("brain");
        fact.setVersion(version);
        fact.setStatus("active");
        return fact;
    }

    private static FactCommand cmd(String factKey, String factType, String stateText,
                                   String visibility, FactAuthority authority, String idempotencyKey) {
        return cmd(factKey, factType, stateText, visibility, authority, idempotencyKey, 500L);
    }

    private static FactCommand cmd(String factKey, String factType, String stateText,
                                   String visibility, FactAuthority authority, String idempotencyKey,
                                   Long gameSecond) {
        return new FactCommand(1L, factType, factKey, "杂货铺", stateText,
                "{\"stock\":3}", 7L, "城东",
                visibility, authority, 100L, 2000L, gameSecond,
                "npc:3", 55L, 3, idempotencyKey);
    }
}
