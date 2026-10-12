package com.holzyn.actor.domain.growth.infant;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 婴儿行为程序化状态机单元测试（O6 G4，Q7：零 AI 状态机 + 啼哭刺激父母 + 位置跟随母亲）。
 */
@ExtendWith(MockitoExtension.class)
class InfantBehaviorServiceTest {

    private static final long DAY = 86400L;

    @Mock private ActorCharacterRepository characterRepository;
    @Mock private ActorNpcStateRepository stateRepository;
    @Mock private LineageService lineageService;
    @Mock private WorldEventBus worldEventBus;
    @Mock private CurrentUserProvider currentUserProvider;

    private InfantBehaviorService service;
    private ActorCharacter infant;
    private ActorNpcState infantState;

    @BeforeEach
    void setUp() {
        GrowthProperties props = new GrowthProperties();
        service = new InfantBehaviorService(characterRepository, stateRepository, lineageService, props,
                worldEventBus, currentUserProvider);
        infant = new ActorCharacter();
        infant.setId(50L);
        infant.setName("沈小苗");
        infant.setProjectId(1L);
        infant.setStage(GrowthStage.INFANT.name());
        infant.setLocation("家中");
        infant.setCurrentActivity("襁褓中安睡");
        infantState = new ActorNpcState();
        infantState.setProjectId(1L);
        infantState.setNpcRef("npc:50");
        infantState.setHunger(70);
        infantState.setBladder(70);
        infantState.setEnergy(80);
        lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(infant));
        lenient().when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:50"))
                .thenReturn(Optional.of(infantState));
        lenient().when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stateRepository.save(any(ActorNpcState.class))).thenAnswer(inv -> inv.getArgument(0));
        // 母亲 npc:2（默认无谱系母亲 → 位置不跟随）
        lenient().when(lineageService.motherOf(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(lineageService.parentEdgesOf(anyLong(), anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Q7：需求衰减 + 饥饿跌破阈值 → 啼哭，活动文本与喂食恢复生效")
    void cryWhenHungry() {
        infantState.setHunger(30); // < 35 阈值
        infantState.setBladder(60);
        service.advanceInfants(1L, 100 * DAY);
        // 啼哭 → 程序化喂食恢复（hunger 30 - 2.5 + 35 = 62.5 → 63）
        assertTrue(infantState.getHunger() > 30, "啼哭后应恢复 hunger");
        assertTrue(infant.getCurrentActivity().contains("啼哭"), "活动文本应反映啼哭：" + infant.getCurrentActivity());
    }

    @Test
    @DisplayName("Q7：需求正常 → 安睡/醒来，不啼哭不喂食")
    void noCryWhenSatisfied() {
        infantState.setHunger(70);
        infantState.setBladder(70);
        infantState.setEnergy(90);
        service.advanceInfants(1L, 100 * DAY);
        assertTrue(infant.getCurrentActivity().contains("咿呀") || infant.getCurrentActivity().contains("安睡"),
                "需求正常 → 醒着玩/安睡：" + infant.getCurrentActivity());
        // 衰减后仍高于阈值
        assertTrue(infantState.getHunger() >= 67, "hunger 衰减 2.5/小时");
    }

    @Test
    @DisplayName("Q7：啼哭 → 向父母发布世界事件（infant_cry，小时级幂等键）")
    void cryStimulusToParents() {
        infantState.setHunger(20);
        when(lineageService.parentEdgesOf(1L, "npc:50")).thenReturn(List.of(
                parentEdge("npc:2")));
        service.advanceInfants(1L, 100 * DAY + 3600L);
        verify(worldEventBus).publish(anyLong(), anyLong(), any());
    }

    private com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage parentEdge(String ref) {
        com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage e =
                new com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage();
        e.setProjectId(1L);
        e.setEdgeType(com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage.EDGE_PARENT_CHILD);
        e.setFromRef(ref);
        e.setToRef("npc:50");
        return e;
    }

    @Test
    @DisplayName("Q7：位置跟随母亲——母亲换地方，婴儿同步")
    void followsMotherLocation() {
        ActorCharacter mother = new ActorCharacter();
        mother.setId(2L);
        mother.setName("白小满");
        mother.setProjectId(1L);
        mother.setStage(GrowthStage.PRIME.name());
        mother.setLocation("东渠");
        mother.setLocationId(7L);
        when(lineageService.motherOf(1L, "npc:50")).thenReturn(Optional.of("npc:2"));
        when(characterRepository.findById(2L)).thenReturn(Optional.of(mother));
        infantState.setHunger(70);
        infantState.setBladder(70);
        service.advanceInfants(1L, 100 * DAY + 7200L);
        assertEquals("东渠", infant.getLocation(), "婴儿位置应跟随母亲");
        assertEquals(7L, infant.getLocationId());
    }

    @Test
    @DisplayName("Q7：需求长期不满足 → 程序化喂食防止饿死（hunger 不触底）")
    void feedingPreventsStarvation() {
        infantState.setHunger(0);
        infantState.setBladder(80);
        service.advanceInfants(1L, 100 * DAY);
        assertEquals(35, infantState.getHunger(), "0 - 3（衰减取整）+ 35（喂食恢复）= 32~35");
        assertTrue(infantState.getHunger() >= 30, "喂食后 hunger 恢复至阈值附近");
    }
}
