package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.evolution.PersonalityShiftService;
import com.holzyn.actor.domain.growth.infant.InfantRevealService;
import com.holzyn.actor.domain.growth.legacy.LegacyService;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineageRepository;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpaceRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.growth.stage.DevelopmentalStageService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 婚配→死亡/离异→再恋爱场景编排测试（治理批次 0 · 卡5，{@code domain/growth/romance} 同包）。
 *
 * <p><b>与 MarriageServiceTest 的区别</b>：MarriageServiceTest 用 {@code @Mock LineageService}；
 * 本类用<b>真实 LineageService</b>（内存边列表）编排「婚配→死亡→再恋爱」真实链路
 * （G-REMARRY-01），以及「婚配→离婚→再恋爱」链路（G-DIVORCE-01，离婚入口为卡5-方案A ②新增）。</p>
 *
 * <p>测试范式：手工构造 MarriageService / DevelopmentalStageService / LineageService +
 * 内存边表/关系表 fake；AI 判定源全 mock 并 stub「允许」；无真实 AI 调用。</p>
 */
class RemarriageScenarioTest {

    private static final long DAY = 86400L;

    /** 内存谱系边表（照 LineageServiceTest 范式） */
    private final List<ActorGrowthLineage> edges = new ArrayList<>();
    /** 内存关系表 */
    private final List<ActorCharacterRelation> relations = new ArrayList<>();

    private ActorCharacterRepository characterRepository;
    private ActorCharacterRelationRepository relationRepository;
    private LineageService lineageService;
    private MarriageService marriageService;
    private DevelopmentalStageService stageService;
    private RomanceDecisionSource romanceDecisionSource;

    private ActorCharacter male;
    private ActorCharacter female;
    private ActorCharacter candidate;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ActorNpcStateRepository stateRepository = Mockito.mock(ActorNpcStateRepository.class);
        relationRepository = Mockito.mock(ActorCharacterRelationRepository.class);
        ActorWorldClockRepository worldClockRepository = Mockito.mock(ActorWorldClockRepository.class);
        ActorGrowthLineageRepository lineageRepository = Mockito.mock(ActorGrowthLineageRepository.class);
        GrowthMilestoneRepository milestoneRepository = Mockito.mock(GrowthMilestoneRepository.class);
        GrowthSpaceRepository spaceRepository = Mockito.mock(GrowthSpaceRepository.class);
        GrowthGateService growthGateService = Mockito.mock(GrowthGateService.class);
        GrowthSpaceService growthSpaceService = Mockito.mock(GrowthSpaceService.class);
        PromptTemplateService promptTemplateService = Mockito.mock(PromptTemplateService.class);
        AiSpringChatGateway aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        UsageLogService usageLogService = Mockito.mock(UsageLogService.class);
        WorldEventBus worldEventBus = Mockito.mock(WorldEventBus.class);
        AgentMemoryService agentMemoryService = Mockito.mock(AgentMemoryService.class);
        PersonalityShiftService personalityShiftService = Mockito.mock(PersonalityShiftService.class);
        LegacyService legacyService = Mockito.mock(LegacyService.class);
        InfantRevealService infantRevealService = Mockito.mock(InfantRevealService.class);
        WorldSandboxSseHub sandboxSseHub = Mockito.mock(WorldSandboxSseHub.class);
        ProposalDecisionSource proposalDecisionSource = Mockito.mock(ProposalDecisionSource.class);
        FertilityDecisionSource fertilityDecisionSource = Mockito.mock(FertilityDecisionSource.class);
        PregnancyService pregnancyService = Mockito.mock(PregnancyService.class);
        AiDecisionSourceSupport aiSupport = Mockito.mock(AiDecisionSourceSupport.class);
        ActorProjectRepository projectRepository = Mockito.mock(ActorProjectRepository.class);

        GrowthProperties props = new GrowthProperties();
        // ===== 真实 LineageService（内存边表）=====
        lineageService = new LineageService(lineageRepository, characterRepository, props);
        lenient().when(lineageRepository.save(any(ActorGrowthLineage.class))).thenAnswer(inv -> {
            ActorGrowthLineage e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId((long) (edges.size() + 1));
            }
            edges.add(e);
            return e;
        });
        lenient().when(lineageRepository.findByProjectIdOrderByIdAsc(1L)).thenReturn(edges);
        lenient().when(lineageRepository.findByProjectIdAndFromRefOrderByIdAsc(any(), any())).thenAnswer(inv ->
                edges.stream().filter(e -> e.getFromRef().equals(inv.getArgument(1))).toList());
        lenient().when(lineageRepository.findByProjectIdAndToRefOrderByIdAsc(any(), any())).thenAnswer(inv ->
                edges.stream().filter(e -> e.getToRef().equals(inv.getArgument(1))).toList());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndFromRefOrderByIdAsc(any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                        && e.getFromRef().equals(inv.getArgument(2))).toList());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndToRefOrderByIdAsc(any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                        && e.getToRef().equals(inv.getArgument(2))).toList());
        lenient().when(lineageRepository.findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(
                        any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getFromRef().equals(inv.getArgument(2))
                                && Integer.valueOf(1).equals(e.getActive()))
                        .findFirst());
        lenient().when(lineageRepository.findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(
                        any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getToRef().equals(inv.getArgument(2))
                                && Integer.valueOf(1).equals(e.getActive()))
                        .findFirst());
        lenient().when(lineageRepository.findByProjectIdAndEdgeTypeAndFromRefAndToRef(any(), any(), any(), any()))
                .thenAnswer(inv -> edges.stream().filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getFromRef().equals(inv.getArgument(2))
                                && e.getToRef().equals(inv.getArgument(3)))
                        .toList());

        // ===== 角色 =====
        male = ch(1L, "沈青禾", "男", 25, GrowthStage.YOUTH.name());
        female = ch(2L, "白小满", "女", 22, GrowthStage.YOUTH.name());
        candidate = ch(3L, "李翠花", "女", 20, GrowthStage.YOUTH.name());
        lenient().when(characterRepository.findById(1L)).thenReturn(Optional.of(male));
        lenient().when(characterRepository.findById(2L)).thenReturn(Optional.of(female));
        lenient().when(characterRepository.findById(3L)).thenReturn(Optional.of(candidate));
        lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(male, female, candidate));
        lenient().when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));

        // ===== 关系表（内存）=====
        lenient().when(relationRepository.findByProjectId(1L)).thenReturn(relations);
        lenient().when(relationRepository.save(any(ActorCharacterRelation.class))).thenAnswer(inv -> {
            ActorCharacterRelation r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) (relations.size() + 1));
            }
            relations.removeIf(x -> x.getId().equals(r.getId()));
            relations.add(r);
            return r;
        });
        lenient().doAnswer(inv -> {
            relations.removeAll(inv.getArgument(0));
            return null;
        }).when(relationRepository).deleteAll(any());

        // ===== 状态/里程碑/空间仓库（executeDeath 走查必需）=====
        lenient().when(stateRepository.findByProjectIdAndNpcRef(any(), any())).thenReturn(Optional.empty());
        lenient().when(spaceRepository.findByProjectIdAndNpcRef(any(), any())).thenReturn(Optional.empty());
        lenient().when(milestoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // ===== 真实 MarriageService（lineageService=真实）=====
        romanceDecisionSource = Mockito.mock(RomanceDecisionSource.class);
        lenient().when(romanceDecisionSource.decide(any()))
                .thenReturn(new RomanceDecisions.AttractionDecision(true, 0.9, "心动", "test"));
        marriageService = new MarriageService(characterRepository, stateRepository, relationRepository,
                worldClockRepository, lineageRepository, milestoneRepository, lineageService, props,
                romanceDecisionSource, proposalDecisionSource, fertilityDecisionSource, pregnancyService,
                agentMemoryService, worldEventBus, currentUserProvider, promptTemplateService,
                usageLogService, aiSupport, projectRepository);

        // ===== 真实 DevelopmentalStageService =====
        stageService = new DevelopmentalStageService(characterRepository, stateRepository, relationRepository,
                worldClockRepository, milestoneRepository, spaceRepository, growthGateService,
                growthSpaceService, props, promptTemplateService, aiChatGateway,
                currentUserProvider, usageLogService, worldEventBus, agentMemoryService,
                personalityShiftService, legacyService, infantRevealService, sandboxSseHub);
        // 卡5：谱系服务字段注入（executeDeath ⑦ 婚姻终止；字段注入不改构造器）
        org.springframework.test.util.ReflectionTestUtils.setField(stageService, "lineageService", lineageService);
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

    /** MARRIAGE 关系行（婚礼升格语义的双向行之一）。 */
    private ActorCharacterRelation marriageRow(Long id, Long fromId, String fromRef, String fromName,
                                               Long toId, String toRef, String toName) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setId(id);
        r.setProjectId(1L);
        r.setFromCharacterId(fromId);
        r.setFromRef(fromRef);
        r.setFromName(fromName);
        r.setToCharacterId(toId);
        r.setToRef(toRef);
        r.setToName(toName);
        r.setRelationType("MARRIAGE");
        r.setRelationState("intimate");
        return r;
    }

    /** 好感达标的关系行（心动检查候选）。 */
    private ActorCharacterRelation acquaintanceRow(Long id, Long fromId, String fromRef,
                                                   Long toId, String toRef, int affinity) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setId(id);
        r.setProjectId(1L);
        r.setFromCharacterId(fromId);
        r.setFromRef(fromRef);
        r.setFromName("沈青禾");
        r.setToCharacterId(toId);
        r.setToRef(toRef);
        r.setToName("李翠花");
        r.setRelationType("acquaintance");
        r.setAffinity(affinity);
        r.setTrust(60);
        r.setRelationState("friend");
        r.setDescription("老相识");
        return r;
    }

    /**
     * G-REMARRY-01（卡5 修复断言，起始 FAIL）：丧偶后婚姻边终止 + 可再发起恋爱。
     * <p>现状错误值：executeDeath 不调 terminateMarriage（L89 零调用方）且 spouseOf 无 alive 过滤 →
     * 婚姻边 active 仍 1 → romanceEligible=false → 丧偶者终身不能再婚。</p>
     */
    @Test
    @DisplayName("G-REMARRY-01: 丧偶后婚姻边终止 + 可再婚（自然死亡/处死共用 executeDeath 路径）")
    void widow_canCourtAgain_afterSpouseDeath() {
        // ① 婚配（谱系婚姻边 + 关系表 MARRIAGE 双向行）
        lineageService.marry(1L, "npc:1", "npc:2", 1000L);
        relations.add(marriageRow(1L, 1L, "npc:1", "沈青禾", 2L, "npc:2", "白小满"));
        relations.add(marriageRow(2L, 2L, "npc:2", "白小满", 1L, "npc:1", "沈青禾"));
        // ② 配偶死亡（executeDeath 真实路径）
        stageService.executeDeath(1L, female, 2000L, "测试处死");
        // ③ 硬断言①：婚姻边终止（源头）
        assertTrue(lineageService.spouseOf(1L, "npc:1").isEmpty(), "丧偶后配偶查询为空");
        assertEquals(0, edges.get(0).getActive(), "丧偶后婚姻边 active=0（历史保留）");
        assertFalse(lineageService.isMarried(1L, "npc:1"), "丧偶后不再已婚");
        // ④ 硬断言②：关系表无 MARRIAGE 行指向亡偶
        assertTrue(relations.stream().noneMatch(r -> "MARRIAGE".equals(r.getRelationType())
                        && ("npc:2".equals(r.getFromRef()) || "npc:2".equals(r.getToRef()))),
                "关系表 MARRIAGE 行不得指向亡偶");
        // ⑤ 硬断言③（行为断言）：丧偶者可对适龄对象再发起恋爱（心动检查创建 ROMANTIC 边）
        relations.add(acquaintanceRow(3L, 1L, "npc:1", 3L, "npc:3", 80));
        marriageService.checkAttractions(1L, 3000L);
        assertTrue(relations.stream().anyMatch(r -> "ROMANTIC".equals(r.getRelationType())
                        && "npc:1".equals(r.getFromRef()) && "npc:3".equals(r.getToRef())),
                "丧偶者可再发起恋爱（心动检查 → ROMANTIC 边）");
    }

    /**
     * G-DIVORCE-01（卡5 修复断言，起始 FAIL）：离异后婚姻边终止 + 可再婚。
     * <p>离婚入口此前不存在（RelationDynamicsService 状态机/关系 CRUD 均无离婚操作）——卡5-方案 A ②
     * 新增 {@link MarriageService#divorce} 为离婚入口（婚姻域服务）。现状错误值：离婚只改 relation
     * 状态（或什么都不改），lineage 边 active 仍 1 → romanceEligible=false。</p>
     */
    @Test
    @DisplayName("G-DIVORCE-01: 离异后婚姻边终止 + 可再婚（离婚入口=MarriageService.divorce）")
    void divorcee_canCourtAgain_afterDivorce() {
        // ① 婚配（配偶存活）
        lineageService.marry(1L, "npc:1", "npc:2", 4000L);
        relations.add(marriageRow(1L, 1L, "npc:1", "沈青禾", 2L, "npc:2", "白小满"));
        relations.add(marriageRow(2L, 2L, "npc:2", "白小满", 1L, "npc:1", "沈青禾"));
        // ② 离婚入口（卡5-方案 A ② 新增）
        marriageService.divorce(1L, "npc:1", "npc:2", 5000L);
        // ③ 硬断言：配偶查询为空 + 婚姻边 active=0
        assertTrue(lineageService.spouseOf(1L, "npc:1").isEmpty(), "离异后配偶查询为空");
        assertEquals(0, edges.get(0).getActive(), "离异后婚姻边 active=0（历史保留）");
        assertFalse(lineageService.isMarried(1L, "npc:1"), "离异后不再已婚");
        assertTrue(relations.stream().noneMatch(r -> "MARRIAGE".equals(r.getRelationType())
                        && (( "npc:1".equals(r.getFromRef()) && "npc:2".equals(r.getToRef()))
                        || ("npc:2".equals(r.getFromRef()) && "npc:1".equals(r.getToRef())))),
                "关系表 MARRIAGE 双向行已终止");
        // ④ 硬断言（行为断言）：离异者可再发起恋爱
        relations.add(acquaintanceRow(3L, 1L, "npc:1", 3L, "npc:3", 80));
        marriageService.checkAttractions(1L, 6000L);
        assertTrue(relations.stream().anyMatch(r -> "ROMANTIC".equals(r.getRelationType())
                        && "npc:1".equals(r.getFromRef()) && "npc:3".equals(r.getToRef())),
                "离异者可再发起恋爱（心动检查 → ROMANTIC 边）");
    }
}
