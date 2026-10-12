package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryFuzzState;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryFuzzStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryWorkingRepository;
import com.holzyn.actor.domain.npc.physio.PhysioNeedService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆注入预算路由单元测试（O1 记忆内核扩展 O1c，domain/memory/service 同包）。
 * <p>职责：验证——tier 判定（规则关键词/反思/urgency + AI 判定失败回退）、分档预算、
 * L1 候选评分排序（清晰优先 + 相关分）、路由 trace 环形缓冲与过滤、检索标记委托。</p>
 * <p>设计依据：《HolzynActor_O1记忆内核扩展_L1动态模糊与跨层级巩固_设计方案_V1.0》§6。</p>
 */
class MemoryInjectionRouterTest {

    private ActorMemoryFuzzStateRepository fuzzRepository;
    private ActorMemoryWorkingRepository workingRepository;
    private MemoryVectorService memoryVectorService;
    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private UsageLogService usageLogService;
    private AiIntensityGate aiIntensityGate;
    private MemoryInjectionRouter router;

    @BeforeEach
    void setUp() {
        fuzzRepository = Mockito.mock(ActorMemoryFuzzStateRepository.class);
        workingRepository = Mockito.mock(ActorMemoryWorkingRepository.class);
        memoryVectorService = Mockito.mock(MemoryVectorService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        router = new MemoryInjectionRouter(fuzzRepository, workingRepository, memoryVectorService,
                aiChatGateway, promptTemplateService, usageLogService, new ObjectMapper());
        // @Value 字段回填生产默认值
        ReflectionTestUtils.setField(router, "fuzzClearThreshold", 0.3);
        ReflectionTestUtils.setField(router, "budgetLowTokens", 300);
        ReflectionTestUtils.setField(router, "budgetMidTokens", 800);
        ReflectionTestUtils.setField(router, "budgetHighTokens", 2000);
        ReflectionTestUtils.setField(router, "lowL1TopK", 3);
        ReflectionTestUtils.setField(router, "midL2TopK", 3);
        ReflectionTestUtils.setField(router, "highL2TopK", 5);
        ReflectionTestUtils.setField(router, "highFuzzyL1TopK", 3);
        ReflectionTestUtils.setField(router, "traceMaxEntries", 200);
        ReflectionTestUtils.setField(router, "hybridVecWeight", 0.6);
    }

    // ==================== 构造 ====================

    private ActorMemoryFuzzState state(long id, long workingId, double fuzz, String content) {
        ActorMemoryFuzzState fs = new ActorMemoryFuzzState();
        fs.setId(id);
        fs.setProjectId(100L);
        fs.setMemoryWorkingId(workingId);
        fs.setCharacterRef("special:3");
        fs.setOriginalContent(content);
        fs.setCurrentContent(content);
        fs.setFuzzLevel(fuzz);
        fs.setImportanceScore(0.4);
        fs.setRetrievalCount(0);
        fs.setFuzzCount(0);
        fs.setFuzzHistoryJson("[]");
        fs.setStatus("active");
        return fs;
    }

    // ==================== 规则 tier 判定（确定性） ====================

    /** 规则：简单事实词（天气/时间/地点/价格）→ LOW。 */
    @Test
    void ruleTierLowForSimpleFacts() {
        assertEquals("LOW", router.ruleTier("dialog", "今天天气如何？", 1));
        assertEquals("LOW", router.ruleTier("dialog", "集市几点开门？", 1));
        assertEquals("LOW", router.ruleTier("dialog", "这东西多少钱一斤？", 1));
        assertEquals("LOW", router.ruleTier("dialog", "他在哪？", 1));
    }

    /** 规则：人物关系/高利害词 → HIGH。 */
    @Test
    void ruleTierHighForRelationsAndStakes() {
        assertEquals("HIGH", router.ruleTier("dialog", "他是否值得信任？", 1));
        assertEquals("HIGH", router.ruleTier("dialog", "阿福答应的事靠谱吗？", 1));
        assertEquals("HIGH", router.ruleTier("dialog", "这事生死攸关，怎么办？", 1));
        assertEquals("HIGH", router.ruleTier("dialog", "我和她是什么关系？", 2));
    }

    /** 规则：反思场景 → HIGH。 */
    @Test
    void ruleTierHighForReflection() {
        assertEquals("HIGH", router.ruleTier("reflection", "随便什么", 1));
    }

    /** 规则：urgency ≥4 → HIGH。 */
    @Test
    void ruleTierHighForHighUrgency() {
        assertEquals("HIGH", router.ruleTier("dialog", "普通问题", 5));
    }

    /** 规则：无关键词无 urgency → MID。 */
    @Test
    void ruleTierMidByDefault() {
        assertEquals("MID", router.ruleTier("dialog", "你觉得怎么样？", 1));
        assertEquals("MID", router.ruleTier("decision", "接下来做什么？", 3));
        assertEquals("MID", router.ruleTier("dialog", null, null));
        assertEquals("MID", router.ruleTier("dialog", "  ", 1));
    }

    /** 分档预算：LOW 300 / MID 800 / HIGH 2000。 */
    @Test
    void budgetOfTiers() {
        assertEquals(300, router.budgetOf("LOW"));
        assertEquals(800, router.budgetOf("MID"));
        assertEquals(2000, router.budgetOf("HIGH"));
        assertEquals(800, router.budgetOf("XXX")); // 未知档回 MID
    }

    /** AI 判定（scene=memory_tier）：合法输出直接采用。 */
    @Test
    void determineTierUsesAiWhenValid() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(router, "currentUserProvider", currentUser);
        when(promptTemplateService.render(eq(1L), eq(100L), eq(PromptTemplateService.CODE_MEMORY_TIER), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(eq(1L), eq(100L), eq(PromptTemplateService.CODE_MEMORY_TIER), any()))
                .thenReturn("sys");
        when(aiChatGateway.chatCompletion(eq(1L), eq(100L), eq(null), any()))
                .thenReturn(new AiChatResult("{\"tier\":\"HIGH\",\"reason\":\"关系问题\"}", "m", 10, 5, 15, 1L));

        assertEquals("HIGH", router.determineTier(100L, "dialog", "他值得信任吗", 1));
        verify(usageLogService).record(eq(1L), eq(100L), eq(null), eq(1L), anyString(),
                eq("memory_tier"), any(), any(), any(), any(), any());
    }

    /** AI 判定：非法输出（非 LOW/MID/HIGH）→ 回退规则。 */
    @Test
    void determineTierFallsBackToRulesOnInvalidAi() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(router, "currentUserProvider", currentUser);
        when(promptTemplateService.render(anyLong(), eq(100L), anyString(), any())).thenReturn("p");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), anyString(), any())).thenReturn("s");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), eq(null), any()))
                .thenReturn(new AiChatResult("{\"tier\":\"XXX\"}", "m", 10, 5, 15, 1L));

        assertEquals("LOW", router.determineTier(100L, "dialog", "今天天气如何", 1));
    }

    /** AI 判定：调用失败（异常）→ 回退规则。 */
    @Test
    void determineTierFallsBackToRulesOnAiError() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(router, "currentUserProvider", currentUser);
        when(promptTemplateService.render(anyLong(), eq(100L), anyString(), any())).thenReturn("p");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), anyString(), any())).thenReturn("s");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), eq(null), any()))
                .thenThrow(new RuntimeException("AI 不可用"));

        assertEquals("MID", router.determineTier(100L, "dialog", "随便聊聊", 1));
    }

    /** programmatic 档：跳过 AI 直接规则。 */
    @Test
    void determineTierSkipsAiWhenProgrammatic() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(false);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        assertEquals("LOW", router.determineTier(100L, "dialog", "今天天气如何", 1));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 批6 收口：memory_tier 记忆链段位前缀（2026-10-10） ====================

    /** 带角色引用调用 → AI 判定请求前置 段1A+1C+2D+2E（memoryChainContext）。 */
    @Test
    void determineTierWithCharacterRefPrependsMemoryChainContext() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(router, "currentUserProvider", currentUser);
        when(promptTemplateService.render(anyLong(), eq(100L), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), anyString(), any())).thenReturn("sys");
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:1"), any()))
                .thenReturn("【世界观】青禾村…\n\n【我的身份与处境】沈砚秋…\n\n【当前世界动态】…\n\n【当前状态】…");
        ReflectionTestUtils.setField(router, "aiContextInjector", injector);
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any()))
                .thenReturn(new AiChatResult("{\"tier\":\"MID\",\"reason\":\"中等\"}", "m", 10, 5, 15, 1L));

        assertEquals("MID", router.determineTier(100L, "npc:1", "dialog", "随便聊聊", 1));

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), org.mockito.ArgumentMatchers.isNull(), cap.capture());
        String userMsg = cap.getValue().messages().get(1).content();
        assertTrue(userMsg.startsWith("【世界观】"), "memory_tier 请求必须前置 记忆链段位: " + userMsg);
        assertTrue(userMsg.contains("prompt"), "模板正文须保留在段位之后");
    }

    /** 无角色引用调用（旧 4 参重载）→ 不注入前缀（fail-open 零阻断）。 */
    @Test
    void determineTierWithoutCharacterRefSkipsPrefix() {
        when(aiIntensityGate.allowsAi(100L)).thenReturn(true);
        ReflectionTestUtils.setField(router, "aiIntensityGate", aiIntensityGate);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(1L);
        ReflectionTestUtils.setField(router, "currentUserProvider", currentUser);
        when(promptTemplateService.render(anyLong(), eq(100L), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), anyString(), any())).thenReturn("sys");
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(anyLong(), anyString(), any())).thenReturn("不应注入");
        ReflectionTestUtils.setField(router, "aiContextInjector", injector);
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any()))
                .thenReturn(new AiChatResult("{\"tier\":\"HIGH\",\"reason\":\"反思\"}", "m", 10, 5, 15, 1L));

        assertEquals("HIGH", router.determineTier(100L, "dialog", "反思", 5));

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), org.mockito.ArgumentMatchers.isNull(), cap.capture());
        assertEquals("prompt", cap.getValue().messages().get(1).content(),
                "无角色引用时不注入前缀（fail-open）");
    }

    // ==================== L1 候选评分 ====================

    /** 评分排序：清晰（fuzz<0.3）优先、清晰内 fuzz 升序 + 相关分降序。 */
    @Test
    void scoreL1ClearFirstThenFuzzy() {
        ActorMemoryFuzzState fuzzy = state(1L, 1L, 0.8, "模糊记忆内容甲");
        ActorMemoryFuzzState clearLow = state(2L, 2L, 0.1, "清晰记忆乙");
        ActorMemoryFuzzState clearHigh = state(3L, 3L, 0.2, "清晰记忆丙");
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of(fuzzy, clearLow, clearHigh));

        List<MemoryInjectionRouter.L1Candidate> list = router.scoreL1(100L, "special:3", "记忆");

        assertEquals(3, list.size());
        // 清晰两条在前（fuzz 升序 0.1 → 0.2），模糊在后
        assertFalse(list.get(0).fuzzy());
        assertFalse(list.get(1).fuzzy());
        assertTrue(list.get(2).fuzzy());
        assertEquals(2L, list.get(0).memoryWorkingId());
        assertEquals(3L, list.get(1).memoryWorkingId());
        assertEquals(1L, list.get(2).memoryWorkingId());
        // 权重 = 1−fuzz
        assertEquals(0.9, list.get(0).weight(), 1e-9);
        assertEquals(0.2, list.get(2).weight(), 1e-9);
    }

    /** 评分：空角色/空状态 → 空列表。 */
    @Test
    void scoreL1EmptyInputs() {
        assertTrue(router.scoreL1(100L, null, "q").isEmpty());
        assertTrue(router.scoreL1(100L, "  ", "q").isEmpty());
        assertTrue(router.scoreL1(null, "special:3", "q").isEmpty());
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of());
        assertTrue(router.scoreL1(100L, "special:3", "q").isEmpty());
    }

    /** 评分：空白内容行跳过。 */
    @Test
    void scoreL1SkipsBlankContent() {
        ActorMemoryFuzzState blank = state(1L, 1L, 0.1, "  ");
        ActorMemoryFuzzState ok = state(2L, 2L, 0.1, "有效内容");
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of(blank, ok));
        List<MemoryInjectionRouter.L1Candidate> list = router.scoreL1(100L, "special:3", "内容");
        assertEquals(1, list.size());
        assertEquals(2L, list.get(0).memoryWorkingId());
    }

    // ==================== 预览 / trace ====================

    /** 预览：档位 + 预算 + L1 候选全量。 */
    @Test
    void previewContainsTierBudgetAndL1() {
        ActorMemoryFuzzState clear = state(1L, 1L, 0.1, "今天在集市卖鱼");
        when(fuzzRepository.findByProjectIdAndCharacterRefAndStatusOrderByIdAsc(100L, "special:3", "active"))
                .thenReturn(List.of(clear));

        Map<String, Object> p = router.preview(100L, "special:3", "dialog", "今天天气如何", 1);

        assertEquals("LOW", p.get("tier"));
        assertEquals(300, p.get("budgetTokens"));
        assertNotNull(p.get("l1"));
        List<?> l1 = (List<?>) p.get("l1");
        assertEquals(1, l1.size());
    }

    /** trace：记录后可回放，按项目/角色过滤。 */
    @Test
    void traceRecordsAndFilters() {
        router.recordTrace(100L, "special:3", "dialog", 7L, "LOW", "事实查询", 1,
                Map.of("l1", new MemoryInjectionRouter.LayerStat("L1短期", 2, 120)));
        router.recordTrace(100L, "special:9", "dialog", 7L, "MID", "默认", 1,
                Map.of("l2", new MemoryInjectionRouter.LayerStat("L2语义", 1, 200)));
        router.recordTrace(200L, "special:3", "dialog", 7L, "HIGH", "关系", 1,
                Map.of("l3c", new MemoryInjectionRouter.LayerStat("L3巩固", 1, 300)));

        // 项目+角色过滤
        List<MemoryInjectionRouter.RouteTrace> all = router.recentTraces(null, null);
        assertEquals(3, all.size());
        List<MemoryInjectionRouter.RouteTrace> p100 = router.recentTraces(100L, null);
        assertEquals(2, p100.size());
        List<MemoryInjectionRouter.RouteTrace> ref3 = router.recentTraces(100L, "special:3");
        assertEquals(1, ref3.size());
        assertEquals("LOW", ref3.get(0).tier());
        assertEquals(2, ref3.get(0).layers().get("l1").count());
    }

    /** trace：环形缓冲上限生效（超限丢弃最旧）。 */
    @Test
    void traceBufferCapsAtMax() {
        ReflectionTestUtils.setField(router, "traceMaxEntries", 5);
        for (int i = 0; i < 8; i++) {
            router.recordTrace(100L, "special:3", "dialog", 7L, "MID", "r" + i, 1,
                    Map.of());
        }
        List<MemoryInjectionRouter.RouteTrace> all = router.recentTraces(null, null);
        assertEquals(5, all.size());
        // 最新在前（seq 降序；seq 为类级自增计数，仅验证相对顺序）
        assertTrue(all.get(0).seq() > all.get(1).seq());
        assertTrue(all.get(1).seq() > all.get(all.size() - 1).seq());
        assertEquals("r7", all.get(0).reason());
        assertEquals("r3", all.get(all.size() - 1).reason());
    }

    /** 检索标记：委托 fuzz 引擎（反复检索=重要 → 蒸馏触发）。 */
    @Test
    void markRetrievedDelegatesToFuzzService() {
        L1FuzzificationService fuzzService = Mockito.mock(L1FuzzificationService.class);
        ReflectionTestUtils.setField(router, "fuzzService", fuzzService);
        router.markRetrieved(42L);
        verify(fuzzService).markRetrieved(42L);
    }

    // ==================== 情境合成：综合紧迫度 + query 兜底（memory_tier 判定输入真实化，2026-10） ====================

    /** 注入情境信号依赖（event/plan/character/physio；null=不装配，fail-open）。 */
    private void injectContextSignals(ActorEventRepository er, ActorActionPlanRepository pr,
                                      ActorCharacterRepository cr, PhysioNeedService pns) {
        ReflectionTestUtils.setField(router, "eventRepository", er);
        ReflectionTestUtils.setField(router, "planRepository", pr);
        ReflectionTestUtils.setField(router, "characterRepository", cr);
        ReflectionTestUtils.setField(router, "physioNeedService", pns);
    }

    private ActorEvent event(long id, String title, int importance) {
        ActorEvent e = new ActorEvent();
        e.setId(id);
        e.setTitle(title);
        e.setImportance(importance);
        return e;
    }

    private ActorActionPlan plan(long id, String actionJson) {
        ActorActionPlan p = new ActorActionPlan();
        p.setId(id);
        p.setActionJson(actionJson);
        return p;
    }

    /** 综合紧迫度：无任何信号 → 场景基数（dialog=2 / decision=3 / reflection=5）。 */
    @Test
    void situationalUrgencyScenarioBaseWithoutSignals() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(anyLong(), anyLong(), anyString())).thenReturn(List.of());
        ActorActionPlanRepository pr = Mockito.mock(ActorActionPlanRepository.class);
        when(pr.findByCharacterIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
        injectContextSignals(er, pr, null, null);

        assertEquals(2, router.situationalUrgency(100L, "npc:3", "dialog"));
        assertEquals(3, router.situationalUrgency(100L, "npc:3", "decision"));
        assertEquals(5, router.situationalUrgency(100L, "npc:3", "reflection"));
    }

    /** 综合紧迫度：最近参与事件重要度参与（max 取高）。 */
    @Test
    void situationalUrgencyUsesRecentEventImportance() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(eq(100L), eq(3L), anyString()))
                .thenReturn(List.of(event(9L, "村口发生争执", 4)));
        ActorActionPlanRepository pr = Mockito.mock(ActorActionPlanRepository.class);
        when(pr.findByCharacterIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
        injectContextSignals(er, pr, null, null);

        assertEquals(4, router.situationalUrgency(100L, "npc:3", "dialog"));
    }

    /** 综合紧迫度：当前行动 urgency 参与（进行中计划 actionJson 的 urgency）。 */
    @Test
    void situationalUrgencyUsesCurrentActionUrgency() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(anyLong(), anyLong(), anyString())).thenReturn(List.of());
        ActorActionPlanRepository pr = Mockito.mock(ActorActionPlanRepository.class);
        when(pr.findByCharacterIdAndStatusInOrderByIdDesc(eq(3L), any()))
                .thenReturn(List.of(plan(5L, "{\"action\":\"奔跑\",\"urgency\":5}")));
        injectContextSignals(er, pr, null, null);

        assertEquals(5, router.situationalUrgency(100L, "npc:3", "dialog"));
    }

    /** 综合紧迫度：生理危险区 → 直接 5（健康告急优先，其他信号失效也抬满）。 */
    @Test
    void situationalUrgencyPhysioDangerForcesFive() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(anyLong(), anyLong(), anyString())).thenReturn(List.of());
        ActorActionPlanRepository pr = Mockito.mock(ActorActionPlanRepository.class);
        when(pr.findByCharacterIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
        ActorCharacterRepository cr = Mockito.mock(ActorCharacterRepository.class);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        when(cr.findByIdAndProjectId(eq(3L), eq(100L))).thenReturn(Optional.of(ch));
        PhysioNeedService pns = Mockito.mock(PhysioNeedService.class);
        when(pns.dangerDecision(eq(100L), any())).thenReturn(Map.of("urgency", 5, "priority", 5));
        injectContextSignals(er, pr, cr, pns);

        assertEquals(5, router.situationalUrgency(100L, "npc:3", "dialog"));
    }

    /** 综合紧迫度：生理危险区信号异常 → fail-open（跳过该信号，仍给场景基数）。 */
    @Test
    void situationalUrgencyPhysioDangerFailsOpen() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(anyLong(), anyLong(), anyString())).thenReturn(List.of());
        ActorActionPlanRepository pr = Mockito.mock(ActorActionPlanRepository.class);
        when(pr.findByCharacterIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
        ActorCharacterRepository cr = Mockito.mock(ActorCharacterRepository.class);
        when(cr.findByIdAndProjectId(anyLong(), anyLong())).thenThrow(new RuntimeException("db down"));
        injectContextSignals(er, pr, cr, Mockito.mock(PhysioNeedService.class));

        assertEquals(2, router.situationalUrgency(100L, "npc:3", "dialog"));
    }

    /** query 兜底：最近一条 L1 task 工作项（当前关注点）。 */
    @Test
    void recentContextQueryReturnsTaskContent() {
        ActorMemoryWorking task = new ActorMemoryWorking();
        task.setId(11L);
        task.setItemType("task");
        task.setContent("当前行动：去集市买药");
        when(workingRepository.findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
                anyLong(), anyString(), anyString(), eq(0))).thenReturn(List.of(task));

        assertEquals("当前行动：去集市买药", router.recentContextQuery(100L, "npc:3"));
    }

    /** query 兜底：无 task → 最近参与事件文本（该角色刚经历了什么）。 */
    @Test
    void recentContextQueryFallsBackToEventWhenNoTask() {
        ActorEventRepository er = Mockito.mock(ActorEventRepository.class);
        when(er.findByProjectIdAndCharacterRef(eq(100L), eq(3L), anyString()))
                .thenReturn(List.of(event(1L, "村口老槐树被雷劈了", 3)));
        injectContextSignals(er, null, null, null);

        assertEquals("村口老槐树被雷劈了", router.recentContextQuery(100L, "npc:3"));
    }

    /** query 兜底：全空（无 task 无事件）→ null（上层再兜底「（无明确查询）」）。 */
    @Test
    void recentContextQueryReturnsNullWhenNothingAvailable() {
        injectContextSignals(null, null, null, null);

        assertNull(router.recentContextQuery(100L, "npc:3"));
    }
}
