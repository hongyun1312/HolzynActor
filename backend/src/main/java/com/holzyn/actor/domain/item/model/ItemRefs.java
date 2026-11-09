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

import java.util.List;

/**
 * 持有者 ref 的唯一校验源（持有栈 /市场共用）。
 *
 * <p>命名空间：{@code npc:{profileId}}（含玩家）、{@code loc:{locationId}}（货架/柜台/仓廪）、
 * {@code container:{placementId}}（将来的容器）。不新增 holder_type 列，靠前缀区分。</p>
 *
 * <p><b>为什么单独抽出来</b>：市场要判「卖家是店铺还是个人」，如果自己再写一份
 * 前缀校验，哪天加了 {@code container:} 就会出现「物品能放、市场不认」的分叉——
 * 同一套规则只留一处（对齐 AGENTS §8.1.7 同源原则）。</p>
 */
public final class ItemRefs {

    /** 允许的持有者前缀 */
    public static final List<String> HOLDER_PREFIXES = List.of("npc:", "loc:", "container:");

    private ItemRefs() {
    }

    /**
     * 校验并归一化持有者 ref。
     *
     * @param ref  原始 ref
     * @param side 侧别（from/to/seller/buyer，仅用于报错文案）
     *
     * @return 去空白后的 ref
     *
     * @throws IllegalArgumentException 前缀非法
     */
    public static String requireHolder(String ref, String side) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("物品持有者不能为空（" + side + "）");
        }
        String value = ref.trim();
        for (String prefix : HOLDER_PREFIXES) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法持有者 ref（需 npc:/loc:/container: 前缀）：" + ref);
    }

    /**
     * 是否为地点 ref（货架/柜台）。
     *
     * @param ref 持有者 ref
     *
     * @return true=loc:{id}
     */
    public static boolean isLocation(String ref) {
        return ref != null && ref.trim().startsWith("loc:");
    }

    /**
     * 是否为角色 ref（{@code npc:{id}}）。
     *
     * <p>与 {@link #isLocation} 成对：判断「这个持有者是不是一个人」时不要再自己
     * {@code startsWith}——前缀清单只留这一处（AGENTS §8.1.7 同源）。</p>
     *
     * @param ref 持有者 ref
     *
     * @return true=npc:{id}
     */
    public static boolean isNpc(String ref) {
        return ref != null && ref.trim().startsWith("npc:");
    }

    /**
     * 解析 ref 的数值 id（不校验前缀合法性，只取冒号后的数字）。
     *
     * @param ref 持有者 ref
     *
     * @return id；非法返回 null
     */
    public static Long idOf(String ref) {
        if (ref == null) {
            return null;
        }
        String value = ref.trim();
        int colon = value.indexOf(':');
        if (colon < 0 || colon == value.length() - 1) {
            return null;
        }
        try {
            long id = Long.parseLong(value.substring(colon + 1));
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
