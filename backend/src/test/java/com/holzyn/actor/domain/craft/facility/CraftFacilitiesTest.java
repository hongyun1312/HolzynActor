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

package com.holzyn.actor.domain.craft.facility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设施类型匹配单测（R-CRAFT-01「设施可用」的唯一判定入口）。
 *
 * <p>为什么值得单独测：世界是 AI 生成的中文语境，「铁匠炉」「铁匠铺」「张家锻炉」指的是
 * 同一种设施。若要求逐字相等，NPC 会站在炉子前说「这儿没有炉子」——这是最伤真实感的一类误判。</p>
 */
class CraftFacilitiesTest {

    @Test
    @DisplayName("配方不要求设施 → 恒满足（编草绳在哪儿都能做）")
    void noRequirementAlwaysMatches() {
        assertTrue(CraftFacilities.matches(null, null, null));
        assertTrue(CraftFacilities.matches("  ", "灶台", "厨房"));
    }

    @Test
    @DisplayName("类型逐字相等 → 满足（忽略大小写/空白）")
    void exactTypeMatches() {
        assertTrue(CraftFacilities.matches("铁匠炉", "铁匠炉", "某处"));
        assertTrue(CraftFacilities.matches("forge", " Forge ", null));
    }

    @Test
    @DisplayName("设施叫「铁匠铺」也满足「铁匠炉」的要求（同义词）")
    void aliasInNameMatches() {
        assertTrue(CraftFacilities.matches("铁匠炉", null, "张记铁匠铺"));
        assertTrue(CraftFacilities.matches("铁匠炉", "锻炉", "无名"));
        assertTrue(CraftFacilities.matches("灶台", null, "王家厨房"));
        assertTrue(CraftFacilities.matches("石磨", "磨坊", null));
    }

    @Test
    @DisplayName("类型确实不对 → 不满足（厨房做不了铁匠活）")
    void mismatchFails() {
        assertFalse(CraftFacilities.matches("铁匠炉", "灶台", "王家厨房"));
        assertFalse(CraftFacilities.matches("铁匠炉", null, "村口空地"));
    }

    @Test
    @DisplayName("factTypeOf：兼容 type / facilityType / facility_type / kind")
    void factTypeOfReadsSeveralFields() {
        assertEquals("铁匠炉", CraftFacilities.factTypeOf("{\"type\":\"铁匠炉\"}"));
        assertEquals("灶台", CraftFacilities.factTypeOf("{\"facilityType\":\"灶台\"}"));
        assertEquals("木工台", CraftFacilities.factTypeOf("{\"facility_type\":\"木工台\"}"));
        assertEquals("石磨", CraftFacilities.factTypeOf("{\"kind\":\"石磨\"}"));
        assertNull(CraftFacilities.factTypeOf(null));
        assertNull(CraftFacilities.factTypeOf(""));
        assertNull(CraftFacilities.factTypeOf("not-json"));
        assertNull(CraftFacilities.factTypeOf("{\"open\":true}"));
    }

    @Test
    @DisplayName("aliasesOf：已知类型给同义词表，未知类型只给自身")
    void aliasesOf() {
        assertTrue(CraftFacilities.aliasesOf("铁匠炉").contains("铁匠铺"));
        assertTrue(CraftFacilities.aliasesOf("forge").contains("铁匠炉"), "从英文别名反查同一组");
        assertEquals(1, CraftFacilities.aliasesOf("不存在的设施").size());
    }
}
