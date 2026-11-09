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

import com.holzyn.actor.domain.world.authority.model.AuthorityViolationException;
import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import com.holzyn.actor.domain.world.authority.service.WorldAuthorityResolver;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权威写守卫矩阵（§4.11.2）。
 *
 * <table>
 *   <caption>允许矩阵</caption>
 *   <tr><th>模式</th><th>authority</th><th>writer</th><th>结果</th></tr>
 *   <tr><td>BRAIN</td><td>brain</td><td>brain</td><td>允许</td></tr>
 *   <tr><td>BRAIN</td><td>engine</td><td>engine</td><td>拒绝（引擎未连接）</td></tr>
 *   <tr><td>ENGINE</td><td>brain</td><td>brain</td><td>允许（看不见的永远归后端）</td></tr>
 *   <tr><td>ENGINE</td><td>engine</td><td>engine</td><td>允许（只允许镜像）</td></tr>
 * </table>
 */
class AuthorityWriteGuardImplTest {

    private WorldAuthorityResolver resolver;
    private AuthorityWriteGuardImpl guard;

    @BeforeEach
    void setUp() {
        resolver = mock(WorldAuthorityResolver.class);
        guard = new AuthorityWriteGuardImpl(resolver);
    }

    @Test
    @DisplayName("BRAIN 模式：brain 事实只能 brain 写；engine 事实一律拒绝")
    void brainModeAllowsOnlyBrainFacts() {
        when(resolver.mode(1L)).thenReturn(WorldAuthorityMode.BRAIN);
        assertDoesNotThrow(() -> guard.assertCanWrite(1L, FactAuthority.BRAIN, "brain"));
        assertDoesNotThrow(() -> guard.assertCanWrite(1L, FactAuthority.BRAIN, null));
        assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(1L, FactAuthority.ENGINE, "engine"));
        assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(1L, FactAuthority.ENGINE, "brain"));
        assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(1L, FactAuthority.BRAIN, "engine"));
    }

    @Test
    @DisplayName("ENGINE 模式：engine 事实只允许 engine 镜像；brain 事实仍由 brain 写")
    void engineModeAllowsEngineMirrorOnly() {
        when(resolver.mode(1L)).thenReturn(WorldAuthorityMode.ENGINE);
        assertDoesNotThrow(() -> guard.assertCanWrite(1L, FactAuthority.BRAIN, "brain"));
        assertDoesNotThrow(() -> guard.assertCanWrite(1L, FactAuthority.ENGINE, "engine"));
        assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(1L, FactAuthority.ENGINE, "brain"));
        assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(1L, FactAuthority.ENGINE, null));
    }

    @Test
    @DisplayName("异常信息带 AUTHORITY_DENIED 与完整上下文，便于日志定位")
    void violationCarriesContext() {
        when(resolver.mode(7L)).thenReturn(WorldAuthorityMode.BRAIN);
        AuthorityViolationException ex = assertThrows(AuthorityViolationException.class,
                () -> guard.assertCanWrite(7L, FactAuthority.ENGINE, "engine"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("AUTHORITY_DENIED"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("project=7"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("authority=ENGINE"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("writer=engine"));
        org.junit.jupiter.api.Assertions.assertEquals("AUTHORITY_DENIED",
                AuthorityViolationException.CODE);
    }
}
