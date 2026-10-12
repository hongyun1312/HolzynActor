package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineageRepository;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 婚育流程编排单元测试（O6 G4：Q1 混合引擎 / Q6 三插拔点编排）。
 *
 * <p>覆盖：① 心动检查（AI 判定 → ROMANTIC 边）；② 双向恋爱；③ 求婚检查（恋爱满 30 游戏日 →
 * 婚礼：谱系婚姻边 + 关系升格）；④ 生育意愿检查（门控 + AI 判定 → 受孕）；⑤ 冷却与周期门控。</p>
 */
@ExtendWith(MockitoExtension.class)
class MarriageServiceTest {

    private static final long DAY = 86400L;

    @Mock private ActorCharacterRepository characterRepository;
    @Mock private ActorNpcStateRepository stateRepository;
    @Mock private ActorCharacterRelationRepository relationRepository;
    @Mock private ActorWorldClockRepository worldClockRepository;
    @Mock private ActorGrowthLineageRepository lineageRepository;
    @Mock private GrowthMilestoneRepository milestoneRepository;
    @Mock private LineageService lineageService;
    @Mock private RomanceDecisionSource romanceDecisionSource;
    @Mock private ProposalDecisionSource proposalDecisionSource;
    @Mock private FertilityDecisionSource fertilityDecisionSource;
    @Mock private PregnancyService pregnancyService;
    @Mock private AgentMemoryService agentMemoryService;
    @Mock private WorldEventBus worldEventBus;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private UsageLogService usageLogService;
    @Mock private AiDecisionSourceSupport aiSupport;
    @Mock private com.holzyn.actor.domain.project.repository.ActorProjectRepository projectRepository;

    private MarriageService service;
    private final List<ActorCharacterRelation> relations = new ArrayList<>();
    private final List<ActorGrowthLineage> lineageEdges = new ArrayList<>();

    private ActorCharacter male;
    private ActorCharacter female;

    @BeforeEach
    void setUp() {
        GrowthProperties props = new GrowthProperties();
        service = new MarriageService(characterRepository, stateRepository, relationRepository,
                worldClockRepository, lineageRepository, milestoneRepository, lineageService, props,
                romanceDecisionSource, proposalDecisionSource, fertilityDecisionSource, pregnancyService,
                agentMemoryService, worldEventBus, currentUserProvider, promptTemplateService,
                usageLogService, aiSupport, projectRepository);
        male = ch(1L, "沈青禾", "男", 25, GrowthStage.YOUTH.name());
        female = ch(2L, "白小满", "女", 22, GrowthStage.YOUTH.name());
        relations.clear();
        lineageEdges.clear();
        lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(male, female));
        lenient().when(relationRepository.findByProjectId(1L)).thenReturn(relations);
        lenient().when(lineageRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(lineageEdges);
        lenient().when(lineageService.isMarried(anyLong(), anyString())).thenReturn(false);
        lenient().when(lineageService.isBloodRelated(anyLong(), anyString(), anyString())).thenReturn(false);
        lenient().when(lineageService.spouseOf(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(lineageService.childrenOf(anyLong(), anyString())).thenReturn(List.of());
        lenient().when(lineageService.parentEdgesOf(anyLong(), anyString())).thenReturn(List.of());
        lenient().when(lineageService.marry(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    ActorGrowthLineage e = new ActorGrowthLineage();
                    e.setId((long) (lineageEdges.size() + 1));
                    e.setProjectId(1L);
                    e.setEdgeType(ActorGrowthLineage.EDGE_MARRIAGE);
                    e.setFromRef(inv.getArgument(1));
                    e.setToRef(inv.getArgument(2));
                    e.setActive(1);
                    lineageEdges.add(e);
                    return e;
                });
        lenient().when(characterRepository.findById(1L)).thenReturn(Optional.of(male));
        lenient().when(characterRepository.findById(2L)).thenReturn(Optional.of(female));
        lenient().when(stateRepository.findByProjectIdAndNpcRef(anyLong(), anyString()))
                .thenReturn(Optional.of(state(female)));
        lenient().when(milestoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ActorCharacter ch(Long id, String name, String gender, int age, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setGender(gender);
        c.setAge(age);
        c.setStage(stage);
        c.setProjectId(1L);
        return c;
    }

    private ActorNpcState state(ActorCharacter c) {
        ActorNpcState s = new ActorNpcState();
        s.setProjectId(1L);
        s.setNpcRef(c.npcRef());
        return s;
    }

    private ActorCharacterRelation relation(ActorCharacter from, ActorCharacter to, int affinity) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(1L);
        r.setFromCharacterId(from.getId());
        r.setToCharacterId(to.getId());
        r.setFromRef(from.npcRef());
        r.setToRef(to.npcRef());
        r.setFromName(from.getName());
        r.setToName(to.getName());
        r.setAffinity(affinity);
        r.setTrust(50);
        return r;
    }

    @Test
    @DisplayName("① 心动检查：好感达标 + AI 判定心动 → ROMANTIC 边落库")
    void attractionCreatesRomanceEdge() {
        relations.add(relation(male, female, 70));
        when(romanceDecisionSource.decide(any())).thenReturn(
                RomanceDecisions.AttractionDecision.of(true, 0.8, "两小无猜", "ai"));
        service.dailyCheck(1L, 10_000 * DAY);
        assertTrue(relations.stream().anyMatch(r -> "ROMANTIC".equals(r.getRelationType())
                && "npc:1".equals(r.getFromRef()) && "npc:2".equals(r.getToRef())),
                "心动 → ROMANTIC 边");
    }

    @Test
    @DisplayName("① 心动门控：好感不足 → 无 AI 判定（程序化预筛，零 AI 调用）")
    void attractionSkipsLowAffinity() {
        relations.add(relation(male, female, 30));
        service.dailyCheck(1L, 10_000 * DAY);
        verify(romanceDecisionSource, never()).decide(any());
        assertTrue(relations.stream().noneMatch(r -> "ROMANTIC".equals(r.getRelationType())));
    }

    @Test
    @DisplayName("① 心动门控：三代内血亲 → 无 AI 判定（禁婚硬门控，双向对称）")
    void attractionSkipsBloodRelative() {
        relations.add(relation(male, female, 80));
        // 血亲判定对称（男方视角与女方视角都拦）
        when(lineageService.isBloodRelated(1L, "npc:1", "npc:2")).thenReturn(true);
        when(lineageService.isBloodRelated(1L, "npc:2", "npc:1")).thenReturn(true);
        service.dailyCheck(1L, 10_000 * DAY);
        verify(romanceDecisionSource, never()).decide(any());
    }

    @Test
    @DisplayName("① 心动冷却：7 游戏日内已判定过 → 跳过（不重复判定）")
    void attractionCooldown() {
        ActorCharacterRelation rel = relation(male, female, 80);
        // 上次判定 = 100,000 游戏日；在 100,000+5 游戏日检查 → 5 < 7 冷却 → 跳过
        rel.setDescription("{\"romance_state\":\"courting\",\"state_game_second\":0,"
                + "\"last_check_game_second\":" + (100_000 * DAY) + "}");
        relations.add(rel);
        service.dailyCheck(1L, 100_000 * DAY + 5 * DAY);
        verify(romanceDecisionSource, never()).decide(any());
        // 冷却过后（+10 游戏日）→ 恢复判定
        service.dailyCheck(1L, 100_000 * DAY + 10 * DAY);
        verify(romanceDecisionSource, org.mockito.Mockito.atLeastOnce()).decide(any());
    }

    @Test
    @DisplayName("②③ 双向恋爱满 30 游戏日 + 求婚成功 → 婚礼（谱系婚姻边 + MARRIAGE 关系）")
    void proposalAndWedding() {
        // 双向 ROMANTIC（恋爱 40 游戏日）
        ActorCharacterRelation a2b = relation(male, female, 80);
        a2b.setRelationType("ROMANTIC");
        a2b.setDescription("{\"romance_state\":\"courting\",\"state_game_second\":0,\"last_check_game_second\":0}");
        ActorCharacterRelation b2a = relation(female, male, 80);
        b2a.setRelationType("ROMANTIC");
        b2a.setDescription("{\"romance_state\":\"courting\",\"state_game_second\":0,\"last_check_game_second\":0}");
        relations.add(a2b);
        relations.add(b2a);
        when(proposalDecisionSource.decide(any())).thenReturn(
                RomanceDecisions.ProposalDecision.of(true, "npc:2", "感情深厚", "ai"));
        when(lineageService.spouseOf(anyLong(), anyString())).thenReturn(Optional.empty());
        service.dailyCheck(1L, 40 * DAY);
        // 谱系婚姻边
        assertEquals(1, lineageEdges.size());
        assertEquals("npc:1", lineageEdges.get(0).getFromRef());
        assertEquals("npc:2", lineageEdges.get(0).getToRef());
        // 关系升格 MARRIAGE（双向）
        assertTrue(relations.stream().anyMatch(r -> "MARRIAGE".equals(r.getRelationType())
                && "npc:1".equals(r.getFromRef())));
        assertTrue(relations.stream().anyMatch(r -> "MARRIAGE".equals(r.getRelationType())
                && "npc:2".equals(r.getFromRef())));
    }

    @Test
    @DisplayName("③ 求婚门控：恋爱未满 30 游戏日 → 不判定求婚")
    void proposalRequiresRomanceDuration() {
        ActorCharacterRelation a2b = relation(male, female, 80);
        a2b.setRelationType("ROMANTIC");
        a2b.setDescription("{\"romance_state\":\"courting\",\"state_game_second\":0,\"last_check_game_second\":null}");
        ActorCharacterRelation b2a = relation(female, male, 80);
        b2a.setRelationType("ROMANTIC");
        b2a.setDescription("{\"romance_state\":\"courting\",\"state_game_second\":0,\"last_check_game_second\":null}");
        relations.add(a2b);
        relations.add(b2a);
        service.dailyCheck(1L, 10 * DAY); // 仅恋爱 10 游戏日
        verify(proposalDecisionSource, never()).decide(any());
        assertTrue(lineageEdges.isEmpty());
    }

    @Test
    @DisplayName("④ 生育意愿：婚后满 30 游戏日 + 门控过 + AI 想生 → 受孕")
    void fertilityStartsPregnancy() {
        // 已婚（谱系边 + 无孕 + 冷却已过）
        ActorGrowthLineage marriage = new ActorGrowthLineage();
        marriage.setId(1L);
        marriage.setProjectId(1L);
        marriage.setEdgeType(ActorGrowthLineage.EDGE_MARRIAGE);
        marriage.setFromRef("npc:1");
        marriage.setToRef("npc:2");
        marriage.setGameSecond(0L);
        marriage.setActive(1);
        lineageEdges.add(marriage);
        when(lineageService.spouseOf(1L, "npc:2")).thenReturn(Optional.of("npc:1"));
        when(lineageService.childrenOf(anyLong(), anyString())).thenReturn(List.of());
        when(fertilityDecisionSource.decide(any())).thenReturn(
                RomanceDecisions.FertilityDecision.of(true, "添丁兴旺", "ai"));
        service.dailyCheck(1L, 40 * DAY);
        verify(pregnancyService).startPregnancy(1L, "npc:2", "npc:1", 40 * DAY);
    }

    @Test
    @DisplayName("④ 生育门控：生育冷却 180 游戏日内 → 不判定")
    void fertilityCooldown() {
        ActorGrowthLineage marriage = new ActorGrowthLineage();
        marriage.setId(1L);
        marriage.setProjectId(1L);
        marriage.setEdgeType(ActorGrowthLineage.EDGE_MARRIAGE);
        marriage.setFromRef("npc:1");
        marriage.setToRef("npc:2");
        marriage.setGameSecond(0L);
        marriage.setActive(1);
        lineageEdges.add(marriage);
        when(lineageService.spouseOf(1L, "npc:2")).thenReturn(Optional.of("npc:1"));
        lenient().when(lineageService.childrenOf(anyLong(), anyString())).thenReturn(List.of());
        ActorNpcState st = state(female);
        st.setLastBirthGameSecond(20 * DAY); // 20 游戏日前刚分娩
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:2")).thenReturn(Optional.of(st));
        service.dailyCheck(1L, 40 * DAY);
        verify(fertilityDecisionSource, never()).decide(any());
    }

    @Test
    @DisplayName("④ 生育门控：人口上限 → 不判定")
    void fertilityPopulationCap() {
        ActorGrowthLineage marriage = new ActorGrowthLineage();
        marriage.setId(1L);
        marriage.setProjectId(1L);
        marriage.setEdgeType(ActorGrowthLineage.EDGE_MARRIAGE);
        marriage.setFromRef("npc:1");
        marriage.setToRef("npc:2");
        marriage.setGameSecond(0L);
        marriage.setActive(1);
        lineageEdges.add(marriage);
        lenient().when(lineageService.spouseOf(1L, "npc:2")).thenReturn(Optional.of("npc:1"));
        lenient().when(lineageService.childrenOf(anyLong(), anyString())).thenReturn(List.of());
        // 造 50 个在世 NPC（人口上限 50）
        List<ActorCharacter> crowd = new ArrayList<>();
        for (long i = 100; i < 150; i++) {
            crowd.add(ch(i, "村民" + i, "男", 30, GrowthStage.PRIME.name()));
        }
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(crowd);
        service.dailyCheck(1L, 40 * DAY);
        verify(fertilityDecisionSource, never()).decide(any());
    }
}
