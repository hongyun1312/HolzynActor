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

package com.holzyn.actor.domain.world.authority.service;

import com.holzyn.actor.domain.world.fact.model.FactAuthority;

/**
 * 权威写守卫（§4.5）。
 *
 * <p>在写入口调用；不满足则抛 {@link com.holzyn.actor.domain.world.authority.model.AuthorityViolationException}
 * （fail-closed，绝不放行后静默修正）。</p>
 */
public interface AuthorityWriteGuard {

    /**
     * 断言本次写入合法。
     *
     * @param projectId 项目/世界 ID
     *
     * @param authority 事实声明的权威
     *
     * @param writer    实际写者（brain / engine）
     *
     * @throws com.holzyn.actor.domain.world.authority.model.AuthorityViolationException 不合法
     */
    void assertCanWrite(Long projectId, FactAuthority authority, String writer);
}
