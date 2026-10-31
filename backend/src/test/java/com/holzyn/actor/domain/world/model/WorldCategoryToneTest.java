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

package com.holzyn.actor.domain.world.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界类别（固定八类）与世界程度（四档）的判定口径测试。
 *
 * <p>这两个枚举是"世界设定边界"的唯一权威：类别决定生成口径，程度决定宏观层要不要跑、
 * 提示词要不要加边界段。它们被前端下拉、文件解析、导入、迁移脚本共同依赖，
 * 所以错一个关键字就会让世界跑到另一套设定上——用测试把口径钉死。</p>
 */
@DisplayName("世界类别与世界程度：固定枚举口径")
class WorldCategoryToneTest {

    @Test
    @DisplayName("类别：八个固定标签本身原样返回（幂等）")
    void labelsAreIdempotent() {
        for (String label : WorldCategory.labels()) {
            assertEquals(label, WorldCategory.normalize(label), "固定标签必须原样返回：" + label);
        }
        assertEquals(8, WorldCategory.labels().size(), "固定八类，不能增减");
    }

    @Test
    @DisplayName("类别：历史自由文本按关键字归一（仙侠/末世优先于宽泛类别）")
    void normalizesLegacyFreeText() {
        assertEquals("仙侠", WorldCategory.normalize("东方修仙世界"));
        assertEquals("末世", WorldCategory.normalize("废土幸存者据点"));
        assertEquals("科幻", WorldCategory.normalize("赛博朋克近未来"));
        // "海岛小镇生活模拟" 既含岛屿又含小镇：必须落在「日常」而不是「都市」
        assertEquals("日常", WorldCategory.normalize("治愈系海岛小镇生活模拟"));
        assertEquals("悬疑", WorldCategory.normalize("密室推理"));
    }

    @Test
    @DisplayName("类别：映射不到时返回空串（不猜），require 则抛 IllegalArgumentException")
    void unmappableStaysEmpty() {
        assertEquals("", WorldCategory.normalize("某种说不清的东西"), "不认识就不能硬塞一个默认类别");
        assertEquals("", WorldCategory.normalize(null));
        assertEquals("", WorldCategory.normalize("  "));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorldCategory.require("某种说不清的东西"));
        assertTrue(e.getMessage().contains("八类"), "错误信息要告诉用户只能从八类里选");
    }

    @Test
    @DisplayName("程度：空值/未知一律回落到标准档（历史世界零回归）")
    void toneDefaultsToStandard() {
        assertEquals(WorldTone.STANDARD, WorldTone.ofCode(null));
        assertEquals(WorldTone.STANDARD, WorldTone.ofCode(""));
        assertEquals(WorldTone.STANDARD, WorldTone.ofCode("不存在的档位"));
        assertEquals(WorldTone.CASUAL, WorldTone.ofCode("休闲"));
        assertEquals(WorldTone.HARD, WorldTone.ofCode("hard"));
        assertTrue(WorldTone.find(null).isEmpty(), "find 用于判断是否显式设置过");
    }

    @Test
    @DisplayName("程度：只有标准/硬核允许调度宏观与势力链")
    void macroGateFollowsTone() {
        assertTrue(WorldTone.CASUAL.macroDisabled(), "休闲档整个宏观层不调度");
        assertTrue(WorldTone.CASUAL.factionDisabled());
        assertTrue(WorldTone.CASUAL.skipsMacroTemplate(), "休闲档初始化不生成宏观模板（标已跳过）");
        assertFalse(WorldTone.LIGHT.macroDisabled(), "轻度档保留文化/人口这类轻引擎，宏观层不全关");
        assertTrue(WorldTone.LIGHT.factionDisabled(), "轻度档必须跳过势力/战争链");
        assertTrue(WorldTone.LIGHT.skipsMacroTemplate(), "轻度档初始化同样不生成宏观模板");
        assertFalse(WorldTone.STANDARD.macroDisabled(), "标准档 = 改造前行为，必须照常调度");
        assertFalse(WorldTone.STANDARD.factionDisabled());
        assertFalse(WorldTone.STANDARD.skipsMacroTemplate());
        assertFalse(WorldTone.HARD.macroDisabled());
    }

    @Test
    @DisplayName("程度：标准档的边界段不出现否定式约束（提示词零回归）")
    void standardBoundaryIsNeutral() {
        assertTrue(WorldTone.STANDARD.boundary().contains("标准"));
        assertFalse(WorldTone.STANDARD.boundary().contains("不要写势力斗争"),
                "标准档不该带休闲档的禁令，否则等于偷偷改了所有历史世界的输入");
        assertTrue(WorldTone.CASUAL.boundary().contains("不要写势力斗争"));
        assertEquals(4, WorldTone.values().length, "四档，不能增减");
    }

    @Test
    @DisplayName("程度：每档都有给下拉用的短说明")
    void everyToneHasSummary() {
        for (WorldTone t : WorldTone.values()) {
            assertTrue(t.summary() != null && !t.summary().isBlank(), "缺短说明：" + t.code());
            assertTrue(t.label() != null && !t.label().isBlank());
        }
        assertNull(null);
    }
}
