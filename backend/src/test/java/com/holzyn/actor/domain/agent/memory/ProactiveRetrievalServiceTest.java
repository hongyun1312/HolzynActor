package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * ProactiveRetrievalService 主动记忆检索（ProactAgent「Ask Only When Needed」）单元测试。
 * <p>验证：门控（开关/情境空白）、AI 判定（need true/false/解析失败/fail-open）、
 * 三源检索（角色记忆/世界大事记/知识卡 + 跨源去重）、【主动想起】段组装（预算截断）、
 * 冷却（need=true 后冷却期内不再自问）、trace 落点（stepType=proactive_retrieval + ref 前缀）。</p>
 */
class ProactiveRetrievalServiceTest {

    private final Consumer<AiCallTrace>[] capturedSink = new Consumer[1];
    private final List<AiCallTrace> traces = new ArrayList<>();

    private ProactiveRetrievalService service;
    private ActorMemoryRepository memoryRepository;
    private AgentMemoryService agentMemoryService;
    private KnowledgeRetrievalService knowledgeRetrievalService;
    private WorldClockService worldClockService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;

    @BeforeEach
    void setUp() {
        service = new ProactiveRetrievalService();
        memoryRepository = mock(ActorMemoryRepository.class);
        agentMemoryService = mock(AgentMemoryService.class);
        knowledgeRetrievalService = mock(KnowledgeRetrievalService.class);
        worldClockService = mock(WorldClockService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(service, "agentMemoryService", agentMemoryService);
        ReflectionTestUtils.setField(service, "knowledgeRetrievalService", knowledgeRetrievalService);
        ReflectionTestUtils.setField(service, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(service, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        // 测试确定性：top-k=3、冷却 2 游戏小时
        ReflectionTestUtils.setField(service, "topK", 3);
        ReflectionTestUtils.setField(service, "cooldownGameHours", 2L);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
        AiCallContext.clearProactiveSegment();
    }

    private void captureTraces() {
        capturedSink[0] = traces::add;
        AiCallContext.setSink(capturedSink[0]);
    }

    /** 过滤出主动检索环节 trace（stepType=proactive_retrieval；AI 调用行 scene 同名但 stepType=ai_call）。 */
    private List<AiCallTrace> proactiveTraces() {
        return traces.stream().filter(t -> ProactiveRetrievalService.STEP_TYPE_PROACTIVE_RETRIEVAL.equals(t.getStepType())).toList();
    }

    private void stubGateway(String json) throws Exception {
        when(promptTemplateService.render(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(json, "qwen", 100, 50, 150, 0, 100, 1L));
    }

    private void stubClock() {
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(100L);
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

    private ActorMemory projectMemory(Long id, String content) {
        ActorMemory m = new ActorMemory();
        m.setId(id);
        m.setProjectId(100L);
        m.setCharacterId(null);
        m.setContent(content);
        m.setImportance(4);
        m.setDeleted(0);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ==================== 门控 ====================

    /** 总开关关闭 → 跳过（无 AI、无 trace）。 */
    @Test
    void skipsWhenDisabled() throws Exception {
        captureTraces();
        ReflectionTestUtils.setField(service, "enabled", false);
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "茶馆里坐着几个人"));
        assertTrue(proactiveTraces().isEmpty(), "开关关不写 trace");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /** 情境空白 → 跳过（无判定输入）。 */
    @Test
    void skipsWhenBlankSituation() throws Exception {
        captureTraces();
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, null));
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "   "));
        assertTrue(proactiveTraces().isEmpty());
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== AI 判定 ====================

    /** need=false：不检索、不进入冷却、写 need=false trace 留痕审计。 */
    @Test
    void needFalseNoRetrievalAndNoCooldown() throws Exception {
        captureTraces();
        stubGateway("{\"need\": false, \"query\": \"\", \"reason\": \"熟悉场景无需回想\"}");
        stubClock();
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "茶馆里坐着几个人"));
        List<AiCallTrace> tr = proactiveTraces();
        assertEquals(1, tr.size(), "need=false 留痕一条");
        assertTrue(tr.get(0).getContent().startsWith("ref=npc:3 "), "content 带 ref 前缀");
        assertTrue(tr.get(0).getContent().contains("need=false"), "need=false 标记");
        // 冷却不落（need=false 不是「想起过」）：第二次仍会判定
        stubGateway("{\"need\": true, \"query\": \"上次与张屠户的交易\", \"reason\": \"想起\"}");
        stubMemoryOnly();
        ProactiveRetrievalService.ProactiveResult r = service.maybeRetrieve(100L, "npc:3", 1L, "茶馆里坐着几个人");
        assertNotNull(r, "need=false 不进入冷却，第二次可再判定");
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), eq(100L), any(), any());
    }

    /** need=true：三源检索命中 → 组装【主动想起】段 + need=true trace。 */
    @Test
    void needTrueRetrievesThreeSources() throws Exception {
        captureTraces();
        stubGateway("{\"need\": true, \"query\": \"上次与张屠户的交易他提过什么要求\", \"reason\": \"关系到本次交易\"}");
        stubClock();
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), eq("上次与张屠户的交易他提过什么要求"), eq(3)))
                .thenReturn(List.of(memory(11L, 3L, "上次交易时张屠户说过自家铺子的米是从邻县进的")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of(projectMemory(22L, "村口粮仓前夜失火，村长带人扑灭了")));
        when(knowledgeRetrievalService.search(eq(1L), eq(100L), eq(3L),
                eq("上次与张屠户的交易他提过什么要求"), eq(3)))
                .thenReturn(List.of(new KnowledgeHit(33L, "人物知识卡", "张屠户：卖米为生，最重信用", 0.9, 3L)));

        ProactiveRetrievalService.ProactiveResult result =
                service.maybeRetrieve(100L, "npc:3", 1L, "茶馆里坐着几个人，准备与张屠户谈米价");
        assertNotNull(result);
        assertNotNull(result.segment());
        assertTrue(result.segment().startsWith("【主动想起】"), "段以【主动想起】开头");
        assertTrue(result.segment().contains("[经历]"), "含角色记忆");
        assertTrue(result.segment().contains("[世界]"), "含世界大事记");
        assertTrue(result.segment().contains("[所知]"), "含知识卡");
        assertEquals(3, result.hits().size(), "三源各 1 条");
        // need=true trace
        List<AiCallTrace> tr = proactiveTraces();
        assertEquals(1, tr.size());
        assertTrue(tr.get(0).getContent().startsWith("ref=npc:3 "));
        assertTrue(tr.get(0).getContent().contains("need=true"));
        assertTrue(tr.get(0).getContent().contains("mem#11") && tr.get(0).getContent().contains("proj#22")
                && tr.get(0).getContent().contains("doc#33"), "trace 含三源命中 id");
    }

    /** need=true 但某源为空：其余源照常注入。 */
    @Test
    void needTruePartialSources() throws Exception {
        captureTraces();
        stubGateway("{\"need\": true, \"query\": \"村里最近的大事\", \"reason\": \"想知道近况\"}");
        stubClock();
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt()))
                .thenReturn(List.of(memory(11L, 3L, "我在村口见过一队陌生商贩")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt()))
                .thenReturn(List.of());
        ProactiveRetrievalService.ProactiveResult result =
                service.maybeRetrieve(100L, "npc:3", 1L, "独自走在村口");
        assertNotNull(result);
        assertTrue(result.segment().contains("[经历]"), "仅角色记忆源注入");
        assertFalse(result.segment().contains("[世界]"), "无世界大事记不注入");
        assertEquals(1, result.hits().size());
    }

    /** need=true 全部源无命中：写 trace 不注入（fail-open 零注入）。 */
    @Test
    void needTrueNoHits() throws Exception {
        captureTraces();
        stubGateway("{\"need\": true, \"query\": \"一件我没听说过的事\", \"reason\": \"想查证\"}");
        stubClock();
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt())).thenReturn(List.of());
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt())).thenReturn(List.of());
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "听到一件奇怪的事"));
        List<AiCallTrace> tr = proactiveTraces();
        assertEquals(1, tr.size());
        assertTrue(tr.get(0).getContent().contains("need=true"), "need=true 无命中也留痕");
        assertTrue(tr.get(0).getContent().contains("检索无命中"));
    }

    /** need=true 但 query 缺失：降级用情境文本检索（fail-open 兜底）。 */
    @Test
    void needTrueEmptyQueryFallsBackToSituation() throws Exception {
        captureTraces();
        stubGateway("{\"need\": true, \"query\": \"\", \"reason\": \"想起来了\"}");
        stubClock();
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt()))
                .thenReturn(List.of(memory(11L, 3L, "茶馆老板娘今日换了新茶")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt())).thenReturn(List.of());
        ProactiveRetrievalService.ProactiveResult result =
                service.maybeRetrieve(100L, "npc:3", 1L, "走进茶馆，老板娘正招呼客人");
        assertNotNull(result, "query 空 → 情境文本检索仍可命中");
        verify(agentMemoryService).retrieve(eq(100L), eq("npc:3"), org.mockito.ArgumentMatchers.contains("茶馆"), anyInt());
    }

    /** AI 判定失败（网关抛异常）→ fail-open 跳过（不写 proactive trace、不冷却）。 */
    @Test
    void failOpenOnAiFailure() throws Exception {
        captureTraces();
        when(promptTemplateService.render(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(ProactiveRetrievalService.SCENE_PROACTIVE_RETRIEVAL), any()))
                .thenReturn("system");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("gateway down"));
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境"));
        assertTrue(proactiveTraces().isEmpty(), "AI 失败不写 proactive trace");
        // 未冷却：下一次可重试
        stubGateway("{\"need\": true, \"query\": \"某事\", \"reason\": \"r\"}");
        stubClock();
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt()))
                .thenReturn(List.of(memory(11L, 3L, "一些记忆")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt())).thenReturn(List.of());
        assertNotNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境"), "AI 失败不冷却，下次可重试");
    }

    /** 判定 JSON 非法 → fail-open 跳过。 */
    @Test
    void invalidVerdictFailsOpen() throws Exception {
        captureTraces();
        stubGateway("不是JSON");
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境"));
        assertTrue(proactiveTraces().isEmpty());
    }

    /** 批 4 记忆与反思链·段 2F 对齐：主动检索判定前置 memoryChainContext（段1A+1C+2D+2E）。 */
    @Test
    void judgePrependsMemoryChainContextToPrompt() throws Exception {
        captureTraces();
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】\n青禾村四面环山。\n\n【我的身份与处境】\n姓名：林安\n\n"
                        + "【当前世界动态】\n世界历 0420-05-15 06时00分 · 夏季\n\n【当前状态】\n- 需求：饥饿 68/100（略饿）");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        stubGateway("{\"need\": false, \"query\": \"\", \"reason\": \"日常熟悉场景，无需额外想起\"}");
        stubClock();
        stubMemoryOnly();
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "日常情境"));
        // 捕获实际请求：user 消息应前置记忆链段位（段1A 世界观 + 段1C 角色卡 + 段2D 世界动态 + 段2E 状态）
        org.mockito.ArgumentCaptor<AiChatRequest> reqCaptor = org.mockito.ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), reqCaptor.capture());
        String user = reqCaptor.getValue().messages().get(1).content();
        assertTrue(user.contains("【世界观】"), "应含段1A世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C角色卡: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D世界动态: " + user);
        assertTrue(user.contains("【当前状态】"), "应含段2E当前状态: " + user);
        assertTrue(user.contains("日常熟悉场景") || user.contains("prompt"), "模板仍在注入段之后: " + user);
    }

    // ==================== 冷却 ====================

    /** need=true 后进入冷却：冷却期内再次自问直接跳过（不再调 AI）。 */
    @Test
    void cooldownAfterNeedTrueBlocksSecondAttempt() throws Exception {
        captureTraces();
        stubGateway("{\"need\": true, \"query\": \"上次交易的事\", \"reason\": \"想起\"}");
        stubClock();
        stubMemoryOnly();
        assertNotNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境一"));
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), eq(100L), any(), any());
        // 第二次（同游戏小时，diff=0 < 2）：冷却直接跳过，不再调 AI
        assertNull(service.maybeRetrieve(100L, "npc:3", 1L, "情境二"));
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), eq(100L), any(), any());
        assertTrue(proactiveTraces().stream().noneMatch(t -> t.getContent() != null && t.getContent().contains("need=false")),
                "冷却跳过不写 need=false trace");
    }

    /** 冷却窗口判定（包可见）：游戏小时差 < 冷却 → 在冷却内。 */
    @Test
    void withinCooldownWindowGameHours() {
        LocalDateTime last = LocalDateTime.now().minusMinutes(10);
        // last → 100h，now → 101h，差 1 < 2 → 冷却内
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenAnswer(inv -> {
            LocalDateTime at = inv.getArgument(1);
            return Math.abs(java.time.Duration.between(last, at).getSeconds()) < 5 ? 100L : 101L;
        });
        assertTrue(service.withinCooldownWindow(100L, last), "1 游戏小时差 < 2 → 冷却内");
    }

    /** 冷却窗口判定：差 ≥ 冷却 → 已过期（放行）。 */
    @Test
    void withinCooldownWindowExpired() {
        LocalDateTime last = LocalDateTime.now().minusMinutes(10);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenAnswer(inv -> {
            LocalDateTime at = inv.getArgument(1);
            return Math.abs(java.time.Duration.between(last, at).getSeconds()) < 5 ? 100L : 103L;
        });
        assertFalse(service.withinCooldownWindow(100L, last), "3 游戏小时差 ≥ 2 → 已过期");
    }

    /** 冷却=0（关闭冷却）：恒放行。 */
    @Test
    void cooldownZeroAlwaysAllows() {
        ReflectionTestUtils.setField(service, "cooldownGameHours", 0L);
        assertFalse(service.withinCooldownWindow(100L, LocalDateTime.now().minusMinutes(1)), "冷却 0 恒放行");
    }

    /** 世界时钟不可用：回退真实小时判定（同刻差 < 2 小时 → 冷却内）。 */
    @Test
    void withinCooldownWindowRealTimeFallback() {
        ReflectionTestUtils.setField(service, "worldClockService", null);
        assertTrue(service.withinCooldownWindow(100L, LocalDateTime.now().minusMinutes(1)), "回退真实时间仍冷却内");
        assertFalse(service.withinCooldownWindow(100L, LocalDateTime.now().minusHours(5)), "超真实 2 小时已过期");
    }

    // ==================== 静态纯逻辑 ====================

    /** parseVerdict：need=true 完整解析。 */
    @Test
    void parseVerdictNeedTrue() {
        var v = ProactiveRetrievalService.parseVerdict(
                "{\"need\": true, \"query\": \"上次与张屠户的交易\", \"reason\": \"关系到本次交易\"}");
        assertNotNull(v);
        assertTrue(v.need());
        assertEquals("上次与张屠户的交易", v.query());
        assertEquals("关系到本次交易", v.reason());
    }

    /** parseVerdict：need=false。 */
    @Test
    void parseVerdictNeedFalse() {
        var v = ProactiveRetrievalService.parseVerdict("{\"need\": false, \"reason\": \"熟悉\"}");
        assertNotNull(v);
        assertFalse(v.need());
        assertNull(v.query());
    }

    /** parseVerdict：非法/空白 → null。 */
    @Test
    void parseVerdictInvalid() {
        assertNull(ProactiveRetrievalService.parseVerdict(null));
        assertNull(ProactiveRetrievalService.parseVerdict(""));
        assertNull(ProactiveRetrievalService.parseVerdict("not json"));
        assertNull(ProactiveRetrievalService.parseVerdict("{\"query\": \"x\"}"));
    }

    /** buildSegment：渲染各源标签；无命中 → 空串。 */
    @Test
    void buildSegmentRenders() {
        String seg = ProactiveRetrievalService.buildSegment("q", "r", List.of(
                new ProactiveRetrievalService.Hit("mem", 1L, "经历A"),
                new ProactiveRetrievalService.Hit("proj", 2L, "世界B"),
                new ProactiveRetrievalService.Hit("doc", 3L, "所知C")), 300);
        assertTrue(seg.startsWith("【主动想起】"));
        assertTrue(seg.contains("[经历] 经历A"));
        assertTrue(seg.contains("[世界] 世界B"));
        assertTrue(seg.contains("[所知] 所知C"));
        assertEquals("", ProactiveRetrievalService.buildSegment("q", "r", List.of(), 300));
        assertEquals("", ProactiveRetrievalService.buildSegment("q", "r", null, 300));
    }

    /** buildSegment：2026-10-10 决策不设 token 预算——命中全量注入（不再按预算丢弃后续）。 */
    @Test
    void buildSegmentKeepsAllHitsWithoutBudget() {
        String longText = "长".repeat(500);
        String seg = ProactiveRetrievalService.buildSegment("q", "r", List.of(
                new ProactiveRetrievalService.Hit("mem", 1L, longText),
                new ProactiveRetrievalService.Hit("proj", 2L, longText)), 64);
        assertTrue(seg.contains("[经历]"), "首条保留");
        assertTrue(seg.contains("[世界]"), "不设预算：后续命中全量保留（此前按 token 预算丢弃）");
    }

    /** maxTokensOf：默认 300，0/负回退。 */
    @Test
    void maxTokensOfDefault() {
        assertEquals(300, ProactiveRetrievalService.maxTokensOf(0));
        assertEquals(300, ProactiveRetrievalService.maxTokensOf(-5));
        assertEquals(200, ProactiveRetrievalService.maxTokensOf(200));
    }

    /** kindLabel：mem/proj/doc 标签。 */
    @Test
    void kindLabel() {
        assertEquals("经历", ProactiveRetrievalService.kindLabel("mem"));
        assertEquals("世界", ProactiveRetrievalService.kindLabel("proj"));
        assertEquals("所知", ProactiveRetrievalService.kindLabel("doc"));
        assertEquals("记忆", ProactiveRetrievalService.kindLabel("other"));
    }

    /** abbreviate：截断加省略号。 */
    @Test
    void abbreviateTrims() {
        assertNull(ProactiveRetrievalService.abbreviate(null, 5));
        assertNull(ProactiveRetrievalService.abbreviate("   ", 5));
        assertEquals("12345…", ProactiveRetrievalService.abbreviate("1234567890", 5));
        assertEquals("abc", ProactiveRetrievalService.abbreviate("abc", 5));
    }

    // ==================== 私有辅助 ====================

    private void stubMemoryOnly() {
        when(agentMemoryService.retrieve(eq(100L), eq("npc:3"), anyString(), anyInt()))
                .thenReturn(List.of(memory(11L, 3L, "我上回在茶馆见过一个熟客")));
        when(memoryRepository.findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(100L, 0))
                .thenReturn(List.of());
        when(knowledgeRetrievalService.search(anyLong(), eq(100L), eq(3L), anyString(), anyInt())).thenReturn(List.of());
    }
}
