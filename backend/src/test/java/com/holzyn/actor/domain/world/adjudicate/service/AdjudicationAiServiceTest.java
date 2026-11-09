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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 裁决服务：未配置/异常/空输出降级 PARTIAL，合法 JSON 原样解析。
 */
class AdjudicationAiServiceTest {

    private AdjudicationAiService service;
    private AiSpringChatGateway gateway;
    private WorldLoopProperties props;
    private com.holzyn.actor.domain.project.repository.ActorProjectRepository projects;

    @BeforeEach
    void setUp() {
        service = new AdjudicationAiService();
        gateway = mock(AiSpringChatGateway.class);
        props = new WorldLoopProperties();
        projects = mock(com.holzyn.actor.domain.project.repository.ActorProjectRepository.class);
        com.holzyn.actor.domain.project.entity.ActorProject project =
                new com.holzyn.actor.domain.project.entity.ActorProject();
        project.setId(1L);
        project.setUserId(1L);
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(project));
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "properties", props);
        ReflectionTestUtils.setField(service, "projectRepository", projects);
    }

    @Test
    @DisplayName("网关未装配 → PARTIAL + AI_UNAVAILABLE（不 FAIL）")
    void noGatewayFallsBack() {
        ReflectionTestUtils.setField(service, "aiChatGateway", null);
        ActionVerdict v = service.adjudicate(ctx());
        assertEquals(Verdict.PARTIAL, v.verdict());
        assertEquals("AI_UNAVAILABLE", v.reasonCode());
        assertEquals("fallback", v.adjudicatedBy());
    }

    @Test
    @DisplayName("网关抛异常 → PARTIAL + AI_UNAVAILABLE")
    void gatewayFailureFallsBack() {
        when(gateway.chatCompletion(anyLong(), anyLong(), isNull(), any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        ActionVerdict v = service.adjudicate(ctx());
        assertEquals(Verdict.PARTIAL, v.verdict());
        assertEquals("AI_UNAVAILABLE", v.reasonCode());
    }

    @Test
    @DisplayName("空输出 → PARTIAL + AI_UNAVAILABLE")
    void blankOutputFallsBack() {
        when(gateway.chatCompletion(anyLong(), anyLong(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("   ", "m", 10, 0, 10, null, null, 66L));
        ActionVerdict v = service.adjudicate(ctx());
        assertEquals(Verdict.PARTIAL, v.verdict());
        assertEquals("AI_UNAVAILABLE", v.reasonCode());
    }

    @Test
    @DisplayName("合法 JSON → 三态原样返回，effects 受配置上限约束")
    void validOutputParsed() {
        when(gateway.chatCompletion(anyLong(), anyLong(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("""
                        {"verdict":"FAIL","reasonCode":"ROUTE_BLOCKED","observedText":"路封了。",
                         "effects":[{"op":"relation.delta","body":{}},{"op":"memory.add","body":{}}]}
                        """, "m", 10, 20, 30, null, null, 66L));
        ActionVerdict v = service.adjudicate(ctx());
        assertEquals(Verdict.FAIL, v.verdict());
        assertEquals("ROUTE_BLOCKED", v.reasonCode());
        assertEquals(2, v.effects().size());
    }

    @Test
    @DisplayName("用户消息含行动者/意图/地点/已知事实占位符替换结果")
    void rendersPromptWithoutLeavingPlaceholders() {
        when(gateway.chatCompletion(anyLong(), anyLong(), isNull(), any(AiChatRequest.class)))
                .thenAnswer(inv -> {
                    AiChatRequest req = inv.getArgument(3);
                    String user = req.messages().stream()
                            .filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
                    org.junit.jupiter.api.Assertions.assertFalse(user.contains("{{actor_name}}"));
                    org.junit.jupiter.api.Assertions.assertTrue(user.contains("阿禾"));
                    org.junit.jupiter.api.Assertions.assertTrue(user.contains("TRAVEL"));
                    return new AiChatResult("{\"verdict\":\"SUCCESS\"}", "m", 1, 1, 2, null, null, 66L);
                });
        assertEquals(Verdict.SUCCESS, service.adjudicate(ctx()).verdict());
    }

    private static AdjudicationContext ctx() {
        ActorCharacter actor = new ActorCharacter();
        actor.setId(10L);
        actor.setProjectId(1L);
        actor.setName("阿禾");
        actor.setLocation("集市");
        return AdjudicationContext.builder()
                .projectId(1L)
                .actor(actor)
                .intent(com.holzyn.actor.domain.world.adjudicate.model.IntentKind.TRAVEL)
                .decision(new ObjectMapper().createObjectNode()
                        .put("action", "去码头").put("type", "move_to"))
                .gameSecond(1000L)
                .locationName("集市")
                .targetName("码头")
                .importance(5)
                .energy(80)
                .knownFacts(List.of())
                .inventory(List.of())
                .inventoryKnown(false)
                .build();
    }
}
