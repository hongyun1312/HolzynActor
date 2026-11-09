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

package com.holzyn.actor.domain.item.model;

import java.util.Locale;

/**
 * 物品域实现形态（§4.10 的 {@code item.provider}）。
 *
 * <p>只做 {@link #DB}（桌面端后端权威）；{@link #ENGINE} 属 E1d 的
 * {@code EngineInventoryAdapter}，本轮未实现——配成 ENGINE 时端口不装配，
 * EffectApplier 按降级为 {@code SKIPPED_NO_INVENTORY}，不会静默写错库。</p>
 */
public enum ItemProvider {

    /** 数据库实现（后端权威） */
    DB,

    /** 引擎实现（引擎权威，E1d；本轮未实现） */
    ENGINE;

    /**
     * 宽松解析（未知/空 → DB，保持向后兼容）。
     *
     * @param raw 配置文本
     *
     * @return 枚举值
     */
    public static ItemProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DB;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DB;
        }
    }
}
