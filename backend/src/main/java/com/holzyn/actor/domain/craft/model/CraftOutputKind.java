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

package com.holzyn.actor.domain.craft.model;

import java.util.Locale;

/**
 * 产出形态闭集（「制作」与「建造」的分界）。
 *
 * <p>闭集而不是自由字符串：产出落到哪里（背包 / 设施事实 / 物件事实）决定了结算写哪张表、
 * 哪类事实，多一个自由值就多一条没人处理的路径。</p>
 */
public final class CraftOutputKind {

    /** 产物进背包（制作：面包、柴刀） */
    public static final String ITEM = "item";

    /** 产物成为一处设施（建造：铁匠炉），落 {@code facility:loc:{id}} 事实 */
    public static final String FACILITY = "facility";

    /** 产物成为场景物件（建造：木凳、货摊），落 {@code prop:*} 事实 */
    public static final String PROP = "prop";

    private CraftOutputKind() {
    }

    /**
     * 归一化（空值按 item；未知值原样返回，由调用方决定是否拒绝）。
     *
     * @param raw 原始值
     *
     * @return 小写形态
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ITEM;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.length() > 12 ? value.substring(0, 12) : value;
    }
}
