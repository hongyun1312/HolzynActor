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

package com.holzyn.actor.domain.world.effect.service;

import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.adjudicate.model.Verdict;
import com.holzyn.actor.domain.world.effect.model.EffectApplyException;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.effect.model.EffectResult;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行动效果落地顺序测试：先执行行动/发事件，再拿 eventId 应用 effects。
 */
class ActionEffectServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ActionEffectService service;
    private AgentActionService actionService;
    private EffectApplier effectApplier;
    private ActorActionPlanRepository planRepository;
    private ActorCharacterRepository characterRepository;
    private WorldClockService clockService;

    @BeforeEach
    void setUp() {
        actionService = mock(AgentActionService.class);
        effectApplier = mock(EffectApplier.class);
        planRepository = mock(ActorActionPlanRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        clockService = mock(WorldClockService.class);
        service = new ActionEffectService();
        ReflectionTestUtils.setField(service, "agentActionService", actionService);
        ReflectionTestUtils.setField(service, "effectApplier", effectApplier);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "worldClockService", clockService);
    }

    @Test
    @DisplayName("SUCCESS + effects：行动先完成拿到 eventId，再应用 effect；结果写 effects_json")
    void appliesEffectsAfterActionEvent() {
        ActorActionPlan plan = plan(8L, 1L);
        ActorCharacter ch = character(1L, 7L, "码头");
        ActionVerdict verdict = verdictWithOneEffect();
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
        when(clockService.currentEngineGameSecond(7L)).thenReturn(1000L);
        doAnswer(inv -> {
            plan.setStatus("done");
            plan.setEventId(99L);
            return null;
        }).when(actionService).executeWithVerdict(plan, verdict);
        when(effectApplier.applyWithReport(any(), any())).thenReturn(new EffectReport(List.of(
                EffectResult.applied(0, "fact.upsert", 5L, null))));

        service.execute(plan, verdict);

        ArgumentCaptor<EffectContext> ctx = ArgumentCaptor.forClass(EffectContext.class);
        verify(effectApplier).applyWithReport(any(), ctx.capture());
        assertEquals(99L, ctx.getValue().eventId());
        assertEquals(8L, ctx.getValue().planId());
        assertEquals("npc:1", ctx.getValue().actorRef());
        assertEquals(1000L, ctx.getValue().gameSecond());
        assertTrue(plan.getEffectsJson().contains("\"results\""));
        assertTrue(plan.getEffectsJson().contains("fact.upsert"));
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("FAIL/无可执行效果：不应用 effect，保持 M2 行为")
    void failDoesNotApplyEffects() {
        ActorActionPlan plan = plan(8L, 1L);
        ActionVerdict fail = new ActionVerdict(Verdict.FAIL, "ROUTE_BLOCKED", "路封了",
                0, List.of(), "rule");
        doAnswer(inv -> {
            plan.setStatus("cancelled");
            return null;
        }).when(actionService).executeWithVerdict(plan, fail);

        service.execute(plan, fail);

        verify(effectApplier, never()).applyWithReport(any(), any());
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("效果失败：异常继续上抛，由外层事务回滚 + 写 EFFECT_FAILED")
    void effectFailurePropagates() {
        ActorActionPlan plan = plan(8L, 1L);
        ActionVerdict verdict = verdictWithOneEffect();
        when(characterRepository.findById(1L)).thenReturn(Optional.of(character(1L, 7L, "码头")));
        when(clockService.currentEngineGameSecond(7L)).thenReturn(1000L);
        doAnswer(inv -> {
            plan.setStatus("done");
            plan.setEventId(99L);
            return null;
        }).when(actionService).executeWithVerdict(plan, verdict);
        when(effectApplier.applyWithReport(any(), any()))
                .thenThrow(new EffectApplyException(0, "fact.upsert", new IllegalStateException("boom")));

        assertThrows(EffectApplyException.class, () -> service.execute(plan, verdict));
    }

    private ActorActionPlan plan(Long id, Long charId) {
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(id);
        plan.setCharacterId(charId);
        plan.setStatus("planned");
        plan.setActionJson("{\"action\":\"去码头\"}");
        return plan;
    }

    private ActorCharacter character(Long id, Long projectId, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(projectId);
        ch.setLocation(location);
        return ch;
    }

    private ActionVerdict verdictWithOneEffect() {
        EffectOp op = new EffectOp("fact.upsert",
                mapper.createObjectNode().put("factType", "route").put("factKey", "route:A-B")
                        .put("stateText", "桥塌了"), "plan:8:op:0");
        return new ActionVerdict(Verdict.SUCCESS, "R-DEFAULT", "", 0, List.of(op), "rule");
    }
}
