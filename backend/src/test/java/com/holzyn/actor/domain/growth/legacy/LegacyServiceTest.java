package com.holzyn.actor.domain.growth.legacy;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 身后事 · 遗产转移单元测试（O6 G4，Q8：文本级遗产转移 + 继承顺序 + 族中公产兜底）。
 */
@ExtendWith(MockitoExtension.class)
class LegacyServiceTest {

    @Mock private ActorCharacterRepository characterRepository;
    @Mock private LineageService lineageService;
    @Mock private PersonaService personaService;
    @Mock private GrowthMilestoneRepository milestoneRepository;
    @Mock private AgentMemoryService agentMemoryService;
    @Mock private WorldEventBus worldEventBus;
    @Mock private CurrentUserProvider currentUserProvider;

    private LegacyService service;
    private ActorCharacter deceased;

    @BeforeEach
    void setUp() {
        GrowthProperties props = new GrowthProperties();
        service = new LegacyService(characterRepository, lineageService, personaService, milestoneRepository,
                agentMemoryService, worldEventBus, currentUserProvider, props);
        deceased = ch(1L, "沈老族长", "男", "ELDER");
        // 亡者档案带财产
        PersonaProfile p = PersonaProfile.empty(1L, 1L);
        PersonaDims.Identity identity = new PersonaDims.Identity();
        PersonaDims.Property prop = new PersonaDims.Property();
        prop.setHouse("祖宅一座");
        prop.setLandMu(32.0);
        prop.setHeirlooms(List.of("传家印信"));
        identity.setProperty(prop);
        p.getDims().setIdentity(identity);
        lenient().when(personaService.profileOf(1L)).thenReturn(p);
        lenient().when(milestoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ActorCharacter ch(Long id, String name, String gender, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setGender(gender);
        c.setStage(stage);
        c.setProjectId(1L);
        return c;
    }

    @Test
    @DisplayName("Q8：配偶在世 → 遗产转配偶（档案 property 追加 + 记忆 + 里程碑）")
    void transferToSpouse() {
        ActorCharacter spouse = ch(2L, "柳氏", "女", "MIDDLE");
        when(lineageService.spouseOf(1L, "npc:1")).thenReturn(Optional.of("npc:2"));
        when(lineageService.alive(1L, "npc:2")).thenReturn(true);
        when(characterRepository.findById(2L)).thenReturn(Optional.of(spouse));
        // 受让人档案（空档案；appendLegacyToHeir 走 profileOrEmpty）
        when(personaService.profileOrEmpty(2L)).thenReturn(PersonaProfile.empty(1L, 2L));
        service.transferOnDeath(1L, deceased, 1000L);
        verify(agentMemoryService).recordObservation(anyLong(), anyString(), anyString(), any());
        // 受让人档案写回（savePersona 被调用）
        verify(personaService).savePersona(anyLong(), any(PersonaProfile.class), anyString());
    }

    @Test
    @DisplayName("Q8：配偶已故 → 长子/长女继承（谱系 childrenOf 顺序）")
    void transferToEldestChild() {
        when(lineageService.spouseOf(1L, "npc:1")).thenReturn(Optional.of("npc:2"));
        when(lineageService.alive(1L, "npc:2")).thenReturn(false);
        ActorCharacter child = ch(3L, "沈砚舟", "男", "PRIME");
        when(lineageService.childrenOf(1L, "npc:1")).thenReturn(List.of("npc:3", "npc:4"));
        when(lineageService.alive(1L, "npc:3")).thenReturn(true);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(child));
        when(personaService.profileOrEmpty(3L)).thenReturn(PersonaProfile.empty(1L, 3L));
        service.transferOnDeath(1L, deceased, 1000L);
        verify(personaService).savePersona(anyLong(), any(PersonaProfile.class), anyString());
    }

    @Test
    @DisplayName("Q8：无在世血亲 → 族中公产（不落个人，事件留痕）")
    void transferToClanWhenNoHeir() {
        when(lineageService.spouseOf(1L, "npc:1")).thenReturn(Optional.empty());
        when(lineageService.childrenOf(1L, "npc:1")).thenReturn(List.of());
        when(lineageService.parentEdgesOf(1L, "npc:1")).thenReturn(List.of());
        service.transferOnDeath(1L, deceased, 1000L);
        verify(personaService, never()).savePersona(anyLong(), any(PersonaProfile.class), anyString());
        verify(worldEventBus).publish(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("Q8：亡者无财产 → 零动作（零阻断）")
    void noPropertyNoTransfer() {
        PersonaProfile empty = PersonaProfile.empty(1L, 1L);
        when(personaService.profileOf(1L)).thenReturn(empty);
        service.transferOnDeath(1L, deceased, 1000L);
        verify(worldEventBus, never()).publish(anyLong(), anyLong(), any());
        verify(personaService, never()).savePersona(anyLong(), any(PersonaProfile.class), anyString());
    }

    @Test
    @DisplayName("Q8：遗产文本提取正确（房/田/粮/钱/传家物拼装并写入受让人档案）")
    void propertyTextAssembled() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(9L);
        ch.setProjectId(1L);
        PersonaProfile p = PersonaProfile.empty(1L, 9L);
        PersonaDims.Identity identity = new PersonaDims.Identity();
        PersonaDims.Property prop = new PersonaDims.Property();
        prop.setHouse("铺面一间");
        prop.setMoneyGuan(12.0);
        identity.setProperty(prop);
        p.getDims().setIdentity(identity);
        when(personaService.profileOf(9L)).thenReturn(p);
        when(lineageService.spouseOf(1L, "npc:9")).thenReturn(Optional.of("npc:8"));
        when(lineageService.alive(1L, "npc:8")).thenReturn(true);
        ActorCharacter heir = ch(8L, "继承人", "女", "PRIME");
        when(characterRepository.findById(8L)).thenReturn(Optional.of(heir));
        when(personaService.profileOrEmpty(8L)).thenReturn(PersonaProfile.empty(1L, 8L));
        service.transferOnDeath(1L, ch, 1000L);
        // 捕获传给 savePersona 的档案，断言遗产文本写入 property.heirlooms
        org.mockito.ArgumentCaptor<PersonaProfile> captor =
                org.mockito.ArgumentCaptor.forClass(PersonaProfile.class);
        verify(personaService).savePersona(org.mockito.ArgumentMatchers.eq(8L), captor.capture(),
                org.mockito.ArgumentMatchers.eq("inheritance"));
        PersonaProfile saved = captor.getValue();
        assertNotNull(saved.getDims().getIdentity().getProperty());
        assertTrue(saved.getDims().getIdentity().getProperty().getHeirlooms().stream()
                .anyMatch(h -> h.contains("铺面一间")), "受让人档案应含遗产条目：" + saved.getDims().getIdentity().getProperty().getHeirlooms());
    }
}
