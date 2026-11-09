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

package com.holzyn.actor.domain.agent.initiative.service;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.adjudicate.service.ActionAdjudicationService;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NpcInitiativeServiceTest {

    private NpcInitiativeService service;
    private WorldLoopProperties properties;
    private ActorCharacterRepository characters;
    private ActorProjectRepository projects;
    private ActorActionPlanRepository plans;
    private ActorWorldFactRepository factRepository;
    private ActorWorldFactKnowledgeRepository knowledge;
    private ActorCharacterRelationRepository relations;
    private ActorEventRepository events;
    private WorldFactQuery factQuery;
    private WorldEventBus eventBus;
    private ActionAdjudicationService adjudication;
    private WorldClockService worldClock;

    private ActorCharacter player;
    private ActorProject project;

    @BeforeEach
    void setUp() {
        characters = mock(ActorCharacterRepository.class);
        projects = mock(ActorProjectRepository.class);
        plans = mock(ActorActionPlanRepository.class);
        factRepository = mock(ActorWorldFactRepository.class);
        knowledge = mock(ActorWorldFactKnowledgeRepository.class);
        relations = mock(ActorCharacterRelationRepository.class);
        events = mock(ActorEventRepository.class);
        factQuery = mock(WorldFactQuery.class);
        eventBus = mock(WorldEventBus.class);
        adjudication = mock(ActionAdjudicationService.class);
        worldClock = mock(WorldClockService.class);
        when(worldClock.currentEngineGameSecond(1L)).thenReturn(12_345L);
        properties = new WorldLoopProperties();
        properties.setEnabled(true);
        properties.getInitiative().setEnabled(true);
        properties.getInitiative().setMinUrgency(3);
        properties.getInitiative().setMaxPerHour(2);
        properties.getInitiative().setAbsenceDays(3);

        service = new NpcInitiativeService(characters, projects, plans, factRepository, knowledge,
                relations, events, factQuery, eventBus, properties);
        ReflectionTestUtils.setField(service, "actionAdjudicationService", adjudication);
        ReflectionTestUtils.setField(service, "worldClockService", worldClock);

        player = character(77L, "traveler", "A", "player");
        project = new ActorProject();
        project.setId(1L);
        project.setUserId(9L);
        when(characters.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.of(player));
        when(characters.findById(77L)).thenReturn(Optional.of(player));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(factQuery.byType(eq(1L), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(knowledge.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                eq(1L), anyString(), any(Pageable.class))).thenReturn(List.of());
        when(relations.findByProjectId(1L)).thenReturn(List.of());
        when(events.findByProjectIdAndCharacterRef(eq(1L), anyLong(), anyString())).thenReturn(List.of());
        when(plans.save(any(ActorActionPlan.class))).thenAnswer(inv -> {
            ActorActionPlan plan = inv.getArgument(0);
            plan.setId(100L);
            return plan;
        });
    }

    @Test
    @DisplayName("契约到期：建 initiative 计划，带父事件并走规则执行")
    void contractDueCreatesPlan() {
        ActorCharacter creditor = character(65L, "creditor", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(creditor));
        ActorWorldFact contract = fact(3L, "contract", "contract:1", 10L,
                "{\"parties\":[\"npc:65\",\"npc:77\"]}");
        when(factQuery.byType(1L, "contract", "active", 200)).thenReturn(List.of(contract));

        int created = service.scan(1L, 100_000L);

        assertEquals(1, created);
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertEquals("initiative", captor.getValue().getTriggerType());
        assertTrue(captor.getValue().getActionJson().contains("\"causeEventId\":10"));
        assertTrue(captor.getValue().getActionJson().contains("\"kind\":\"contract\""));
        verify(adjudication).executeGated(captor.getValue());
    }

    @Test
    @DisplayName("同地点：执行即到达，发布私密 initiative 通知并带因果")
    void sameLocationPublishesArrival() {
        ActorCharacter creditor = character(65L, "creditor", "A", null);
        when(characters.findById(65L)).thenReturn(Optional.of(creditor));
        ActorWorldFact contract = fact(3L, "contract", "contract:2", 11L,
                "{\"parties\":[\"npc:65\",\"npc:77\"]}");
        when(factQuery.byType(1L, "contract", "active", 200)).thenReturn(List.of(contract));
        when(eventBus.publish(anyLong(), anyLong(), any(EventSpec.class))).thenReturn(new ActorEvent());

        int created = service.scan(1L, 100_000L);

        assertEquals(1, created);
        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus, atLeastOnce()).publish(eq(9L), eq(1L), spec.capture());
        assertEquals("initiative", spec.getValue().getSource());
        assertEquals("private", spec.getValue().getVisibility());
        assertEquals(11L, spec.getValue().getCauseEventId());
        assertTrue(spec.getValue().getCharacterRefs().contains("npc:77"));
        assertTrue(spec.getValue().getTitle().contains("来找你"));
        assertEquals(27L, spec.getValue().getGameHour());
    }

    @Test
    @DisplayName("Authority 到达未传时刻：从世界时钟补 gameHour，不落成 0001 年")
    void authorityArrivalUsesWorldClock() {
        ActorCharacter creditor = character(65L, "creditor", "A", null);
        when(characters.findById(65L)).thenReturn(Optional.of(creditor));
        when(eventBus.publish(anyLong(), anyLong(), any(EventSpec.class))).thenReturn(new ActorEvent());
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(200L);
        plan.setCharacterId(65L);
        plan.setTriggerType("initiative");
        plan.setStatus("done");
        plan.setActionJson("{\"reason\":\"契约快到期了\",\"_initiative\":{\"kind\":\"contract\","
                + "\"npcRef\":\"npc:65\",\"playerRef\":\"npc:77\",\"destination\":\"A\","
                + "\"factKey\":\"contract:1\",\"causeEventId\":10}}");

        service.onAuthorityArrived(plan);

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus).publish(eq(9L), eq(1L), spec.capture());
        assertEquals(3L, spec.getValue().getGameHour());
        assertEquals(10L, spec.getValue().getCauseEventId());
    }

    @Test
    @DisplayName("Authority 到达优先用计划扫描时刻，避免 tick 拨钟前的时间错位")
    void authorityArrivalPrefersPlanScanTime() {
        ActorCharacter creditor = character(65L, "creditor", "A", null);
        when(characters.findById(65L)).thenReturn(Optional.of(creditor));
        when(eventBus.publish(anyLong(), anyLong(), any(EventSpec.class))).thenReturn(new ActorEvent());
        ActorActionPlan plan = new ActorActionPlan();
        plan.setId(201L);
        plan.setCharacterId(65L);
        plan.setTriggerType("initiative");
        plan.setStatus("done");
        // createdGameSecond=7200 → 2 时；世界时钟是 12345（3 时），应优先用计划扫描时刻
        plan.setActionJson("{\"reason\":\"契约快到期了\",\"_initiative\":{\"kind\":\"contract\","
                + "\"npcRef\":\"npc:65\",\"playerRef\":\"npc:77\",\"destination\":\"A\","
                + "\"factKey\":\"contract:2\",\"causeEventId\":11,\"createdGameSecond\":7200}}");

        service.onAuthorityArrived(plan);

        ArgumentCaptor<EventSpec> spec = ArgumentCaptor.forClass(EventSpec.class);
        verify(eventBus).publish(eq(9L), eq(1L), spec.capture());
        assertEquals(2L, spec.getValue().getGameHour(), "用 _initiative.createdGameSecond/3600");
        assertEquals(11L, spec.getValue().getCauseEventId());
    }

    @Test
    @DisplayName("项目级每小时最多 2 个；第三个候选不发起")
    void capsAtTwoPerHour() {
        ActorCharacter a = character(65L, "a", "B", null);
        ActorCharacter b = character(66L, "b", "B", null);
        ActorCharacter c = character(67L, "c", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(a));
        when(characters.findById(66L)).thenReturn(Optional.of(b));
        when(characters.findById(67L)).thenReturn(Optional.of(c));
        when(factQuery.byType(1L, "contract", "active", 200)).thenReturn(List.of(
                fact(3L, "contract", "contract:3", 12L, "{\"parties\":[\"npc:65\",\"npc:77\"]}"),
                fact(4L, "contract", "contract:4", 13L, "{\"parties\":[\"npc:66\",\"npc:77\"]}"),
                fact(5L, "contract", "contract:5", 14L, "{\"parties\":[\"npc:67\",\"npc:77\"]}")));

        int created = service.scan(1L, 100_000L);

        assertEquals(2, created);
        verify(plans, atLeastOnce()).save(any(ActorActionPlan.class));
    }

    @Test
    @DisplayName("开关关闭：零查询、零计划")
    void disabledIsNoop() {
        properties.getInitiative().setEnabled(false);

        assertEquals(0, service.scan(1L, 100_000L));
        verify(plans, never()).save(any(ActorActionPlan.class));
        verify(factQuery, never()).byType(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("传闻缠身：NPC 持有关于玩家的 rumor 认知才发起")
    void rumorAboutPlayerCreatesPlan() {
        ActorCharacter npc = character(66L, "gossip", "B", null);
        when(characters.findById(66L)).thenReturn(Optional.of(npc));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(npc));
        ActorWorldFactKnowledge row = new ActorWorldFactKnowledge();
        row.setFactId(9L);
        row.setSourceType("rumor");
        when(knowledge.findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                eq(1L), eq("npc:66"), any(Pageable.class))).thenReturn(List.of(row));
        ActorWorldFact rumor = fact(9L, "reputation", "reputation:9", 15L,
                "{\"parties\":[\"npc:66\",\"npc:77\"]}");
        when(factRepository.findById(9L)).thenReturn(Optional.of(rumor));

        int created = service.scan(1L, 100_000L);

        assertEquals(1, created);
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertTrue(captor.getValue().getActionJson().contains("\"kind\":\"rumor\""));
        assertTrue(captor.getValue().getActionJson().contains("\"causeEventId\":15"));
        assertEquals("initiative", captor.getValue().getTriggerType());
    }

    @Test
    @DisplayName("同一事实已有 initiative 计划：不重复发起")
    void sameFactDoesNotDuplicatePlan() {
        ActorCharacter creditor = character(65L, "creditor", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(creditor));
        when(factQuery.byType(1L, "contract", "active", 200)).thenReturn(List.of(
                fact(3L, "contract", "contract:1", 10L, "{\"parties\":[\"npc:65\",\"npc:77\"]}")));
        ActorActionPlan existing = new ActorActionPlan();
        existing.setCharacterId(65L);
        existing.setTriggerType("initiative");
        existing.setStatus("done");
        existing.setActionJson("\"{\\\"_initiative\\\":{\\\"factKey\\\":\\\"contract:1\\\"}}\"");
        when(plans.findByCharacterIdOrderByIdDesc(65L)).thenReturn(List.of(existing));

        assertEquals(0, service.scan(1L, 100_000L));
        verify(plans, never()).save(any(ActorActionPlan.class));
    }

    @Test
    @DisplayName("已发起事实不占名额：新事实仍可在本小时发起")
    void existingFactDoesNotStarveNewFact() {
        ActorCharacter a = character(65L, "a", "B", null);
        ActorCharacter b = character(66L, "b", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(a));
        when(characters.findById(66L)).thenReturn(Optional.of(b));
        when(factQuery.byType(1L, "contract", "active", 200)).thenReturn(List.of(
                fact(3L, "contract", "contract:1", 10L, "{\"parties\":[\"npc:65\",\"npc:77\"]}"),
                fact(4L, "contract", "contract:2", 11L, "{\"parties\":[\"npc:66\",\"npc:77\"]}")));
        ActorActionPlan existing = new ActorActionPlan();
        existing.setCharacterId(65L);
        existing.setTriggerType("initiative");
        existing.setStatus("done");
        existing.setActionJson("{\"_initiative\":{\"factKey\":\"contract:1\"}}");
        when(plans.findByCharacterIdOrderByIdDesc(65L)).thenReturn(List.of(existing));

        assertEquals(1, service.scan(1L, 100_000L));
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertEquals(66L, captor.getValue().getCharacterId());
        assertTrue(captor.getValue().getActionJson().contains("contract:2"));
    }

    @Test
    @DisplayName("久未见面：最近互动超过阈值才发起")
    void absenceBeyondThreshold() {
        ActorCharacter npc = character(65L, "old-friend", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(npc));
        ActorCharacterRelation relation = new ActorCharacterRelation();
        relation.setFromRef("npc:65");
        relation.setToRef("npc:77");
        relation.setAffinity(20);
        relation.setTrust(30);
        when(relations.findByProjectId(1L)).thenReturn(List.of(relation));
        ActorEvent last = new ActorEvent();
        last.setId(50L);
        last.setKind("dialog");
        last.setGameHour(0L);
        last.setCharacterIds("npc:65,npc:77");
        when(events.findByProjectIdAndCharacterRef(1L, 65L, "npc:65")).thenReturn(List.of(last));

        int created = service.scan(1L, 4 * 86_400L);

        assertEquals(1, created);
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertTrue(captor.getValue().getActionJson().contains("\"kind\":\"absence\""));
        assertTrue(captor.getValue().getActionJson().contains("\"causeEventId\":50"));
    }

    // ==================== 对接：物品类发起 ====================

    @Test
    @DisplayName("M×I：饿了又没吃的 → 发起「断粮」（urgency 3）")
    void hungryWithoutFoodInitiates() {
        wireItems();
        ActorCharacter smith = character(65L, "铁匠", "B", null);
        smith.setOccupation("铁匠");
        when(characters.findById(65L)).thenReturn(Optional.of(smith));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(player, smith));
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        st.setHunger(20);
        when(states().stateFor(1L, "npc:65")).thenReturn(st);
        when(port().list(1L, "npc:65")).thenReturn(List.of());

        int created = service.scan(1L, 100_000L);

        assertEquals(1, created);
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertTrue(captor.getValue().getActionJson().contains("断粮"));
        assertTrue(captor.getValue().getActionJson().contains("item:food:npc:65"));
    }

    @Test
    @DisplayName("M×I：身上有面包 → 不算断粮，不发起")
    void hungryWithFoodDoesNotInitiate() {
        wireItems();
        ActorCharacter smith = character(65L, "铁匠", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(smith));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(player, smith));
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        st.setHunger(20);
        when(states().stateFor(1L, "npc:65")).thenReturn(st);
        when(port().list(1L, "npc:65")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("面包", "面包", "food", 1, 100, 100, null)));

        int created = service.scan(1L, 100_000L);

        assertEquals(0, created);
        verify(plans, never()).save(any(ActorActionPlan.class));
    }

    @Test
    @DisplayName("M×I：吃饭的家伙快断了 → 发起「帮衬一把」（urgency 2）")
    void wornToolInitiates() {
        wireItems();
        properties.getInitiative().setMinUrgency(2);
        ActorCharacter smith = character(65L, "铁匠", "B", null);
        when(characters.findById(65L)).thenReturn(Optional.of(smith));
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(player, smith));
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        st.setHunger(90);
        when(states().stateFor(1L, "npc:65")).thenReturn(st);
        when(port().list(1L, "npc:65")).thenReturn(List.of(
                new com.holzyn.actor.domain.item.model.ItemView("柴刀", "柴刀", "weapon", 1, 100, 20, "hand")));

        int created = service.scan(1L, 100_000L);

        assertEquals(1, created);
        ArgumentCaptor<ActorActionPlan> captor = ArgumentCaptor.forClass(ActorActionPlan.class);
        verify(plans).save(captor.capture());
        assertTrue(captor.getValue().getActionJson().contains("快不能用了"));
        assertTrue(captor.getValue().getActionJson().contains("item:tool:npc:65"));
    }

    @Test
    @DisplayName("M×I：物品域关闭 → 完全不扫物品类发起（关开关=回基线）")
    void itemDisabledSkipsItemTriggers() {
        wireItems();
        com.holzyn.actor.domain.item.config.ItemProperties off =
                new com.holzyn.actor.domain.item.config.ItemProperties();
        off.setEnabled(false);
        ReflectionTestUtils.setField(service, "itemProperties", off);
        ActorCharacter smith = character(65L, "铁匠", "B", null);
        when(characters.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(player, smith));
        com.holzyn.actor.domain.npc.state.entity.ActorNpcState st =
                new com.holzyn.actor.domain.npc.state.entity.ActorNpcState();
        st.setHunger(5);
        when(states().stateFor(1L, "npc:65")).thenReturn(st);

        int created = service.scan(1L, 100_000L);

        assertEquals(0, created);
        verify(port(), never()).list(anyLong(), anyString());
    }

    /** 装配物品类发起所需的三件套（端口/开关/状态门面）。 */
    private void wireItems() {
        com.holzyn.actor.domain.item.port.InventoryPort inventoryPort =
                mock(com.holzyn.actor.domain.item.port.InventoryPort.class);
        com.holzyn.actor.domain.item.config.ItemProperties itemProperties =
                new com.holzyn.actor.domain.item.config.ItemProperties();
        itemProperties.setEnabled(true);
        com.holzyn.actor.domain.npc.state.service.NpcStateService npcStates =
                mock(com.holzyn.actor.domain.npc.state.service.NpcStateService.class);
        ReflectionTestUtils.setField(service, "inventoryPort", inventoryPort);
        ReflectionTestUtils.setField(service, "itemProperties", itemProperties);
        ReflectionTestUtils.setField(service, "npcStateService", npcStates);
    }

    private com.holzyn.actor.domain.item.port.InventoryPort port() {
        return (com.holzyn.actor.domain.item.port.InventoryPort)
                ReflectionTestUtils.getField(service, "inventoryPort");
    }

    private com.holzyn.actor.domain.npc.state.service.NpcStateService states() {
        return (com.holzyn.actor.domain.npc.state.service.NpcStateService)
                ReflectionTestUtils.getField(service, "npcStateService");
    }

    private ActorCharacter character(Long id, String name, String location, String controller) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setProjectId(1L);
        ch.setName(name);
        ch.setLocation(location);
        ch.setDeleted(0);
        if (controller != null) {
            ch.setController(controller);
        }
        return ch;
    }

    private ActorWorldFact fact(Long id, String type, String key, Long sourceEventId, String attributes) {
        ActorWorldFact fact = new ActorWorldFact();
        fact.setId(id);
        fact.setProjectId(1L);
        fact.setFactType(type);
        fact.setFactKey(key);
        fact.setName(key);
        fact.setVisibility("private");
        fact.setAuthority("brain");
        fact.setStatus("active");
        fact.setSourceEventId(sourceEventId);
        fact.setAttributesJson(attributes);
        fact.setValidToGameSecond(100_000L + 3600L);
        return fact;
    }
}
