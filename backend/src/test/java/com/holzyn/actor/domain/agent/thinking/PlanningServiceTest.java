package com.holzyn.actor.domain.agent.thinking;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.BudgetGuard;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 思考规划组件（D2 F7，domain/agent/thinking 同包）单元测试。
 * <p>职责：验证——静态纯逻辑（parseGoals/parsePlan/mechanismPlan/seedOf）+ mock 流
 * （createGoal 手动 CRUD / ensurePlanDay 惰性补生成[机制回退] / generateGoal AI 生成[minimal 跳过·
 * 已有 active 跳过·预算门控] / goalsSummary / planSummary）。</p>
 */
class PlanningServiceTest {

    private ActorCharacterGoalRepository goalRepository;
    private ActorMemoryRepository memoryRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private ActorWorldEngineRepository engineRepository;
    private ActorProjectRepository projectRepository;
    private BrainProfileService brainProfileService;
    private WorldClockService worldClockService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private AgentMemoryService agentMemoryService;
    private BudgetGuard budgetGuard;
    private NpcTypeResolver npcTypeResolver;
    private PlanningService service;

    @BeforeEach
    void setUp() {
        goalRepository = Mockito.mock(ActorCharacterGoalRepository.class);
        memoryRepository = Mockito.mock(ActorMemoryRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        brainProfileService = Mockito.mock(BrainProfileService.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        agentMemoryService = Mockito.mock(AgentMemoryService.class);
        budgetGuard = new BudgetGuard();
        // O6 G1a：npc:{id} 不再编码类型——用真实 NpcTypeResolver（类型查 actor_profile.type，
        // 查不到回退 special），夹具 character(3L) 默认 type=special、commonCharacter(...) 显式 type=common
        npcTypeResolver = new NpcTypeResolver(characterRepository);
        // G1a：档案读取唯一来源（@Autowired(required=false) 字段注入，单测手动回填 mock）
        PersonaService personaService = Mockito.mock(PersonaService.class);
        service = new PlanningService(goalRepository, memoryRepository, characterRepository,
                npcRepository, engineRepository, projectRepository, brainProfileService, worldClockService,
                promptTemplateService, aiChatGateway, usageLogService, agentMemoryService, budgetGuard,
                new ObjectMapper(), npcTypeResolver);
        ReflectionTestUtils.setField(service, "personaService", personaService);
    }

    // ==================== 静态纯逻辑 ====================

    /**
     * parseGoals：数组/单对象/代码块包裹均能解析；非法/空返回空列表。
     */
    @Test
    void parseGoalsReadsArrayObjectAndCodeFence() {
        assertEquals(2, PlanningService.parseGoals(
                "[{\"title\":\"修复与旧友的关系\",\"description\":\"多年误会\",\"priority\":5},"
                        + "{\"title\":\"经营好药材铺\"}]").size());
        assertEquals(1, PlanningService.parseGoals("{\"title\":\"成为城主护卫队长\",\"priority\":4}").size());
        assertEquals(1, PlanningService.parseGoals("```json\n[{\"title\":\"完成任务\"}]\n```").size());
        assertTrue(PlanningService.parseGoals(null).isEmpty());
        assertTrue(PlanningService.parseGoals("不是 JSON").isEmpty());
        assertTrue(PlanningService.parseGoals("").isEmpty());
    }

    /**
     * parsePlan：合法计划（date_game_day+items）解析；缺 items/非法返回 null。
     */
    @Test
    void parsePlanReadsValidAndRejectsInvalid() {
        String ok = "{\"date_game_day\":3,\"fromAi\":true,\"items\":["
                + "{\"hour\":8,\"action\":\"前往城东市场采购药材\",\"target\":\"城东市场\",\"location\":\"城东\",\"state\":\"walk\"},"
                + "{\"hour\":12,\"action\":\"午间小憩\",\"state\":\"rest\"}]}";
        PlanningService.PlanData p = PlanningService.parsePlan(ok);
        assertNotNull(p);
        assertEquals(3, p.gameDay());
        assertTrue(p.fromAi());
        assertEquals(2, p.items().size());
        assertEquals(8, p.items().get(0).hour());
        assertEquals("城东市场", p.items().get(0).target());
        assertNull(PlanningService.parsePlan("{\"date_game_day\":1}"), "缺 items 返回 null");
        assertNull(PlanningService.parsePlan("不是 JSON"));
        assertNull(PlanningService.parsePlan(null));
        assertNull(PlanningService.parsePlan("{\"items\":[]}"), "空 items 返回 null");
    }

    /**
     * mechanismPlan：确定性 5~8 条、小时升序、游戏日一致；同 seed+游戏日输出稳定（个体差异由 seed 微调）。
     */
    @Test
    void mechanismPlanIsDeterministicAndSorted() {
        PlanningService.PlanData a = PlanningService.mechanismPlan(3, 100);
        PlanningService.PlanData b = PlanningService.mechanismPlan(3, 100);
        PlanningService.PlanData c = PlanningService.mechanismPlan(3, 200);
        assertNotNull(a);
        assertEquals(3, a.gameDay());
        assertFalse(a.fromAi(), "机制计划非 AI 来源");
        assertTrue(a.items().size() >= 5 && a.items().size() <= 8, "条目 5~8 条");
        for (int i = 1; i < a.items().size(); i++) {
            assertTrue(a.items().get(i).hour() > a.items().get(i - 1).hour(), "小时升序");
        }
        assertEquals(a.items().size(), b.items().size(), "同 seed+游戏日稳定");
        assertEquals(a.items().get(0).action(), b.items().get(0).action());
        assertFalse(a.items().get(0).action().equals(c.items().get(0).action()) && a.items().size() == c.items().size(),
                "不同 seed 有差异（个体差异）——防御性断言，同 seed 也合法");
    }

    /**
     * seedOf：统一引用 {@code npc:{id}} 用 id；历史双前缀/其他用 hash 稳定非零。
     */
    @Test
    void seedOfParsesRefs() {
        assertEquals(5, PlanningService.seedOf("npc:5"));
        assertEquals(7, PlanningService.seedOf("npc:7"));
        // G1a：历史 special:/crowd: 前缀不再是合法身份引用（严格单前缀）→ 走 hash 兜底，不再取 id
        assertNotEquals(5, PlanningService.seedOf("special:5"), "历史 special: 前缀不再取 id 作种子");
        assertNotEquals(7, PlanningService.seedOf("crowd:7"), "历史 crowd: 前缀不再取 id 作种子");
        assertTrue(PlanningService.seedOf("unknown-ref") > 0);
        assertEquals(1, PlanningService.seedOf(null));
        assertEquals(1, PlanningService.seedOf(""));
    }

    // ==================== mock 流：手动 CRUD ====================

    /**
     * createGoal：项目存在时创建 active 目标；优先级钳制 1~5；空标题拒绝。
     */
    @Test
    void createGoalPersistsWithDefaults() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActorCharacterGoal g = service.createGoal(100L, "npc:3", "  修复与旧友的关系  ", "多年误会", 9);
        assertNotNull(g);
        assertEquals(100L, g.getProjectId());
        assertEquals("npc:3", g.getAgentRef());
        assertEquals("修复与旧友的关系", g.getTitle());
        assertEquals("active", g.getStatus());
        assertEquals(5, g.getPriority(), "优先级钳制上限 5");
        verify(goalRepository).save(g);

        assertThrows(BizException.class, () -> service.createGoal(100L, "npc:3", "  ", null, null),
                "空标题拒绝");
    }

    /**
     * updateGoal：状态/标题/优先级更新；非法状态拒绝；完成态刷新 finishedAt。
     */
    @Test
    void updateGoalAppliesFieldsAndValidatesStatus() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(1L);
        g.setProjectId(100L);
        g.setStatus("active");
        g.setTitle("旧标题");
        g.setPriority(3);
        when(goalRepository.findById(1L)).thenReturn(Optional.of(g));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActorCharacterGoal updated = service.updateGoal(1L, "done", "新标题", 4);
        assertEquals("done", updated.getStatus());
        assertEquals("新标题", updated.getTitle());
        assertEquals(4, updated.getPriority());
        // 注：finished_at 由实体 @PreUpdate 生命周期回调填充，mock 环境不触发回调，
        // 故此处不断言 finishedAt（真实 JPA save 时自动刷新）
        verify(goalRepository).save(g);

        assertThrows(BizException.class, () -> service.updateGoal(1L, "paused", null, null), "非法状态拒绝");
        assertThrows(BizException.class, () -> service.updateGoal(999L, "done", null, null), "不存在 404");
    }

    /**
     * deleteGoal：软删（deleted=1）。
     */
    @Test
    void deleteGoalSoftDeletes() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(2L);
        g.setProjectId(100L);
        g.setStatus("active");
        when(goalRepository.findById(2L)).thenReturn(Optional.of(g));
        service.deleteGoal(2L);
        assertEquals(1, g.getDeleted());
        verify(goalRepository).save(g);
    }

    // ==================== mock 流：日计划 ====================

    /**
     * ensurePlanDay：当天已有计划 → null（不重复生成）；无计划 → 机制化生成并落记忆（kind=plan）。
     */
    @Test
    void ensurePlanDayGeneratesMechanismPlanWhenMissing() {
        when(worldClockService.currentGameHour(100L)).thenReturn(50L); // 游戏日 2
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of());
        budgetGuard.beginTick(100L, 0); // 无预算 → 机制计划

        String json = service.ensurePlanDay(100L, "npc:3", 50L);
        assertNotNull(json);
        assertTrue(json.contains("\"date_game_day\":2"), "计划归属游戏日 2");
        verify(agentMemoryService).recordPlan(eq(100L), eq("npc:3"), anyString(), anyInt());
    }

    /**
     * ensurePlanDay：当天已有计划 → 返回 null（双保险不重复生成）。
     */
    @Test
    void ensurePlanDaySkipsWhenExists() {
        when(worldClockService.currentGameHour(100L)).thenReturn(50L); // 游戏日 2
        ActorMemory m = new ActorMemory();
        m.setId(9L);
        m.setContent("{\"date_game_day\":2,\"items\":[{\"hour\":8,\"action\":\"劳作\"}]}");
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of(m));

        assertNull(service.ensurePlanDay(100L, "npc:3", 50L));
        verify(agentMemoryService, never()).recordPlan(anyLong(), anyString(), anyString(), anyInt());
    }

    // ==================== mock 流：目标 AI 生成 ====================

    /**
     * generateGoal：minimal 模式跳过（系统自动能力降级），不调用 AI。
     */
    @Test
    void generateGoalSkipsInMinimalMode() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.MINIMAL)));
        assertEquals(0, service.generateGoal(100L, "npc:3", 50L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * generateGoal：已有 active 目标 → 不重复生成（0）。
     */
    @Test
    void generateGoalSkipsWhenActiveExists() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "active", 0)).thenReturn(List.of(goal(1L, "已有目标")));
        assertEquals(0, service.generateGoal(100L, "npc:3", 50L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * generateGoal：无 active 目标 + 无外层预算窗口（手动路径）→ 自开预算窗口（ownsTick 模式）走 AI 生成。
     */
    @Test
    void generateGoalSelfOpensBudgetWhenNoWindow() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "active", 0)).thenReturn(List.of());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("目标 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        String ai = "[{\"title\":\"修复与旧友的关系\",\"description\":\"多年误会\",\"priority\":5}]";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 10, 5, 15, 1L));
        // 注意：未 beginTick（无外层窗口）——依赖自开窗口

        int created = service.generateGoal(100L, "npc:3", 50L);
        assertEquals(1, created, "手动路径自开预算窗口后正常 AI 生成");
        verify(goalRepository, times(1)).save(any(ActorCharacterGoal.class));
        verify(usageLogService).record(eq(1L), eq(100L), eq(3L), anyLong(), anyString(), eq("goal_gen"),
                anyInt(), anyInt(), any(), any(), anyInt());
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * generateGoal：预算用尽（有窗口但剩余 0）→ 跳过（0），不调用 AI。
     */
    @Test
    void generateGoalSkipsWhenBudgetExhausted() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "active", 0)).thenReturn(List.of());
        budgetGuard.beginTick(100L, 0); // 有窗口但预算 0
        assertEquals(0, service.generateGoal(100L, "npc:3", 50L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * ensurePlanDay：AI 模板输出 date_game_day=gameDay+1（展示日号），落库前归一化回 0 基查询口径
     * （冒烟抓修 2026-08-24：口径不一致导致 planToday/planSummary 恒空）。
     */
    @Test
    void planDayNormalizesAiOutputGameDay() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("日计划 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        // AI 输出 date_game_day=3（模板占位 gameDay+1=3），当前游戏日=2
        String ai = "{\"date_game_day\":3,\"items\":[{\"hour\":8,\"action\":\"上午劳作\",\"state\":\"stop\"},"
                + "{\"hour\":12,\"action\":\"午间小憩\",\"state\":\"rest\"}]}";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 10, 5, 15, 1L));
        budgetGuard.beginTick(100L, 5);

        String json = service.ensurePlanDay(100L, "npc:3", 50L); // 游戏日 2
        assertNotNull(json);
        assertTrue(json.contains("\"date_game_day\":2"), "AI 输出归一化回 0 基游戏日（2），实际=" + json);
        assertFalse(json.contains("\"date_game_day\":3"), "不应残留 AI 的 +1 日号");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(agentMemoryService).recordPlan(eq(100L), eq("npc:3"), cap.capture(), anyInt());
    }

    // ==================== 批5 世界与叙事：段1A+1C+2D+2E 前缀（2026-10-10） ====================

    /**
     * 日计划生成（plan_day）前置 V2.0 memoryChainContext 前缀（段1A 世界观 + 段1C 角色卡 +
     * 段2D 当前世界动态 + 段2E 当前状态）——AI 排今日计划时知道世界与自身处境。
     */
    @Test
    void planDay_prependsMemoryChainContextPrefix() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("日计划 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(eq(100L), eq("npc:3"), eq((2L * 24L + 8) * 3600L)))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        String ai = "{\"date_game_day\":3,\"items\":[{\"hour\":8,\"action\":\"上午劳作\",\"state\":\"stop\"}]}";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 10, 5, 15, 1L));
        budgetGuard.beginTick(100L, 5);

        service.ensurePlanDay(100L, "npc:3", 50L);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("日计划 Prompt"), "模板 prompt 保留: " + user);
    }

    /**
     * 计划中断重排（plan_replan）前置 memoryChainContext 前缀（段1A+1C+2D+2E）。
     */
    @Test
    void replan_prependsMemoryChainContextPrefix() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        // 当天已有计划（重排前提）
        ActorMemory oldPlan = new ActorMemory();
        oldPlan.setContent("{\"date_game_day\":2,\"items\":[{\"hour\":8,\"action\":\"上午劳作\",\"state\":\"stop\"}]}");
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of(oldPlan));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("重排 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(eq(100L), eq("npc:3"), eq((2L * 24L + 10) * 3600L)))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        String ai = "{\"date_game_day\":3,\"items\":[{\"hour\":10,\"action\":\"应对事件\",\"state\":\"stop\"}]}";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 10, 5, 15, 1L));
        budgetGuard.beginTick(100L, 5);

        service.replanOnDisruption(100L, "npc:3", 58L, "突降暴雨冲垮村口石桥"); // 游戏日 2 当日 10 时
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("重排 Prompt"), "模板 prompt 保留: " + user);
    }

    /**
     * 目标生成（goal_gen）前置 memoryChainContext 前缀（段1A+1C+2D+2E）。
     */
    @Test
    void generateGoal_prependsMemoryChainContextPrefix() {
        when(engineRepository.findByProjectId(100L)).thenReturn(Optional.of(engine(RunModes.STANDARD)));
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "active", 0)).thenReturn(List.of());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L)));
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any())).thenReturn("目标 Prompt");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.memoryChainContext(eq(100L), eq("npc:3"), eq(50L * 3600L)))
                .thenReturn("【世界观】青禾村志\n【我的身份与处境】沈砚秋\n"
                        + "【当前世界动态】青禾村历 0420年 秋 · 天气晴\n【当前状态】\n- 需求：安闲");
        ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        String ai = "[{\"title\":\"修复与旧友的关系\",\"description\":\"多年误会\",\"priority\":5}]";
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(ai, "test-model", 10, 5, 15, 1L));

        service.generateGoal(100L, "npc:3", 50L);
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.contains("【世界观】"), "应含段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E: " + user);
        assertTrue(user.contains("目标 Prompt"), "模板 prompt 保留: " + user);
    }

    // ==================== mock 流：感知摘要 ====================

    /**
     * goalsSummary：active 目标按优先级排序注入 top2；无目标/普通型（common）返回空串。
     */
    @Test
    void goalsSummaryInjectsTopActiveGoals() {
        when(characterRepository.findById(3L)).thenReturn(Optional.of(character(3L)));
        ActorCharacterGoal low = goal(1L, "低优先目标");
        low.setPriority(2);
        ActorCharacterGoal high = goal(2L, "高优先目标");
        high.setPriority(5);
        ActorCharacterGoal mid = goal(3L, "中优先目标");
        mid.setPriority(3);
        when(goalRepository.findByProjectIdAndAgentRefAndStatusAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "active", 0)).thenReturn(new java.util.ArrayList<>(List.of(low, high, mid)));
        String s = service.goalsSummary(100L, "npc:3");
        assertTrue(s.startsWith("【当前目标】"));
        assertTrue(s.contains("高优先目标"), "高优先在前");
        assertTrue(s.contains("中优先目标"), "第二高优先入 top2");
        assertFalse(s.contains("低优先目标"), "仅注入 top2（最低优先被截断）");
        // G1a：普通型（type=common）不参与目标表 → 空串（类型查 actor_profile.type，不再看引用前缀）
        when(characterRepository.findById(9L)).thenReturn(Optional.of(commonCharacter(9L)));
        assertEquals("", service.goalsSummary(100L, "npc:9"), "普通型（common）无目标表返回空串");
    }

    /**
     * planSummary：注入「当前时段 + 稍后预告」；无计划返回空串。
     */
    @Test
    void planSummaryInjectsCurrentAndUpcoming() {
        when(worldClockService.currentGameHour(100L)).thenReturn(50L); // 游戏日 2，当日 2 时
        ActorMemory m = new ActorMemory();
        m.setId(9L);
        m.setContent("{\"date_game_day\":2,\"items\":[{\"hour\":6,\"action\":\"晨起出门\"},"
                + "{\"hour\":8,\"action\":\"上午劳作\"},{\"hour\":12,\"action\":\"午间小憩\"}]}");
        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of(m));

        String s = service.planSummary(100L, "npc:3", 50L);
        assertTrue(s.startsWith("【今日计划】"));
        assertTrue(s.contains("即将（6时）"), "2 时之前无当前条目，取第一条作「即将」");
        assertTrue(s.contains("稍后（8时）"));
        assertFalse(s.contains("稍后（6时）"), "「即将」已展示首条，稍后不再重复");
        assertFalse(s.contains("稍后（12时）"), "仅预告 2 条后续");

        when(memoryRepository.findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, "npc:3", "plan", 0)).thenReturn(List.of());
        assertEquals("", service.planSummary(100L, "npc:3", 50L), "无计划返回空串");
    }

    // ==================== 辅助 ====================

    private ActorProject project(Long id) {
        ActorProject p = new ActorProject();
        p.setId(id);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    private ActorCharacter character(Long id) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(100L);
        c.setName("林安");
        c.setImportance(4);
        return c;
    }

    /** 普通型 NPC 夹具（G1a：单主表下必须显式 setType("common")，实体默认值是 special） */
    private ActorCharacter commonCharacter(Long id) {
        ActorCharacter c = character(id);
        c.setType("common");
        return c;
    }

    private ActorWorldEngine engine(String runMode) {
        ActorWorldEngine e = new ActorWorldEngine();
        e.setProjectId(100L);
        e.setRunMode(runMode);
        return e;
    }

    private ActorCharacterGoal goal(Long id, String title) {
        ActorCharacterGoal g = new ActorCharacterGoal();
        g.setId(id);
        g.setProjectId(100L);
        g.setAgentRef("npc:3");
        g.setTitle(title);
        g.setStatus("active");
        g.setPriority(3);
        return g;
    }
}
