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

package com.holzyn.actor.domain.equip.model;

import java.util.Locale;

/**
 * 装备槽位闭集。
 *
 * <p>槽位取值由物品定义的 {@code equip_slot} 决定，这里只登记「世界认识哪几个槽」。
 * 加新槽必须同时想清楚「它影响哪条裁决」——只穿不生效的槽位是装饰，不是装备。</p>
 */
public final class EquipmentSlot {

    /** 手持：武器/工具（影响 R-ATTACK-01 与制作设施门控） */
    public static final String HAND = "hand";

    /** 穿戴：衣物/护具（只做「穿得上、看得见」，减伤待后续） */
    public static final String BODY = "body";

    private EquipmentSlot() {
    }

    /**
     * 归一化槽位（小写、去空白）。
     *
     * @param raw 原始槽位
     *
     * @return 归一化槽位；空/非法返回 null
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 16) {
            value = value.substring(0, 16);
        }
        return value;
    }

    /**
     * 是否世界认识的槽位。
     *
     * @param slot 槽位
     *
     * @return true=认识
     */
    public static boolean known(String slot) {
        return HAND.equals(slot) || BODY.equals(slot);
    }
}
