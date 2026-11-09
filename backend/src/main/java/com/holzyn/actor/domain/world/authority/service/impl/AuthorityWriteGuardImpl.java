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
import com.holzyn.actor.domain.world.authority.service.AuthorityWriteGuard;
import com.holzyn.actor.domain.world.authority.service.WorldAuthorityResolver;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 权威写守卫实现（fail-closed）。
 *
 * <table>
 *   <caption>允许矩阵</caption>
 *   <tr><th>模式</th><th>authority</th><th>允许的 writer</th></tr>
 *   <tr><td>BRAIN</td><td>brain</td><td>brain</td></tr>
 *   <tr><td>BRAIN</td><td>engine</td><td>（无：引擎未连接，不允许写）</td></tr>
 *   <tr><td>ENGINE</td><td>brain</td><td>brain（看不见的永远归后端）</td></tr>
 *   <tr><td>ENGINE</td><td>engine</td><td>engine（只允许镜像）</td></tr>
 * </table>
 */
@Slf4j
@Service
public class AuthorityWriteGuardImpl implements AuthorityWriteGuard {

    private final WorldAuthorityResolver resolver;

    public AuthorityWriteGuardImpl(WorldAuthorityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void assertCanWrite(Long projectId, FactAuthority authority, String writer) {
        FactAuthority target = authority == null ? FactAuthority.BRAIN : authority;
        String actualWriter = writer == null || writer.isBlank() ? "brain" : writer.trim().toLowerCase();
        WorldAuthorityMode mode = resolver.mode(projectId);

        boolean allowed = target == FactAuthority.ENGINE
                ? mode == WorldAuthorityMode.ENGINE && "engine".equals(actualWriter)
                : "brain".equals(actualWriter);

        if (!allowed) {
            log.warn("[权威] 拒绝越权写入：project={} mode={} authority={} writer={}",
                    projectId, mode, target, actualWriter);
            throw new AuthorityViolationException("AUTHORITY_DENIED: project=" + projectId
                    + " mode=" + mode + " authority=" + target + " writer=" + actualWriter);
        }
    }
}
