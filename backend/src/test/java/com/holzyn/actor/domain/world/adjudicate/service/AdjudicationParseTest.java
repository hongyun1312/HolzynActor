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

package com.holzyn.actor.domain.world.adjudicate.service;

import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 裁决输出解析：闭集、效果上限、失败降级 PARTIAL。
 */
class AdjudicationParseTest {

    @Test
    @DisplayName("合法 JSON → 三态 + 效果草稿")
    void parsesValidJson() {
        ActionVerdict v = AdjudicationParse.parse("""
                {"verdict":"FAIL","reasonCode":"ROUTE_BLOCKED","observedText":"路被封了。",
                 "durationMinutes":15,"effects":[{"op":"relation.delta","body":{"delta":-2}}]}
                """, 4);
        assertEquals(Verdict.FAIL, v.verdict());
        assertEquals("ROUTE_BLOCKED", v.reasonCode());
        assertEquals("路被封了。", v.observedText());
        assertEquals(15, v.durationMinutes());
        assertEquals(1, v.effects().size());
        assertEquals("ai", v.adjudicatedBy());
    }

    @Test
    @DisplayName("带 ```json 围栏也能解析")
    void stripsFence() {
        ActionVerdict v = AdjudicationParse.parse("""
                ```json
                {"verdict":"SUCCESS","reasonCode":"OK","observedText":"成了。"}
                ```
                """, 4);
        assertEquals(Verdict.SUCCESS, v.verdict());
        assertEquals("成了。", v.observedText());
    }

    @Test
    @DisplayName("非法 verdict → PARTIAL + AI_PARSE_FALLBACK（绝不 FAIL）")
    void invalidVerdictFallsBackToPartial() {
        ActionVerdict v = AdjudicationParse.parse("{\"verdict\":\"UNSURE\",\"reasonCode\":\"X\"}", 4);
        assertEquals(Verdict.PARTIAL, v.verdict());
        assertEquals("AI_PARSE_FALLBACK", v.reasonCode());
        assertEquals("fallback", v.adjudicatedBy());
    }

    @Test
    @DisplayName("坏 JSON / 空输出 → PARTIAL + AI_PARSE_FALLBACK")
    void malformedJsonFallsBack() {
        assertEquals(Verdict.PARTIAL, AdjudicationParse.parse("not-json", 4).verdict());
        assertEquals(Verdict.PARTIAL, AdjudicationParse.parse(null, 4).verdict());
        assertEquals(Verdict.PARTIAL, AdjudicationParse.parse("", 4).verdict());
    }

    @Test
    @DisplayName("效果条数受 maxEffects 限制，未知 op 被丢弃")
    void capsAndFiltersEffects() {
        ActionVerdict v = AdjudicationParse.parse("""
                {"verdict":"SUCCESS","effects":[
                  {"op":"relation.delta","body":{}},
                  {"op":"evil.hack","body":{}},
                  {"op":"memory.add","body":{}},
                  {"op":"fact.upsert","body":{}},
                  {"op":"info.packet","body":{}}
                ]}
                """, 2);
        assertEquals(2, v.effects().size());
        assertEquals("relation.delta", v.effects().get(0).op());
        assertEquals("memory.add", v.effects().get(1).op());
    }

    @Test
    @DisplayName("observed 截断 200、reason 截断 40、duration 钳制 1440")
    void clampsFields() {
        String big = "观".repeat(300);
        ActionVerdict v = AdjudicationParse.parse("{\"verdict\":\"SUCCESS\",\"reasonCode\":\""
                + "R".repeat(80) + "\",\"observedText\":\"" + big + "\",\"durationMinutes\":99999}", 4);
        assertEquals(200, v.observedText().length());
        assertEquals(40, v.reasonCode().length());
        assertEquals(1440, v.durationMinutes());
    }

    @Test
    @DisplayName("effects 非数组 → 空列表，不阻断")
    void nonArrayEffectsIsEmpty() {
        ActionVerdict v = AdjudicationParse.parse("{\"verdict\":\"PARTIAL\",\"effects\":\"x\"}", 4);
        assertTrue(v.effects().isEmpty());
        assertEquals(Verdict.PARTIAL, v.verdict());
    }
}
