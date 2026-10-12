package com.holzyn.actor.domain.growth.stage;

import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.evolution.PersonalityShiftService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpaceRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全生命周期阶段机核心单元测试（O6 轨道 G2）。
 * <p>职责：年龄派生（birth_game_second 权威）、出生反推（年龄列优先 → 档案提取 → 类型默认、
 * 幂等跳过）、死亡概率（阶段基线 × 健康修正 × 季节修正 × 机能修正，钳制 0-0.5）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DevelopmentalStageServiceTest {

    @Mock
    private ActorCharacterRepository characterRepository;
    @Mock
    private ActorNpcStateRepository stateRepository;
    @Mock
    private ActorCharacterRelationRepository relationRepository;
    @Mock
    private ActorWorldClockRepository worldClockRepository;
    @Mock
    private GrowthMilestoneRepository milestoneRepository;
    @Mock
    private GrowthSpaceRepository spaceRepository;
    @Mock
    private GrowthGateService growthGateService;
    @Mock
    private GrowthSpaceService growthSpaceService;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private AiSpringChatGateway aiChatGateway;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private UsageLogService usageLogService;
    @Mock
    private WorldEventBus worldEventBus;
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private PersonalityShiftService personalityShiftService;
    @Mock
    private com.holzyn.actor.domain.growth.legacy.LegacyService legacyService;
    @Mock
    private com.holzyn.actor.domain.growth.infant.InfantRevealService infantRevealService;
    @Mock
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub sandboxSseHub;

    private GrowthProperties properties;
    private DevelopmentalStageService service;

    @BeforeEach
    void setUp() {
        properties = new GrowthProperties();
        properties.getAging().setGameSecondsPerYear(31_536_000L);
        service = new DevelopmentalStageService(characterRepository, stateRepository, relationRepository,
                worldClockRepository, milestoneRepository, spaceRepository, growthGateService,
                growthSpaceService, properties, promptTemplateService, aiChatGateway,
                currentUserProvider, usageLogService, worldEventBus, agentMemoryService,
                personalityShiftService, legacyService, infantRevealService, sandboxSseHub);
    }

    private ActorCharacter ch(Long id, String name, String type, String detail, Integer age, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(1L);
        c.setName(name);
        c.setType(type);
        c.setDetail(detail);
        c.setAge(age);
        c.setStage(stage);
        return c;
    }

    // ==================== 年龄派生 ====================

    @Test
    @DisplayName("ageOf：由 birth_game_second 权威派生（向下取整）；缺失回退 age 列")
    void ageOfDerivation() {
        ActorCharacter c = ch(1L, "测试", "special", null, null, null);
        c.setBirthGameSecond(0L);
        // 0 岁基准 + 31,536,000 秒 = 1 游戏年
        assertEquals(1, service.ageOf(c, 31_536_000L));
        assertEquals(0, service.ageOf(c, 30_000_000L));
        assertEquals(30, service.ageOf(c, 30L * 31_536_000L));
        // birth 缺失 → 回退 age 列
        c.setBirthGameSecond(null);
        c.setAge(42);
        assertEquals(42, service.ageOf(c, 1000L));
        assertNull(service.ageOf(ch(2L, "无年龄", "special", null, null, null), 1000L));
    }

    // ==================== 出生反推 ====================

    @Test
    @DisplayName("出生反推：年龄列优先 + 阶段赋值；已反推幂等跳过")
    void backfillUsesAgeColumn() {
        ActorCharacter withAge = ch(10L, "有年龄", "common", null, 40, null);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(withAge));
        int done = service.backfillBirthSeconds(1L, 10L * 31_536_000L);
        assertEquals(1, done);
        assertEquals(10L * 31_536_000L - 40L * 31_536_000L, withAge.getBirthGameSecond());
        assertEquals(40, withAge.getAge());
        assertEquals("PRIME", withAge.getStage(), "40 岁 → 壮年期（30~45）");
    }

    @Test
    @DisplayName("出生反推：档案文本提取年龄（特殊 NPC 无 age 列）")
    void backfillExtractsFromDetail() {
        ActorCharacter c = ch(11L, "沈砚秋", "special", "青禾村村长，今年 62 岁", null, null);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(c));
        int done = service.backfillBirthSeconds(1L, 100L * 31_536_000L);
        assertEquals(1, done);
        assertEquals(62, c.getAge());
        assertEquals("ELDER", c.getStage());
    }

    @Test
    @DisplayName("出生反推：无任何来源 → 类型默认（special 30 / common 25）；已反推跳过")
    void backfillDefaultByType() {
        ActorCharacter special = ch(12L, "无档案", "special", null, null, null);
        ActorCharacter common = ch(13L, "无档案民", "common", null, null, null);
        ActorCharacter already = ch(14L, "已反推", "special", null, null, null);
        already.setBirthGameSecond(1000L);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(special, common, already));
        int done = service.backfillBirthSeconds(1L, 100L * 31_536_000L);
        assertEquals(2, done, "已反推的跳过");
        assertEquals(30, special.getAge());
        assertEquals(25, common.getAge());
        assertEquals(1000L, already.getBirthGameSecond(), "已反推不被覆盖");
    }

    @Test
    @DisplayName("出生反推：已故跳过（出生信息由谱系承载，G4 补全）")
    void backfillSkipsDeceased() {
        ActorCharacter deceased = ch(15L, "故人", "special", null, null, GrowthStage.DECEASED.name());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0)).thenReturn(List.of(deceased));
        int done = service.backfillBirthSeconds(1L, 100L * 31_536_000L);
        assertEquals(0, done);
        assertNull(deceased.getBirthGameSecond());
    }

    // ==================== 死亡概率 ====================

    @Test
    @DisplayName("死亡概率：耄耋基线最高，健康/季节/机能修正生效且钳制 0.5")
    void deathProbabilityCurve() {
        ActorCharacter old = ch(20L, "老人", "special", null, 80, GrowthStage.ANCIENT.name());
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:20")).thenReturn(Optional.of(new ActorNpcState()));
        when(growthGateService.capacityOf(80)).thenReturn(0.42);
        when(growthGateService.ageModifiers(0.42)).thenReturn(java.util.Map.of("diseaseRisk", 1.7));
        double p = service.deathProbability(old, 0L);
        // 基线 0.08 × 健康 1.0 × 季节 1.3（月份 0=一月）× 机能 1.7 = 0.1768
        assertEquals(0.1768, p, 1e-9);
        assertTrue(p <= 0.5, "钳制上限 0.5");

        ActorCharacter young = ch(21L, "青年", "special", null, 25, GrowthStage.YOUTH.name());
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:21")).thenReturn(Optional.of(new ActorNpcState()));
        when(growthGateService.capacityOf(25)).thenReturn(0.96);
        when(growthGateService.ageModifiers(0.96)).thenReturn(java.util.Map.of("diseaseRisk", 1.05));
        double py = service.deathProbability(young, 0L);
        // 基线 0.001 × 1.0 × 1.3 × 1.05 ≈ 0.001365
        assertEquals(0.001365, py, 1e-9);
        assertTrue(py < p, "青年死亡风险远低于耄耋");
    }

    @Test
    @DisplayName("死亡概率：健康告急放大风险（×1.5 权重）")
    void deathProbabilityHealthModifier() {
        ActorCharacter old = ch(22L, "病危老人", "special", null, 80, GrowthStage.ANCIENT.name());
        ActorNpcState state = new ActorNpcState();
        state.setHealth(100);
        when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:22")).thenReturn(Optional.of(state));
        when(growthGateService.capacityOf(80)).thenReturn(0.42);
        when(growthGateService.ageModifiers(0.42)).thenReturn(java.util.Map.of("diseaseRisk", 1.7));
        double healthy = service.deathProbability(old, 100_000L);
        state.setHealth(20);
        double sick = service.deathProbability(old, 100_000L);
        assertTrue(sick > healthy, "健康 20 风险高于健康 100");
        // 健康 100 → ×1.0；健康 20 → ×1.4（(100-20)/100×0.5+1）
        assertEquals(0.1768, healthy, 1e-9);
        assertEquals(0.1768 * 1.4, sick, 1e-9);
    }

    // ==================== 批5 世界与叙事：段1A+1C+2D+2E 前缀（2026-10-10） ====================

    /**
     * 阶段跨越叙事（growth_stage_transition_narrative）前置 V2.0 memoryChainContext 前缀
     * （段1A 世界观 + 段1C 角色卡 + 段2D 当前世界动态 + 段2E 当前状态）——
     * 叙事者知道此刻时节天气与该角色当下状态。
     */
    @Test
    @DisplayName("阶段跨越叙事前置 V2.0 memoryChainContext 前缀（段1A+1C+2D+2E）")
    void stageTransitionNarrative_prependsMemoryChainContextPrefix() throws Exception {
        ActorCharacter c = ch(1L, "沈砚秋", "special", null, 62, GrowthStage.PRIME.name());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(any(), eq(1L), eq("growth_stage_transition_narrative"), any()))
                .thenReturn("叙事 Prompt");
        when(promptTemplateService.systemMessage(any(), eq(1L), eq("growth_stage_transition_narrative"), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(any(), any(), org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn(new com.holzyn.actor.ai.AiChatResult("他迈过了那道门槛。", "test-model", 10, 5, 15, 1L));
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(1L, "npc:1", null))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋（62 岁）\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        String narrative = invokeStageTransitionNarrative(1L, c, "壮年", GrowthStage.ELDER);
        assertEquals("他迈过了那道门槛。", narrative);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(any(), any(), org.mockito.ArgumentMatchers.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("叙事 Prompt"), "模板 prompt 保留: " + user);
    }

    /**
     * 死亡叙事（growth_death_chronicle）前置 V2.0 memoryChainContext 前缀（段1A+1C+2D+2E）。
     */
    @Test
    @DisplayName("死亡叙事前置 V2.0 memoryChainContext 前缀（段1A+1C+2D+2E）")
    void deathChronicle_prependsMemoryChainContextPrefix() throws Exception {
        ActorCharacter c = ch(1L, "沈砚秋", "special", null, 88, GrowthStage.ANCIENT.name());
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(any(), eq(1L), eq("growth_death_chronicle"), any()))
                .thenReturn("丧事 Prompt");
        when(promptTemplateService.systemMessage(any(), eq(1L), eq("growth_death_chronicle"), any()))
                .thenReturn("");
        when(aiChatGateway.chatCompletion(any(), any(), org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn(new com.holzyn.actor.ai.AiChatResult("他安详地睡去了。", "test-model", 10, 5, 15, 1L));
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                org.mockito.Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(1L, "npc:1", null))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋（88 岁）\n"
                        + "【当前世界动态】青禾村历 0420年 冬 · 天气阴\n【当前状态】\n- 健康：垂危");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", inj);

        String narrative = invokeDeathChronicle(1L, c, "寿终正寝");
        assertEquals("他安详地睡去了。", narrative);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(any(), any(), org.mockito.ArgumentMatchers.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("丧事 Prompt"), "模板 prompt 保留: " + user);
    }

    private String invokeStageTransitionNarrative(Long projectId, ActorCharacter ch, String fromStage,
                                                  GrowthStage toStage) throws Exception {
        java.lang.reflect.Method m = DevelopmentalStageService.class.getDeclaredMethod(
                "stageTransitionNarrative", Long.class, ActorCharacter.class, String.class, GrowthStage.class);
        m.setAccessible(true);
        return (String) m.invoke(service, projectId, ch, fromStage, toStage);
    }

    private String invokeDeathChronicle(Long projectId, ActorCharacter ch, String reason) throws Exception {
        java.lang.reflect.Method m = DevelopmentalStageService.class.getDeclaredMethod(
                "deathChronicle", Long.class, ActorCharacter.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, projectId, ch, reason);
    }
}
