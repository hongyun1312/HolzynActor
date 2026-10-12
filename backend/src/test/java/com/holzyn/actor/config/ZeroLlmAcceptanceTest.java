package com.holzyn.actor.config;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.brain.strategy.DecisionContext;
import com.holzyn.actor.domain.agent.brain.strategy.DevelopmentalGatingStrategy;
import com.holzyn.actor.domain.agent.brain.strategy.NpcKind;
import com.holzyn.actor.domain.agent.brain.strategy.SelfCheckStrategy;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.learning.ReflectionService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.agent.thinking.DecisionService;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.infant.InfantBehaviorService;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.physio.PhysioNeedService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 零 LLM 验收测试组（Zero-Llm Acceptance，P1 ③-2）。
 *
 * <p><b>核心语义</b>：即使 AI 网关全部不可用（mock 全失败），世界核心行为路径仍
 * <b>正确运转</b>——「无 AI 也正确」的程序化基座验收。与 {@link AiCallOutsideTransactionTest}
 * 同属跨域架构约束回归：前者守「AI 调用不得在事务内」，本组守「核心路径不依赖 AI 也能产出正确结果」。</p>
 *
 * <p><b>验收方法</b>：每项用例注入「AI 全失败」网关（所有 chat 调用抛
 * {@link AiCallException}），执行程序化路径，断言①产出正确 ②零 AI 调用发生
 * （{@code verify(gateway, never())} 或调用计数不增长）。</p>
 *
 * <p><b>9 项覆盖点</b>：①决策链 ProgrammaticFallback 兜底产出 ②生理危险区强制（0 AI）
 * ③发育阶段候选池（AI 润色失败回退骨架）④婴儿状态机 ⑤主动检索冷却拦截
 * ⑥自省校验 fail-open ⑦反思 AI 失败降级 ⑧状态衰减/时间推进纯程序化
 * ⑨记忆检索 0 AI（Smallville 评分纯程序化）。</p>
 *
 * <p>所属模块：config（跨域验收，与被验收域解耦）。</p>
 */
class ZeroLlmAcceptanceTest {

    /** AI 全失败网关：任何 chat 调用抛 AiCallException（模拟网关宕机/密钥失效/限流超时）。 */
    private AiSpringChatGateway failingGateway() {
        AiSpringChatGateway g = Mockito.mock(AiSpringChatGateway.class);
        when(g.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new AiCallException("零 LLM 验收：AI 全失败"));
        return g;
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
        AiCallContext.clearProactiveSegment();
    }

    // ==================== ① 决策链 ProgrammaticFallback 兜底产出 ====================

    /**
     * programmatic 档决策链：AI 全失败 → 程序化兜底产出 continue 决策（0 AI）。
     * <p>脑力门控=programmatic 直接程序化决策（0 AI 成本），AI 网关全失败也不影响——</p>
     */
    @Test
    @DisplayName("① 决策链兜底：programmatic 档 AI 全失败仍产出 continue（0 AI）")
    void decisionProgrammaticFallbackProducesWithoutAi() {
        AiSpringChatGateway gateway = failingGateway();
        PromptService promptService = mock(PromptService.class);
        UsageLogService usageLogService = mock(UsageLogService.class);
        PerceptionService perceptionService = mock(PerceptionService.class);
        WorldClockService worldClockService = mock(WorldClockService.class);
        BrainProfileService brainProfileService = mock(BrainProfileService.class);
        ActorAgentProfile prof = new ActorAgentProfile();
        prof.setBrainLevel("programmatic");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        DecisionService service = new DecisionService(gateway, promptService, usageLogService,
                perceptionService, worldClockService, brainProfileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "personaService", mock(com.holzyn.actor.domain.growth.persona.PersonaService.class));

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertNotNull(d);
        assertEquals("continue", d.get("actionType"), "programmatic 档程序化兜底=continue 当前行为");
        verify(gateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== ② 生理危险区强制（0 AI 强制行动） ====================

    /**
     * 生理危险区强制：AI 全失败 + 危险区命中 → 程序化强制行动仍产出（0 AI，先于任何 AI）。
     */
    @Test
    @DisplayName("② 生理危险强制：AI 全失败时危险区仍强制产出（0 AI，先于 AI）")
    void physioDangerForcesWithoutAi() {
        AiSpringChatGateway gateway = failingGateway();
        PromptService promptService = mock(PromptService.class);
        UsageLogService usageLogService = mock(UsageLogService.class);
        PerceptionService perceptionService = mock(PerceptionService.class);
        WorldClockService worldClockService = mock(WorldClockService.class);
        BrainProfileService brainProfileService = mock(BrainProfileService.class);
        ActorAgentProfile prof = new ActorAgentProfile();
        prof.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        DecisionService service = new DecisionService(gateway, promptService, usageLogService,
                perceptionService, worldClockService, brainProfileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "personaService", mock(com.holzyn.actor.domain.growth.persona.PersonaService.class));
        PhysioNeedService physio = mock(PhysioNeedService.class);
        when(physio.dangerDecision(anyLong(), any())).thenReturn(dangerDecisionMap());
        ReflectionTestUtils.setField(service, "physioNeedService", physio);

        Map<String, Object> d = service.generateDecision(character(), 1L, "当前情境");

        assertNotNull(d);
        assertEquals("前往云来茶馆用餐", d.get("action"), "危险区强制行动产出");
        assertEquals(5, d.get("priority"), "危险区 priority=5（健康告急优先）");
        verify(gateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== ③ 发育阶段候选池（AI 润色失败回退骨架） ====================

    /**
     * 发育阶段候选池：AI 润色全失败 → 回退程序化骨架（0 AI 产出，reason 标注「程序化骨架」）。
     */
    @Test
    @DisplayName("③ 发育候选池：AI 润色失败回退骨架（0 AI 产出）")
    void stageGatingFallsBackToSkeletonWithoutAi() {
        GrowthProperties growthProperties = new GrowthProperties();
        growthProperties.getStageGating().setPolishRateSpecial(1.0);
        growthProperties.getStageGating().setPolishRateCommon(1.0);
        GrowthGateService gate = mock(GrowthGateService.class);
        when(gate.gate(any(), any())).thenReturn(HardGateResult.lenient("npc:3"));
        WorldClockService worldClockService = mock(WorldClockService.class);
        when(worldClockService.currentEngineGameSecond(any())).thenReturn(14L * 3600);
        PromptTemplateService pts = mock(PromptTemplateService.class);
        when(pts.render(any(), any(), anyString(), any())).thenReturn("润色 Prompt");
        when(pts.systemMessage(any(), any(), anyString(), any())).thenReturn("system");
        AiSpringChatGateway gateway = failingGateway();
        UsageLogService usageLogService = mock(UsageLogService.class);
        DevelopmentalGatingStrategy strategy = new DevelopmentalGatingStrategy();
        ReflectionTestUtils.setField(strategy, "growthProperties", growthProperties);
        ReflectionTestUtils.setField(strategy, "growthGateService", gate);
        ReflectionTestUtils.setField(strategy, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(strategy, "promptTemplateService", pts);
        ReflectionTestUtils.setField(strategy, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(strategy, "usageLogService", usageLogService);

        DecisionContext ctx = new DecisionContext(100L, character("CHILD"), 1L, NpcKind.SPECIAL,
                "full", null, 14L * 3600, null, false);
        Optional<Map<String, Object>> d = strategy.decide(ctx);

        assertTrue(d.isPresent(), "AI 润色失败仍产出阶段行为");
        String reason = String.valueOf(d.get().get("reason"));
        assertTrue(reason.contains("程序化骨架（0 AI）"), "reason 标注程序化骨架: " + reason);
        assertNotNull(d.get().get("actionDetail"), "骨架行动文本非空");
    }

    // ==================== ④ 婴儿状态机（纯程序化） ====================

    /**
     * 婴儿状态机：advanceInfants 纯程序化推进（构造器无 AI 依赖），饥饿跌破阈值 → 啼哭 + 喂食恢复。
     */
    @Test
    @DisplayName("④ 婴儿状态机：纯程序化推进（0 AI），饥饿触发啼哭喂食")
    void infantStateMachineAdvancesWithoutAi() {
        ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
        ActorNpcStateRepository stateRepository = mock(ActorNpcStateRepository.class);
        LineageService lineageService = mock(LineageService.class);
        WorldEventBus worldEventBus = mock(WorldEventBus.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        GrowthProperties props = new GrowthProperties();
        InfantBehaviorService service = new InfantBehaviorService(characterRepository, stateRepository,
                lineageService, props, worldEventBus, currentUserProvider);
        ActorCharacter infant = new ActorCharacter();
        infant.setId(50L);
        infant.setName("沈小苗");
        infant.setProjectId(1L);
        infant.setStage(GrowthStage.INFANT.name());
        infant.setLocation("家中");
        infant.setCurrentActivity("襁褓中安睡");
        ActorNpcState infantState = new ActorNpcState();
        infantState.setProjectId(1L);
        infantState.setNpcRef("npc:50");
        infantState.setHunger(30);
        infantState.setBladder(60);
        Mockito.lenient().when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(1L, 0))
                .thenReturn(List.of(infant));
        Mockito.lenient().when(stateRepository.findByProjectIdAndNpcRef(1L, "npc:50"))
                .thenReturn(Optional.of(infantState));
        Mockito.lenient().when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.lenient().when(stateRepository.save(any(ActorNpcState.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.lenient().when(lineageService.motherOf(anyLong(), anyString())).thenReturn(Optional.empty());
        Mockito.lenient().when(lineageService.parentEdgesOf(anyLong(), anyString())).thenReturn(List.of());

        service.advanceInfants(1L, 100L * 86400L);

        assertTrue(infantState.getHunger() > 30, "啼哭后应恢复 hunger");
        assertTrue(infant.getCurrentActivity().contains("啼哭"), "活动文本反映啼哭：" + infant.getCurrentActivity());
    }

    // ==================== ⑤ 主动检索冷却拦截（冷却内不发 AI） ====================

    /**
     * 主动记忆检索冷却：need=true 后冷却期内再次自问 → 拦截不调 AI（AI 调用计数不增长）。
     */
    @Test
    @DisplayName("⑤ 主动检索冷却：need=true 后冷却期内不再发起 AI（调用计数不增长）")
    void proactiveCooldownBlocksWithoutAi() throws Exception {
        ProactiveRetrievalService service = new ProactiveRetrievalService();
        ActorMemoryRepository memoryRepository = mock(ActorMemoryRepository.class);
        AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
        KnowledgeRetrievalService knowledgeRetrievalService = mock(KnowledgeRetrievalService.class);
        WorldClockService worldClockService = mock(WorldClockService.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        UsageLogService usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(service, "agentMemoryService", agentMemoryService);
        ReflectionTestUtils.setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "topK", 3);
        ReflectionTestUtils.setField(service, "cooldownGameHours", 2L);
        when(promptTemplateService.render(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(gateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"need\": true, \"query\": \"上次交易的事\", \"reason\": \"想起\"}",
                        "qwen", 100, 50, 150, 1L));
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(100L);
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt()))
                .thenReturn(List.of(memory(11L, 3L, "上次交易时张屠户说过自家铺子的米是从邻县进的")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt()))
                .thenReturn(List.of());

        ProactiveRetrievalService.ProactiveResult first = service.maybeRetrieve(100L, "npc:3", 1L, "情境一");
        assertNotNull(first, "第一次判定 need=true 命中");
        verify(gateway, times(1)).chatCompletion(anyLong(), eq(100L), any(), any());
        // 冷却期内第二次：拦截，不发 AI（计数仍为 1）
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境二"), "冷却期内拦截");
        verify(gateway, times(1)).chatCompletion(anyLong(), eq(100L), any(), any());
    }

    // ==================== ⑥ 自省校验 fail-open（AI 失败不标记） ====================

    /**
     * 自省-行为一致性校验：AI 判定全失败 → fail-open（不标记、不写 trace、不阻断主决策）。
     */
    @Test
    @DisplayName("⑥ 自省校验 fail-open：AI 失败不标记不 trace（零阻断）")
    void selfCheckFailsOpenWithoutAi() {
        List<AiCallTrace> traces = new ArrayList<>();
        Consumer<AiCallTrace> sink = traces::add;
        AiCallContext.setSink(sink);
        SelfCheckStrategy strategy = new SelfCheckStrategy();
        ActorMemoryRepository memoryRepository = mock(ActorMemoryRepository.class);
        WorldClockService worldClockService = mock(WorldClockService.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = failingGateway();
        UsageLogService usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(strategy, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(strategy, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(strategy, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(strategy, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(strategy, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(strategy, "sampleRate", 1.0);
        ReflectionTestUtils.setField(strategy, "reflectionTopN", 3);
        ReflectionTestUtils.setField(strategy, "reflectionWindowGameDays", 1L);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("我要沉稳", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        when(promptTemplateService.render(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");

        DecisionContext ctx = new DecisionContext(100L, character(), 1L, NpcKind.SPECIAL, "full",
                "情境", null, null, false);
        strategy.check(ctx, Map.of("action", "与人争吵"));

        assertTrue(traces.stream().noneMatch(t -> "self_check".equals(t.getStepType())),
                "AI 失败 fail-open：不写漂移 trace");
    }

    // ==================== ⑦ 反思 AI 失败降级（不崩溃） ====================

    /**
     * 反思：AI 返回非 JSON（失败）→ 重试用尽后静默降级返回 false，不崩溃。
     */
    @Test
    @DisplayName("⑦ 反思 AI 失败降级：返回 false 不崩溃（静默降级）")
    void reflectionFallsBackWithoutAi() {
        ActorMemoryRepository memoryRepository = mock(ActorMemoryRepository.class);
        ActorCharacterGoalRepository goalRepository = mock(ActorCharacterGoalRepository.class);
        ActorCharacterRepository characterRepository = mock(ActorCharacterRepository.class);
        ActorWorldEngineRepository engineRepository = mock(ActorWorldEngineRepository.class);
        ActorProjectRepository projectRepository = mock(ActorProjectRepository.class);
        BrainProfileService brainProfileService = mock(BrainProfileService.class);
        WorldClockService worldClockService = mock(WorldClockService.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiSpringChatGateway aiChatGateway = mock(AiSpringChatGateway.class);
        UsageLogService usageLogService = mock(UsageLogService.class);
        AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
        PlanningService planningService = mock(PlanningService.class);
        BudgetGuard budgetGuard = new BudgetGuard();
        ReflectionService service = new ReflectionService(memoryRepository, goalRepository, characterRepository,
                engineRepository, projectRepository, brainProfileService, worldClockService,
                promptTemplateService, aiChatGateway, usageLogService, agentMemoryService,
                planningService, budgetGuard);
        ReflectionTestUtils.setField(service, "maxPerGameDay", 2);
        ReflectionTestUtils.setField(service, "importanceThreshold", 10);
        ReflectionTestUtils.setField(service, "recentMemories", 10);
        ReflectionTestUtils.setField(service, "budgetPerCycle", 0);
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine()));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L);
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(50L);
        ActorAgentProfile prof = new ActorAgentProfile();
        prof.setLastReflectGameDay(null);
        prof.setReflectCountToday(0);
        prof.setReflectionEnabled(1);
        prof.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(new ArrayList<>(List.of(
                mem(1L, 4, LocalDateTime.now().minusDays(1)),
                mem(2L, 4, LocalDateTime.now().minusDays(1)),
                mem(3L, 3, LocalDateTime.now().minusDays(1)))));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project()));
        when(planningService.characterInfoText("npc:3")).thenReturn("林安档案");
        when(planningService.goalsSummary(100L, "npc:3")).thenReturn("");
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("反思 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("不是JSON", "test-model", 20, 8, 28, 1L));
        service.openCycleBudget(100L);

        assertFalse(service.reflect(100L, "npc:3", 50L), "AI 失败静默降级（返回 false 不崩溃）");
    }

    // ==================== ⑧ 状态衰减/时间推进纯程序化 ====================

    /**
     * 状态衰减：applyDecay 纯静态（0 AI）——按游戏时间差惰性补偿衰减数值正确。
     */
    @Test
    @DisplayName("⑧ 状态衰减纯程序化：applyDecay 静态数值正确（0 AI）")
    void stateDecayIsPureProcedural() {
        ActorNpcState st = new ActorNpcState();
        st.setNpcType("special");
        NpcStateService.applyDecay(st, 10L * 3600, 12L * 3600, new NpcStateProperties());
        assertEquals(68, st.getHunger(), "hunger 70-2.4=67.6→68");
        assertEquals(66, st.getThirst(), "thirst 70-4=66");
        assertEquals(78, st.getEnergy(), "energy 80-2=78");
        assertEquals(47, st.getBladder(), "bladder 50-3=47");
        assertEquals(89, st.getHygiene(), "hygiene 90-0.6=89.4→89");
        assertEquals(80, st.getComfort(), "舒适不随时间自衰减");
    }

    // ==================== ⑨ 记忆检索 0 AI（Smallville 评分纯程序化） ====================

    /**
     * 记忆检索：AgentMemoryService.retrieve 纯程序化 Smallville 评分（重要度+新近+相关性），
     * 构造器无 AI 依赖 → AI 全失败时检索仍按评分返回 top-K。
     */
    @Test
    @DisplayName("⑨ 记忆检索 0 AI：Smallville 评分纯程序化，AI 全失败仍返回 top-K")
    void memoryRetrievalWorksWithoutAi() {
        ActorMemoryRepository memoryRepository = mock(ActorMemoryRepository.class);
        AgentMemoryService service = new AgentMemoryService(memoryRepository);
        ReflectionTestUtils.setField(service, "retrievalTopK", 8);
        ReflectionTestUtils.setField(service, "recencyDecayHours", 24);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        ActorMemory imp5 = mem(1L, 5, now.minusHours(1), "昨日与城主密谈边境战事");
        ActorMemory imp1 = mem(2L, 1, now.minusMinutes(5), "今晨在集市买了三个馒头");
        ActorMemory imp3 = mem(3L, 3, now.minusDays(2), "三天前参与城防巡视");
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(100L, "npc:3", 0))
                .thenReturn(List.of(imp5, imp1, imp3));
        when(memoryRepository.save(any(ActorMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        List<ActorMemory> result = service.retrieve(100L, "npc:3", "密谈边境战事", 2);

        assertEquals(2, result.size(), "topK=2");
        assertEquals("昨日与城主密谈边境战事", result.get(0).getContent(),
                "重要度最高且 query 相关应排第一");
    }

    // ==================== 辅助 ====================

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setType("special");
        ch.setImportance(4);
        ch.setCurrentActivity("在茶馆喝茶");
        return ch;
    }

    private ActorCharacter character(String stage) {
        ActorCharacter ch = character();
        ch.setStage(stage);
        ch.setAge(8);
        return ch;
    }

    private Map<String, Object> dangerDecisionMap() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("actionType", "move_to");
        d.put("actionDetail", "前往云来茶馆用餐");
        d.put("reasoning", "饿得发慌了，必须马上找东西吃");
        d.put("priority", 5);
        d.put("expectedDurationMinutes", 30);
        d.put("target", "云来茶馆");
        d.put("type", "move");
        d.put("action", "前往云来茶馆用餐");
        d.put("reason", "饿得发慌了，必须马上找东西吃");
        d.put("urgency", 5);
        d.put("duration", 30);
        return d;
    }

    private ActorMemory memory(Long id, Long charId, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterId(charId);
        m.setCharacterRef(charId == null ? null : "npc:" + charId);
        m.setContent(content);
        m.setImportance(3);
        m.setDeleted(0);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private ActorMemory reflection(String content, LocalDateTime createdAt) {
        ActorMemory m = new ActorMemory();
        m.setId(1L);
        m.setProjectId(100L);
        m.setCharacterId(3L);
        m.setType(ActorMemoryTypes.TYPE_REFLECTION);
        m.setContent(content);
        m.setImportance(3);
        m.setCreatedAt(createdAt);
        m.setDeleted(0);
        return m;
    }

    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(100L);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    private ActorWorldEngine engine() {
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(100L);
        e.setRunMode(RunModes.STANDARD);
        return e;
    }

    private ActorMemory mem(Long id, int importance, LocalDateTime createdAt) {
        return mem(id, importance, createdAt, "记忆内容 " + id);
    }

    private ActorMemory mem(Long id, int importance, LocalDateTime createdAt, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setType("semantic");
        m.setContent(content);
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        return m;
    }
}
