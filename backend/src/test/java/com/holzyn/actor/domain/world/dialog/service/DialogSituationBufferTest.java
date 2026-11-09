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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮末情境缓冲测试。
 *
 * <p>情境只保留最近一轮；事实段只接受带条目的 digest（仅版本行不注入）；无缓冲返回 null，
 * 由调用方回退既有情境组装。</p>
 */
class DialogSituationBufferTest {

    private WorldFactQuery factQuery;
    private DialogSituationBuffer buffer;

    @BeforeEach
    void setUp() {
        factQuery = mock(WorldFactQuery.class);
        buffer = new DialogSituationBuffer();
        ReflectionTestUtils.setField(buffer, "worldFactQuery", factQuery);
    }

    @Test
    @DisplayName("无缓冲：返回 null（回退既有 situation）")
    void noBufferReturnsNull() {
        assertNull(buffer.situationFor(7L, "npc:2", 9L, 1000L));
    }

    @Test
    @DisplayName("摘要 + 事实 + 在场者按段拼接")
    void joinsSummaryFactsAndPresentNames() {
        buffer.remember("conversation:9", "他们约好明早在码头见", List.of("张三", "李四"));
        when(factQuery.digestForPrompt(7L, "npc:2", 1000L, 8))
                .thenReturn("【我所知道的事】\n- 码头：封闭（听说）\n[世界状态版本 gs1000/v3]");

        String situation = buffer.situationFor(7L, "npc:2", 9L, 1000L);

        assertTrue(situation.contains("【本轮对话】他们约好明早在码头见"));
        assertTrue(situation.contains("- 码头：封闭"));
        assertTrue(situation.contains("【在场】张三、李四"));
    }

    @Test
    @DisplayName("只有版本行的事实 digest 不注入（避免空段占上下文）")
    void versionOnlyDigestIsSkipped() {
        buffer.remember("conversation:9", "闲聊", List.of());
        when(factQuery.digestForPrompt(anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn("[世界状态版本 gs1000/v0]");

        String situation = buffer.situationFor(7L, "npc:2", 9L, 1000L);

        assertTrue(situation.contains("【本轮对话】闲聊"));
        assertTrue(!situation.contains("世界状态版本"));
    }

    @Test
    @DisplayName("覆盖式：同一会话只保留最新一轮")
    void rememberOverwritesLatestRound() {
        buffer.remember("conversation:9", "第一轮", List.of());
        buffer.remember("conversation:9", "第二轮", List.of());

        String situation = buffer.situationFor(7L, "npc:2", 9L, 1000L);

        assertTrue(situation.contains("第二轮"));
        assertTrue(!situation.contains("第一轮"));
    }
}
