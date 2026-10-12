package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学习组件-反思（D2 F7/R4，domain/agent/learning 同包）单元测试。
 * <p>职责：验证——静态纯逻辑（canReflect 节奏上限 / parseReflection 洞察+目标状态解析）
 * + mock 流（reflect：minimal 跳过 · profile 门控 · 节奏上限 · 无记忆/重要度不足跳过 ·
 * 预算门控 · 全流程 AI 洞察+证据指针+目标流转+staleness 复核+节奏计数 · AI 失败重试降级）。</p>
 */
class ReflectionServiceTest {

    private ActorMemoryRepository memoryRepository;
    private ActorCharacterGoalRepository goalRepository;
    private ActorCharacterRepository characterRepository;
    private ActorWorldEngineRepository engineRepository;
    private ActorProjectRepository projectRepository;
    private BrainProfileService brainProfileService;
    private WorldClockService worldClockService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private AgentMemoryService agentMemoryService;
    private PlanningService planningService;
    private BudgetGuard budgetGuard;
    private ReflectionService service;

    @BeforeEach
    void setUp() {
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        goalRepository = Mockito.mock(ActorCharacterGoalRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        brainProfileService = Mockito.mock(BrainProfileService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        planningService = Mockito.mock(PlanningService.class);
        budgetGuard = new BudgetGuard();
        service = new ReflectionService(memoryRepository, goalRepository, characterRepository,
                engineRepository, projectRepository, brainProfileService, worldClockService,
                promptTemplateService, aiChatGateway, usageLogService, agentMemoryService,
                planningService, budgetGuard);
        // @Value 字段单测不注入，统一回填生产默认值
        ReflectionTestUtils.setField(service, "maxPerGameDay", 2);
        ReflectionTestUtils.setField(service, "importanceThreshold", 10);
        ReflectionTestUtils.setField(service, "recentMemories", 10);
        ReflectionTestUtils.setField(service, "budgetPerCycle", 0); // 默认 0=自动浮动预算
    }

    // ==================== 静态纯逻辑 ====================

    /**
     * canReflect（R4 节奏上限）：跨游戏日重置（true）；同日计数 < 上限 → true；已达上限 → false；
     * 非法 profile/上限 → false。
     */
    @Test
    void canReflectEnforcesPerDayCap() {
        // 跨游戏日：重置 → 允许
        ActorAgentProfile crossDay = profile(2L, 2);
        assertTrue(ReflectionService.canReflect(crossDay, 3L, 2), "跨游戏日重置允许");
        // 同日 1 次 < 上限 2 → 允许
        ActorAgentProfile one = profile(2L, 1);
        assertTrue(ReflectionService.canReflect(one, 2L, 2));
        // 同日 2 次 = 上限 → 拒绝
        ActorAgentProfile capped = profile(2L, 2);
        assertFalse(ReflectionService.canReflect(capped, 2L, 2));
        // 上限 <=0 / profile null → 拒绝
        assertFalse(ReflectionService.canReflect(capped, 2L, 0));
        assertFalse(ReflectionService.canReflect(null, 2L, 2));
        // 未反思过（lastReflectGameDay=null）→ 允许
        ActorAgentProfile fresh = new ActorAgentProfile();
        assertTrue(ReflectionService.canReflect(fresh, 2L, 2));
    }

    /**
     * parseReflection：合法 JSON 解析洞察 + 目标状态更新；非法/空返回空结果。
     */
    @Test
    void parseReflectionReadsInsightsAndGoalUpdates() {
        String ok = "{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4},"
                + "{\"content\":\"\",\"importance\":5}],"
                + "\"goal_updates\":[{\"goal_id\":1,\"status\":\"done\",\"reason\":\"目标达成\"},"
                + "{\"goal_id\":2,\"status\":\"paused\",\"reason\":\"非法状态应被过滤\"},"
                + "{\"goal_id\":0,\"status\":\"done\",\"reason\":\"非法 id 应被过滤\"}]}";
        ReflectionService.ReflectionResult r = ReflectionService.parseReflection(ok);
        assertEquals(1, r.insights().size(), "空 content 洞察被过滤");
        assertEquals(4, r.insights().get(0).importance());
        assertEquals("我更信任合作过的伙伴", r.insights().get(0).content());
        assertEquals(1, r.goalUpdates().size(), "仅合法 id+状态保留");
        assertEquals(1L, r.goalUpdates().get(0).goalId());
        assertEquals("done", r.goalUpdates().get(0).status());

        ReflectionService.ReflectionResult empty = ReflectionService.parseReflection("不是 JSON");
        assertTrue(empty.insights().isEmpty());
        assertTrue(empty.goalUpdates().isEmpty());
        assertTrue(ReflectionService.parseReflection(null).insights().isEmpty());
    }

    // ==================== mock 流：跳过路径 ====================

    /**
     * reflect：minimal 模式跳过（系统自动能力降级），不查配置不调用 AI。
     */
    @Test
    void reflectSkipsInMinimalMode() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.MINIMAL)));
        assertFalse(service.reflect(100L, "npc:3", 50L));
        verify(brainProfileService, never()).resolveByRef(anyString());
    }

    /**
     * reflect：profile 缺失/reflection_enabled=0/programmatic 档 → 跳过。
     */
    @Test
    void reflectSkipsWhenProfileDisabled() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(null);
        assertFalse(service.reflect(100L, "npc:3", 50L));

        ActorAgentProfile disabled = profile(2L, 0);
        disabled.setBrainLevel("full");
        disabled.setReflectionEnabled(0);
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(disabled);
        assertFalse(service.reflect(100L, "npc:3", 50L), "reflection_enabled=0 跳过");

        ActorAgentProfile programmatic = profile(2L, 1);
        programmatic.setBrainLevel("programmatic");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(programmatic);
        assertFalse(service.reflect(100L, "npc:3", 50L), "programmatic 档跳过");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * reflect：本游戏日已达节奏上限 → 跳过。
     */
    @Test
    void reflectSkipsWhenRhythmCapped() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L); // 游戏日 2
        ActorAgentProfile capped = profile(2L, 2); // 已反思 2 次（=上限）
        capped.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(capped);
        assertFalse(service.reflect(100L, "npc:3", 50L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * reflect：无近期记忆 / 重要度不足 → 跳过。
     */
    @Test
    void reflectSkipsWhenNoEvidenceOrLowImportance() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L);
        ActorAgentProfile ok = profile(null, 0);
        ok.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(ok);

        // 无记忆
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(List.of());
        assertFalse(service.reflect(100L, "npc:3", 50L), "无记忆跳过");

        // 有记忆但重要度不足（4+3=7 < 10）
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(new java.util.ArrayList<>(List.of(
                mem(1L, 4, LocalDateTime.now().minusDays(1)), mem(2L, 3, LocalDateTime.now().minusDays(1)))));
        assertFalse(service.reflect(100L, "npc:3", 50L), "重要度不足跳过");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== mock 流：全流程 ====================

    /**
     * reflect 全流程：预算可用 + AI 返回洞察/目标状态 → 写 kind=reflection 洞察（证据指针）+ 目标流转
     * + staleness 复核刷新 + 节奏计数落 profile。
     */
    @Test
    void reflectFullFlowWritesInsightAndUpdatesGoalAndCounts() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L); // 游戏日 2
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(50L);
        ActorAgentProfile prof = profile(null, 0);
        prof.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        List<ActorMemory> memories = new java.util.ArrayList<>(List.of(
                mem(1L, 4, LocalDateTime.now().minusDays(1)),
                mem(2L, 4, LocalDateTime.now().minusDays(1)),
                mem(3L, 3, LocalDateTime.now().minusDays(1))));
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(memories);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project()));
        when(planningService.characterInfoText("npc:3")).thenReturn("林安档案");
        when(planningService.goalsSummary(100L, "npc:3")).thenReturn("【当前目标】- 修复与旧友的关系");
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("反思 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        String ai = "{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}],"
                + "\"goal_updates\":[{\"goal_id\":1,\"status\":\"done\",\"reason\":\"目标达成\"}]}";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 20, 8, 28, 1L));
        // 目标流转：goal 1 存在且属于本项目/本角色、active
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(1L);
        g.setProjectId(100L);
        g.setAgentRef("npc:3");
        g.setStatus("active");
        g.setTitle("修复与旧友的关系");
        when(goalRepository.findById(1L)).thenReturn(Optional.of(g));
        // 注意：未 beginTick（无外层预算窗口，手动/事件路径）——依赖自开窗口（ownsTick 模式）

        boolean ok = service.reflect(100L, "npc:3", 50L);
        assertTrue(ok, "全流程反思成功");

        // 洞察写入（evidence_ids 证据指针 = 本次反思输入记忆 id）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> evCaptor = ArgumentCaptor.forClass(List.class);
        verify(agentMemoryService).recordReflection(eq(100L), eq("npc:3"), eq("我更信任合作过的伙伴"),
                eq(4), evCaptor.capture());
        assertEquals(3, evCaptor.getValue().size(), "证据指针含全部输入记忆 id");
        assertTrue(evCaptor.getValue().contains(1L) && evCaptor.getValue().contains(3L));

        // 目标状态流转：active → done
        assertEquals("done", g.getStatus());
        verify(goalRepository).save(g);

        // staleness 复核刷新（证据记忆 id 列表）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> reCaptor = ArgumentCaptor.forClass(List.class);
        verify(agentMemoryService).recheckMemories(eq(100L), reCaptor.capture());
        assertEquals(3, reCaptor.getValue().size(), "复核对象=证据记忆");

        // 节奏计数：lastReflectGameDay=2, reflectCountToday=1
        assertEquals(2L, prof.getLastReflectGameDay());
        assertEquals(1, prof.getReflectCountToday());
        verify(brainProfileService).save(prof);

        // 用量 scene=reflection
        verify(usageLogService).record(eq(1L), eq(100L), eq(3L), anyLong(), anyString(), eq("reflection"),
                anyInt(), anyInt(), any(), any(), anyInt());
    }

    /**
     * reflect：AI 生成失败（非 JSON）→ 重试后用尽仍失败 → 返回 false，不写洞察不计节奏。
     * <p>P0-2 遗留根治②（2026-09-29）后预算语义：反思走独立命名空间（本用例用周期窗口，
     * 不再依赖引擎 tick 共享窗口——那是反思被饿死的旧根因）。</p>
     */
    @Test
    void reflectFallsBackSilentlyOnAiFailure() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L);
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(50L);
        ActorAgentProfile prof = profile(null, 0);
        prof.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(new java.util.ArrayList<>(List.of(
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

        assertFalse(service.reflect(100L, "npc:3", 50L), "AI 失败静默降级");
        verify(agentMemoryService, never()).recordReflection(anyLong(), anyString(), anyString(), anyInt(), any());
        verify(brainProfileService, never()).save(any());
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== P0-2 遗留根治②：反思预算独立命名空间（2026-09-29） ====================

    /**
     * 引擎 tick 预算耗尽时反思仍执行：tick 窗口（预算 1 已被决策链用尽）与反思窗口完全隔离——
     * 反思自开独立窗口（自动预算）消费，tick 窗口剩余/已用原封未动。这是「反思被 tick 饿死」的直接回归守护。
     */
    @Test
    void reflectRunsWhenTickBudgetExhaustedViaIndependentNamespace() {
        stubFullFlow("{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}]}");
        // 引擎 tick 窗口：预算 1 且已被决策链耗尽（P0-2 冒烟实证的饿死场景）
        budgetGuard.beginTick(100L, 1);
        assertTrue(budgetGuard.tryConsume(100L), "决策链耗尽 tick 预算");

        boolean ok = service.reflect(100L, "npc:3", 50L);
        assertTrue(ok, "tick 预算耗尽时反思仍执行（独立命名空间，不再被饿死）");
        assertEquals(0, budgetGuard.remaining(100L), "tick 窗口剩余仍 0（反思不消费 tick 预算）");
        assertEquals(1, budgetGuard.used(100L), "tick 窗口已用仍 1（反思不计入 tick 计数）");
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 周期窗口已开（WorldEngine 日巩固 openCycleBudget）时：反思消费周期窗口内预算，
     * 并行存在的 tick 窗口零消耗——两命名空间互不竞争。
     */
    @Test
    void reflectConsumesOpenCycleWindowNotTickWindow() {
        stubFullFlow("{\"insights\":[{\"content\":\"洞察内容\",\"importance\":4}]}");
        service.openCycleBudget(100L); // 自动预算=特殊 NPC 数×2（mock 空角色列表 → 下限 1）
        budgetGuard.beginTick(100L, 5); // 决策链并行窗口

        boolean ok = service.reflect(100L, "npc:3", 50L);
        assertTrue(ok);
        assertEquals(0, budgetGuard.remainingReflection(100L), "周期窗口预算 1 已消耗（自动预算下限 1）");
        assertEquals(1, budgetGuard.reflectionUsed(100L), "反思计数走独立命名空间");
        assertEquals(5, budgetGuard.remaining(100L), "tick 预算原封未动");
        assertEquals(0, budgetGuard.used(100L), "tick 零消耗");
        service.closeCycleBudget(100L);
        assertFalse(budgetGuard.hasReflectionWindow(100L), "周期结束窗口关闭");
    }

    /**
     * 浮动预算自动计算：openCycleBudget 的预算 = 特殊 NPC 数 × max-per-game-day
     * （普通型不计入；5 特殊 + 2 普通 → 预算 10）。世界人数多时自动放大，无需改配置。
     */
    @Test
    void openCycleBudgetAutoComputesFromSpecialNpcCount() {
        List<ActorCharacter> chars = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chars.add(character((long) i, "special"));
        }
        chars.add(character(6L, "common"));
        chars.add(character(7L, "crowd")); // 历史普通型写法同样不计入
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(100L, 0)).thenReturn(chars);

        service.openCycleBudget(100L);
        assertTrue(budgetGuard.hasReflectionWindow(100L));
        assertEquals(10, budgetGuard.remainingReflection(100L), "自动预算=5 特殊 × 2 上限=10");
        service.closeCycleBudget(100L);
        assertFalse(budgetGuard.hasReflectionWindow(100L), "周期结束窗口关闭");
    }

    /**
     * 固定覆盖：budget-per-cycle &gt; 0 时 openCycleBudget 用固定值，不查角色数（运维逃生门）。
     */
    @Test
    void openCycleBudgetFixedOverrideWins() {
        ReflectionTestUtils.setField(service, "budgetPerCycle", 3);
        service.openCycleBudget(100L);
        assertEquals(3, budgetGuard.remainingReflection(100L), "固定覆盖 3（不查角色数）");
        service.closeCycleBudget(100L);
    }

    /**
     * 反思预算用尽（周期窗口内被其它反思消耗完）→ 跳过本轮反思，不调 AI——
     * 独立命名空间同样有成本护栏，只是不与 tick 竞争。
     */
    @Test
    void reflectSkipsWhenReflectionBudgetExhausted() {
        stubFullFlow("{\"insights\":[{\"content\":\"洞察\",\"importance\":4}]}");
        ReflectionTestUtils.setField(service, "budgetPerCycle", 1);
        service.openCycleBudget(100L);
        assertTrue(budgetGuard.tryConsumeReflection(100L), "周期内先消耗掉唯一预算");

        assertFalse(service.reflect(100L, "npc:3", 50L), "反思预算用尽跳过");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 自省漂移闭环反馈（候选 A） ====================

    /**
     * 自省漂移闭环：反思输入注入本角色最近「自省漂移」trace 摘要——reflect 全流程
     * 渲染模板时 placeholders 含 self_drift_summary（内容=漂移行 content 去 ref 前缀）。
     */
    @Test
    void reflectInjectsSelfDriftSummaryIntoPlaceholders() throws Exception {
        com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository aiLogRepo =
                Mockito.mock(com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository.class);
        ReflectionTestUtils.setField(service, "aiCallLogRepository", aiLogRepo);
        com.holzyn.actor.domain.debug.entity.ActorAiCallLog driftLog =
                new com.holzyn.actor.domain.debug.entity.ActorAiCallLog();
        driftLog.setContent("ref=npc:3 自省漂移：反思=「不能再冲动」决策=「与人争吵」（矛盾）");
        driftLog.setStepType("self_check");
        com.holzyn.actor.domain.debug.entity.ActorAiCallLog okLog =
                new com.holzyn.actor.domain.debug.entity.ActorAiCallLog();
        okLog.setContent("ref=npc:3 自省校验：与最近反思一致（反思=「要冷静」决策=「先退一步」）");
        okLog.setStepType("self_check");
        com.holzyn.actor.domain.debug.entity.ActorAiCallLog otherRefLog =
                new com.holzyn.actor.domain.debug.entity.ActorAiCallLog();
        otherRefLog.setContent("ref=npc:4 自省漂移：反思=「…」决策=「…」（别的角色）");
        otherRefLog.setStepType("self_check");
        when(aiLogRepo.findTop10ByProjectIdAndStepTypeOrderByIdDesc(100L, "self_check"))
                .thenReturn(List.of(driftLog, okLog, otherRefLog));
        budgetGuard.beginReflection(100L, 10);
        stubFullFlow("{\"insights\":[{\"content\":\"我发现自己容易冲动\",\"importance\":4}],\"goal_updates\":[]}");

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "闭环反馈不影响反思主流程");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> phCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptTemplateService).render(eq(1L), eq(100L), eq("reflection"), phCaptor.capture());
        java.util.Map<String, Object> ph = phCaptor.getValue();
        String summary = String.valueOf(ph.get("self_drift_summary"));
        assertTrue(summary.contains("自省漂移：反思=「不能再冲动」决策=「与人争吵」（矛盾）"),
                "漂移摘要含本角色漂移行（去 ref 前缀）: " + summary);
        assertFalse(summary.contains("ref=npc:3"), "摘要去 ref 前缀");
        assertFalse(summary.contains("ref=npc:4"), "不含其他角色漂移行");
        assertFalse(summary.contains("与最近反思一致"), "一致行不注入（只注入漂移）");
    }

    /** 自省漂移闭环：无漂移记录/仓库未装配 → 摘要为空占位（模板零噪音）。 */
    @Test
    void reflectSelfDriftSummaryEmptyWhenNoLogs() throws Exception {
        com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository aiLogRepo =
                Mockito.mock(com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository.class);
        ReflectionTestUtils.setField(service, "aiCallLogRepository", aiLogRepo);
        when(aiLogRepo.findTop10ByProjectIdAndStepTypeOrderByIdDesc(100L, "self_check"))
                .thenReturn(List.of());
        budgetGuard.beginReflection(100L, 10);
        stubFullFlow("{\"insights\":[{\"content\":\"我发现自己容易冲动\",\"importance\":4}],\"goal_updates\":[]}");

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> phCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptTemplateService).render(eq(1L), eq(100L), eq("reflection"), phCaptor.capture());
        assertEquals("", String.valueOf(phCaptor.getValue().get("self_drift_summary")), "无漂移 → 空段");
    }

    // ==================== 主动记忆检索（P1 ②-1，2026-10-03） ====================

    /**
     * 主动记忆检索：反思入口自问命中 → 反思输入注入【主动想起】段（{{proactive_memory}} 占位符）。
     */
    @Test
    void reflectInjectsProactiveMemoryIntoPlaceholders() throws Exception {
        com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService pro =
                Mockito.mock(com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.class);
        when(pro.maybeRetrieve(eq(100L), eq("npc:3"), eq(1L), anyString()))
                .thenReturn(new com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.ProactiveResult(
                        "【主动想起】此刻你主动想起与当前情况相关的往事与见闻：\n- [经历] 上次与旧友争吵后他再没来过茶馆",
                        java.util.List.of(new com.holzyn.actor.domain.agent.memory.ProactiveRetrievalService.Hit(
                                "mem", 11L, "上次与旧友争吵后他再没来过茶馆"))));
        ReflectionTestUtils.setField(service, "proactiveRetrievalService", pro);
        budgetGuard.beginReflection(100L, 10);
        stubFullFlow("{\"insights\":[{\"content\":\"我发现自己容易冲动\",\"importance\":4}],\"goal_updates\":[]}");

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "主动检索不影响反思主流程");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> phCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptTemplateService).render(eq(1L), eq(100L), eq("reflection"), phCaptor.capture());
        java.util.Map<String, Object> ph = phCaptor.getValue();
        String proactive = String.valueOf(ph.get("proactive_memory"));
        assertTrue(proactive.startsWith("【主动想起】"), "反思输入含主动想起段: " + proactive);
        assertTrue(proactive.contains("上次与旧友争吵"), "段内容为主动检索命中记忆");
    }

    /** 主动记忆检索：服务未装配/降级 → 空占位（模板零噪音）。 */
    @Test
    void reflectProactiveMemoryEmptyWhenUnwired() throws Exception {
        // proactiveRetrievalService 默认 null（未装配）→ 空占位
        budgetGuard.beginReflection(100L, 10);
        stubFullFlow("{\"insights\":[{\"content\":\"我发现自己容易冲动\",\"importance\":4}],\"goal_updates\":[]}");

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> phCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptTemplateService).render(eq(1L), eq(100L), eq("reflection"), phCaptor.capture());
        assertEquals("", String.valueOf(phCaptor.getValue().get("proactive_memory")), "未装配 → 空段");
    }

    // ==================== P1 ②-3 记忆-知识桥接挂钩（2026-10-03） ====================

    /**
     * 反思成功后经 AfterCommit 调用知识桥接（洞察 → AI 判定抽取「可复用事实型知识候选」）。
     * 无外层事务时 AfterCommit 立即执行 → 桥接调用可同步断言；未装配（null）跳过。
     */
    @Test
    void reflectCallsKnowledgeBridgeAfterCommit() {
        com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService bridge =
                Mockito.mock(com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService.class);
        ReflectionTestUtils.setField(service, "knowledgeBridgeService", bridge);
        when(bridge.runForReflection(anyLong(), anyString(), any(), anyString(), any())).thenReturn(
                new com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService.BridgeResult(1, 0, 0));
        stubFullFlow("{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}],\"goal_updates\":[]}");
        budgetGuard.beginReflection(100L, 10);

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "反思成功");
        // 桥接调用：项目/角色/用户/档案 + 洞察列表（含 AI 判定输入）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> insCaptor = ArgumentCaptor.forClass(List.class);
        verify(bridge).runForReflection(eq(100L), eq("npc:3"), eq(1L), eq("林安档案"), insCaptor.capture());
        assertEquals(1, insCaptor.getValue().size(), "洞察列表传入桥接");
        assertEquals("我更信任合作过的伙伴", insCaptor.getValue().get(0), "洞察原文传入");
    }

    /**
     * 桥接失败（抛异常）→ 静默跳过，反思结果不受影响（fail-open 零阻断）。
     */
    @Test
    void reflectBridgeFailureIsFailOpen() {
        com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService bridge =
                Mockito.mock(com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService.class);
        ReflectionTestUtils.setField(service, "knowledgeBridgeService", bridge);
        when(bridge.runForReflection(anyLong(), anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("桥接网关故障"));
        stubFullFlow("{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}],\"goal_updates\":[]}");
        budgetGuard.beginReflection(100L, 10);

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "桥接失败不阻断反思");
        verify(agentMemoryService).recordReflection(eq(100L), eq("npc:3"), eq("我更信任合作过的伙伴"),
                eq(4), any());
    }

    /**
     * 批 4 记忆与反思链·段 2F 对齐：反思输入前置 memoryChainContext（段1A+1C+2D+2E）——
     * AI 从近期记忆提炼洞察时也知道「世界是什么、我是谁、此刻什么时节天气、我状态如何」。
     */
    @Test
    void reflectPrependsMemoryChainContextToPrompt() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), eq(50L * 3600L)))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：林安\n\n"
                        + "【当前世界动态】\n世界历 0420-05-15 06时00分 · 夏季\n\n【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        stubFullFlow("{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}],\"goal_updates\":[]}");
        budgetGuard.beginReflection(100L, 10);

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "反思成功");
        // 捕获实际请求：user 消息应前置记忆链段位（段1A 世界观 + 段1C 角色卡 + 段2D 世界动态 + 段2E 状态）
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), reqCaptor.capture());
        String user = reqCaptor.getValue().messages().get(1).content();
        assertTrue(user.contains("【世界观】"), "应含段1A世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
        assertTrue(user.contains("反思 Prompt"), "模板仍在注入段之后: " + user);
    }

    /**
     * 批 4 注入器未装配（null）→ 反思请求保持原模板（零阻断，既有行为不变）。
     */
    @Test
    void reflectWithoutInjectorKeepsPromptUntouched() {
        ReflectionTestUtils.setField(service, "aiContextInjector", null);
        stubFullFlow("{\"insights\":[{\"content\":\"我更信任合作过的伙伴\",\"importance\":4}],\"goal_updates\":[]}");
        budgetGuard.beginReflection(100L, 10);

        boolean ok = service.reflect(100L, "npc:3", 50L);

        assertTrue(ok, "反思成功");
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), reqCaptor.capture());
        assertEquals("反思 Prompt", reqCaptor.getValue().messages().get(1).content(),
                "未装配注入器 → 模板原样透传");
    }

    // ==================== 辅助 ====================

    /**
     * 全流程前置 stub（预算检查之前的所有闸门：minimal 模式/profile 门控/节奏/记忆重要度/
     * 项目归属/AI 模板），AI 返回给定内容。预算闸门由各用例自行布置。
     */
    private void stubFullFlow(String aiContent) {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(worldClockService.currentGameHour(100L)).thenReturn(50L);
        when(worldClockService.gameHourAt(eq(100L), any())).thenReturn(50L);
        ActorAgentProfile prof = profile(null, 0);
        prof.setBrainLevel("full");
        when(brainProfileService.resolveByRef("npc:3")).thenReturn(prof);
        when(memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", 0)).thenReturn(new java.util.ArrayList<>(List.of(
                mem(1L, 4, LocalDateTime.now().minusDays(1)),
                mem(2L, 4, LocalDateTime.now().minusDays(1)),
                mem(3L, 3, LocalDateTime.now().minusDays(1)))));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project()));
        when(planningService.characterInfoText("npc:3")).thenReturn("林安档案");
        when(planningService.goalsSummary(100L, "npc:3")).thenReturn("【当前目标】- 修复与旧友的关系");
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("反思 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(aiContent, "test-model", 20, 8, 28, 1L));
    }

    private ActorCharacter character(Long id, String type) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setType(type);
        return ch;
    }

    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(100L);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    private ActorWorldEngine engine(String runMode) {
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(100L);
        e.setRunMode(runMode);
        return e;
    }

    private ActorAgentProfile profile(Long lastDay, int countToday) {
        ActorAgentProfile p = new ActorAgentProfile();
        p.setLastReflectGameDay(lastDay);
        p.setReflectCountToday(countToday);
        p.setReflectionEnabled(1);
        return p;
    }

    private ActorMemory mem(Long id, int importance, LocalDateTime createdAt) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setType("semantic");
        m.setContent("记忆内容 " + id);
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        return m;
    }
}
