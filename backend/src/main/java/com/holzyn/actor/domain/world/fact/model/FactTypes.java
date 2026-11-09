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

package com.holzyn.actor.domain.world.fact.model;

import java.util.Locale;
import java.util.Set;

/**
 * 事实类型闭集与缺省可见性（§4.4.2）。
 *
 * <p>只允许 10 类。闭集是「规则裁决确定性」的前提——裁决器按 type 分派规则，
 * 多一个自由字符串就多一条没人处理的路径。</p>
 */
public final class FactTypes {

    /**
     * 首版 9 类 + {@code equip}。
     *
     * <p>{@code equip} 是新增的第 10 类：一个人「手上握着什么」是世界的状态，
     * 不是他的私有属性——旁人到场应该看得见（默认 location 可见）。不复用 {@code prop}：
     * prop 是「场景里的物件」，而这条事实的主语是「人」，语义与可见性都不同。</p>
     */
    public static final Set<String> ALL = Set.of(
            "facility", "route", "stock", "prop", "land", "contract", "reputation", "office", "secret",
            "equip");

    /** 私有类：只有契约与秘密默认 private。 */
    private static final Set<String> PRIVATE = Set.of("contract", "secret");

    /** 地点类：货架、物件与「身上带着什么」默认 location。 */
    private static final Set<String> LOCATION = Set.of("stock", "prop", "equip");

    private FactTypes() {
    }

    /**
     * 归一化并校验类型（小写；非法抛错）。
     *
     * @param factType 原始类型
     *
     * @return 合法小写类型
     *
     * @throws IllegalArgumentException 类型不在闭集内
     */
    public static String require(String factType) {
        String t = factType == null ? "" : factType.trim().toLowerCase(Locale.ROOT);
        if (!ALL.contains(t)) {
            throw new IllegalArgumentException("非法事实类型：" + factType + "（允许：" + ALL + "）");
        }
        return t;
    }

    /**
     * 按类型给缺省可见性。
     *
     * @param factType 已校验类型
     *
     * @return public / location / private
     */
    public static String defaultVisibility(String factType) {
        if (PRIVATE.contains(factType)) {
            return "private";
        }
        return LOCATION.contains(factType) ? "location" : "public";
    }

    /**
     * 校验可见性（空=按类型缺省）。
     *
     * @param visibility 原始可见性
     *
     * @param factType   已校验类型
     *
     * @return public / location / private
     */
    public static String requireVisibility(String visibility, String factType) {
        if (visibility == null || visibility.isBlank()) {
            return defaultVisibility(factType);
        }
        String v = visibility.trim().toLowerCase(Locale.ROOT);
        if (!"public".equals(v) && !"location".equals(v) && !"private".equals(v)) {
            throw new IllegalArgumentException("非法事实可见性：" + visibility);
        }
        return v;
    }
}
