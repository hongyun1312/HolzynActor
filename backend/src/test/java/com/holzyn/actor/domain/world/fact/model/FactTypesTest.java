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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事实类型闭集与缺省可见性（§4.4.2）。
 *
 * <p>闭集是规则裁决确定性的前提：类型多一个自由字符串，就多一条没人处理的裁决路径。</p>
 */
class FactTypesTest {

    @Test
    @DisplayName("10 类闭集：大小写/空白归一化，非法类型直接拒绝")
    void requireNormalizesAndRejects() {
        // 新增 equip（身上/手上带着什么）→ 9 → 10
        assertEquals(10, FactTypes.ALL.size(), "事实类型必须是 10 类闭集（首版 9 类 + I4 的 equip）");
        assertEquals("facility", FactTypes.require(" FACILITY "));
        assertEquals("secret", FactTypes.require("Secret"));
        assertEquals("equip", FactTypes.require("Equip"));
        assertThrows(IllegalArgumentException.class, () -> FactTypes.require("planet"));
        assertThrows(IllegalArgumentException.class, () -> FactTypes.require(null));
        assertThrows(IllegalArgumentException.class, () -> FactTypes.require(" "));
    }

    @Test
    @DisplayName("缺省可见性：契约/秘密 private，货架/物件 location，其余 public")
    void defaultVisibilityByType() {
        assertEquals("private", FactTypes.defaultVisibility("contract"));
        assertEquals("private", FactTypes.defaultVisibility("secret"));
        assertEquals("location", FactTypes.defaultVisibility("stock"));
        assertEquals("location", FactTypes.defaultVisibility("prop"));
        // 装备默认 location（只有到场的人看得见他手上拿着什么）
        assertEquals("location", FactTypes.defaultVisibility("equip"));
        assertEquals("public", FactTypes.defaultVisibility("facility"));
        assertEquals("public", FactTypes.defaultVisibility("route"));
        assertEquals("public", FactTypes.defaultVisibility("land"));
        assertEquals("public", FactTypes.defaultVisibility("reputation"));
        assertEquals("public", FactTypes.defaultVisibility("office"));
    }

    @Test
    @DisplayName("显式可见性：空=按类型缺省；public/location/private 归一化；其余拒绝")
    void requireVisibilityNormalizes() {
        assertEquals("public", FactTypes.requireVisibility(null, "facility"));
        assertEquals("public", FactTypes.requireVisibility("  ", "facility"));
        assertEquals("private", FactTypes.requireVisibility("", "secret"));
        assertEquals("location", FactTypes.requireVisibility("LOCATION", "facility"));
        assertEquals("private", FactTypes.requireVisibility(" Private ", "facility"));
        assertThrows(IllegalArgumentException.class,
                () -> FactTypes.requireVisibility("world", "facility"));
    }
}
