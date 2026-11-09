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

package com.holzyn.actor.domain.world.authority.model;

/**
 * 世界权威模式（§4.11.1）。
 *
 * <p>{@link #AUTO}：以 {@code UnitySessionRegistry.isConnected(projectId)} 判定——
 * 连上引擎即 ENGINE，否则 BRAIN。桌面端（无引擎）恒为 BRAIN。</p>
 * <p>{@link #BRAIN}：强制后端权威（引擎只读）。</p>
 * <p>{@link #ENGINE}：强制引擎权威；但若引擎未连接，降级 BRAIN 并 WARN（不是失败）。</p>
 */
public enum WorldAuthorityMode {
    AUTO,
    BRAIN,
    ENGINE;

    /**
     * 宽松解析（yml 写 auto/brain/engine，大小写不敏感；非法值回退 AUTO）。
     *
     * @param raw 原始文本（可空）
     *
     * @return 模式（恒非空）
     */
    public static WorldAuthorityMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
