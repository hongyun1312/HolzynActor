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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台任务闸门（V1.1.2-beta.1 M2）。
 *
 * <p>闸门存在的理由不是"少跑几遍"而是"不许在错的地方跑"：世界未挂载时默认路由是目录库，
 * 维护任务若照跑，它扫到的 {@code findAll()} 是目录库的索引/配置表，写回的对象却是
 * "某个世界的数据"——这类错误会安静地把全局行改掉。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldGateTest {

    @AfterEach
    void clear() {
        WorldContext.clear();
    }

    private static WorldGate gateWithMounted(boolean mounted) {
        WorldSessionService sessions = mock(WorldSessionService.class);
        when(sessions.isOpen()).thenReturn(mounted);
        return new WorldGate(sessions);
    }

    @Test
    @DisplayName("未挂载：任务体不执行，返回 false，并累计跳过次数")
    void skipsWhenNotMounted() {
        WorldGate gate = gateWithMounted(false);
        AtomicBoolean ran = new AtomicBoolean(false);

        assertFalse(gate.tryMaintenance("记忆归档", () -> ran.set(true)));
        assertFalse(ran.get(), "未挂载时任务体绝不能执行");
        assertEquals(1L, gate.skipCount("记忆归档"));
    }

    @Test
    @DisplayName("已挂载：任务体执行，返回 true")
    void runsWhenMounted() {
        WorldGate gate = gateWithMounted(true);
        List<String> calls = new ArrayList<>();

        assertTrue(gate.tryMaintenance("记忆归档", () -> calls.add("run")));
        assertEquals(List.of("run"), calls);
        assertEquals(0L, gate.skipCount("记忆归档"));
    }
    @Test
    @DisplayName("带返回值：未挂载返回空 Optional，挂载返回结果")
    void supplierVariant() {
        WorldGate off = gateWithMounted(false);
        Optional<String> skipped = off.tryMaintenanceWithResult("摘要", () -> "done");
        assertTrue(skipped.isEmpty());

        WorldGate on = gateWithMounted(true);
        assertEquals(Optional.of("done"), on.tryMaintenanceWithResult("摘要", () -> "done"));
    }

    @Test
    @DisplayName("全局维护：无论是否挂载都强制走目录库上下文")
    void globalMaintenanceForcesGlobalContext() {
        WorldGate gate = gateWithMounted(true);
        AtomicBoolean sawGlobal = new AtomicBoolean(false);

        assertTrue(gate.tryGlobalMaintenance("AI 日志保留", () -> sawGlobal.set(WorldContext.isGlobal())));
        assertTrue(sawGlobal.get(), "全局维护必须在 GLOBAL 上下文里跑，否则会删到世界库的行");
        assertFalse(WorldContext.isGlobal(), "退出后必须还原（不能把全局标记泄漏给后续代码）");
    }
}
