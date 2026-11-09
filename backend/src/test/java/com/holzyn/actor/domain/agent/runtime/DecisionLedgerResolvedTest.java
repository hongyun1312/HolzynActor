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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionLog;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 留痕：决策日志的 {@code resolved_json} 必须落到「该角色当天最后一行」上。
 *
 * <p>为什么值得单独测：这一列是三方对账第三腿（AI 注入）与第一条腿（权威位置）之间的
 * 唯一书面连接——AI 说「我要去 f_garden_121」，服务端解析成 (x,z) 并起了一条轨迹，
 * 这条留痕是事后唯一能证明「模型的目标引用真的被解析成这个世界里的某个点」的证据。
 * 落错行等于证据归错人，比没有证据更糟。</p>
 */
class DecisionLedgerResolvedTest {

    private static final Long PROJECT = 1L;

    private ActorDecisionLogRepository logRepository;

    private DecisionLedgerService service;

    @BeforeEach
    void setUp() {
        logRepository = Mockito.mock(ActorDecisionLogRepository.class);
        service = new DecisionLedgerService(logRepository,
                Mockito.mock(ActorWorldEngineTickStatRepository.class),
                Mockito.mock(ActorWorldChronicleRepository.class));
    }

    @Test
    @DisplayName("同角色多行：写到最后一行的 resolved_json，其它角色不受影响")
    void attachesToLastRowOfThatCharacter() {
        long gameSecond = 86400L * 3 + 500;
        ActorDecisionLog early = row("npc:7", 71L);
        ActorDecisionLog late = row("special:7", 72L);
        ActorDecisionLog other = row("npc:8", 73L);
        when(logRepository.findByProjectIdAndGameDayOrderByIdAsc(PROJECT, 3L))
                .thenReturn(List.of(early, late, other));

        service.attachResolved(PROJECT, "npc:7", gameSecond, "{\"x\":1.5,\"z\":2.5}");

        assertNull(early.getResolvedJson(), "同 id 但更早的一行不该被写");
        assertEquals("{\"x\":1.5,\"z\":2.5}", late.getResolvedJson(),
                "前缀不同（special:/npc:）但尾部 id 相同，属于同一角色，最后一行才是被执行的那条");
        assertNull(other.getResolvedJson(), "别的角色的行绝不能被写");
        verify(logRepository).save(late);
    }

    @Test
    @DisplayName("当天没有该角色的行：静默跳过，不抛异常也不写库")
    void missingRowIsSilent() {
        when(logRepository.findByProjectIdAndGameDayOrderByIdAsc(PROJECT, 1L))
                .thenReturn(List.of(row("npc:9", 91L)));

        assertDoesNotThrow(() -> service.attachResolved(PROJECT, "npc:7", 86400L + 60, "{}"));
        verify(logRepository, never()).save(any(ActorDecisionLog.class));
    }

    private static ActorDecisionLog row(String ref, Long id) {
        ActorDecisionLog r = new ActorDecisionLog();
        r.setId(id);
        r.setProjectId(PROJECT);
        r.setCharacterRef(ref);
        r.setGameDay(3L);
        return r;
    }
}
