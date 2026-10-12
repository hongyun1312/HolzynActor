package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SelfCheckStrategy 自省-行为一致性校验（自省漂移检测·候选 A）单元测试。
 * <p>验证：门控（特殊 NPC/非 programmatic/概率抽样/反思窗口）、直查最近反思洞察、
 * AI 判定（一致/漂移/fail-open 降级）、trace 落点（stepType=self_check + ref 前缀）、
 * 旁路语义（applies 恒 false/decide 恒 empty 不产出决策）。</p>
 */
class SelfCheckStrategyTest {

    private final Consumer<AiCallTrace>[] capturedSink = new Consumer[1];
    private final List<AiCallTrace> traces = new ArrayList<>();

    private SelfCheckStrategy strategy;
    private ActorMemoryRepository memoryRepository;
    private WorldClockService worldClockService;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;

    @BeforeEach
    void setUp() {
        strategy = new SelfCheckStrategy();
        memoryRepository = mock(ActorMemoryRepository.class);
        worldClockService = mock(WorldClockService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(strategy, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(strategy, "worldClockService", worldClockService);
        ReflectionTestUtils.setField(strategy, "promptTemplateService", promptTemplateService);
        ReflectionTestUtils.setField(strategy, "aiChatGateway", aiChatGateway);
        ReflectionTestUtils.setField(strategy, "usageLogService", usageLogService);
        // 测试确定性：抽样率 1.0（必校验）+ 反思窗口 1 游戏日
        ReflectionTestUtils.setField(strategy, "sampleRate", 1.0);
        ReflectionTestUtils.setField(strategy, "reflectionTopN", 3);
        ReflectionTestUtils.setField(strategy, "reflectionWindowGameDays", 1L);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    private void captureTraces() {
        capturedSink[0] = traces::add;
        AiCallContext.setSink(capturedSink[0]);
    }

    /** 过滤出本校验器写的环节 trace（stepType=self_check；AI 调用行 scene 同名但 stepType=ai_call）。 */
    private List<AiCallTrace> selfCheckTraces() {
        return traces.stream().filter(t -> "self_check".equals(t.getStepType())).toList();
    }

    private ActorCharacter character() {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(3L);
        ch.setProjectId(100L);
        ch.setName("林安");
        ch.setType("special");
        return ch;
    }

    private DecisionContext ctx(NpcKind kind, String brainLevel) {
        return new DecisionContext(100L, character(), 1L, kind, brainLevel, "情境", null, null, false);
    }

    private ActorMemory reflection(String content, LocalDateTime createdAt) {
        ActorMemory m = new ActorMemory();
        m.setId(1L);
        m.setProjectId(100L);
        m.setCharacterId(3L);
        m.setType(ActorMemoryTypes.TYPE_REFLECTION);
        m.setContent(content);
        m.setCreatedAt(createdAt);
        m.setDeleted(0);
        return m;
    }

    private void stubGateway(String json) throws Exception {
        when(promptTemplateService.render(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(json, "qwen", 100, 50, 150, 0, 100, 1L));
    }

    // ==================== 旁路语义（BrainStrategy 占位） ====================

    /** 旁路校验器：永不作为产出策略进链。 */
    @Test
    void neverProducesDecision() {
        assertFalse(strategy.applies(ctx(NpcKind.SPECIAL, "full")), "applies 恒 false（不进产出链）");
        assertTrue(strategy.decide(ctx(NpcKind.SPECIAL, "full")).isEmpty(), "decide 恒 empty（不产出决策）");
        assertEquals("self_check", strategy.name());
    }

    // ==================== 门控 ====================

    /** 普通 NPC 不校验（无反思机制）。 */
    @Test
    void skipsCommonNpc() {
        captureTraces();
        strategy.check(ctx(NpcKind.COMMON, "full"), Map.of("action", "吃饭"));
        assertTrue(selfCheckTraces().isEmpty(), "普通 NPC 不校验");
    }

    /** programmatic 档不校验（程序化决策无 AI 意志）。 */
    @Test
    void skipsProgrammaticBrainLevel() {
        captureTraces();
        strategy.check(ctx(NpcKind.SPECIAL, "programmatic"), Map.of("action", "吃饭"));
        assertTrue(selfCheckTraces().isEmpty(), "programmatic 档不校验");
    }

    /** 概率抽样未命中 → 跳过（不校验不 trace）。 */
    @Test
    void skipsWhenSampleNotHit() {
        captureTraces();
        ReflectionTestUtils.setField(strategy, "sampleRate", 0.0);
        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "吃饭"));
        assertTrue(selfCheckTraces().isEmpty(), "抽样未命中跳过");
    }

    /** 无最近反思洞察 → 跳过（无从校验）。 */
    @Test
    void skipsWhenNoRecentReflection() {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0)).thenReturn(List.of());
        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "吃饭"));
        assertTrue(selfCheckTraces().isEmpty(), "无反思跳过");
    }

    /** 反思在窗口外（超过 1 游戏日）→ 跳过（陈旧反思不参与）。 */
    @Test
    void skipsWhenReflectionOutsideWindow() {
        captureTraces();
        LocalDateTime old = LocalDateTime.now().minusDays(3);
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("我要沉稳", old)));
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(10L);
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "与人争吵"));
        assertTrue(selfCheckTraces().isEmpty(), "窗口外反思跳过");
    }

    // ==================== 判定与 trace ====================

    /** 一致：AI 判定 consistent=true → trace 记「与最近反思一致」。 */
    @Test
    void consistentWritesTrace() throws Exception {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("遇事要冷静", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        stubGateway("{\"consistent\": true, \"reason\": \"决策与反思一致\"}");

        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "先退一步", "reason", "冷静处理"));

        assertEquals(1, selfCheckTraces().size(), "一致也留痕");
        assertEquals("self_check", selfCheckTraces().get(0).getStepType());
        assertTrue(selfCheckTraces().get(0).getContent().contains("自省校验：与最近反思一致"), selfCheckTraces().get(0).getContent());
        assertTrue(selfCheckTraces().get(0).getContent().startsWith("ref=npc:3"), "content 带角色 ref 前缀（反思闭环检索）");
    }

    /** 漂移：AI 判定 consistent=false → trace 记「自省漂移」（明知故犯）。 */
    @Test
    void driftWritesTrace() throws Exception {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("不能再冲动与人争执", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        stubGateway("{\"consistent\": false, \"reason\": \"反思要克制却与人争吵\"}");

        strategy.check(ctx(NpcKind.SPECIAL, "full"),
                Map.of("action", "与邻居大声争吵", "reason", "气不过"));

        assertEquals(1, selfCheckTraces().size());
        assertEquals("self_check", selfCheckTraces().get(0).getStepType());
        assertTrue(selfCheckTraces().get(0).getContent().contains("自省漂移"), selfCheckTraces().get(0).getContent());
        assertTrue(selfCheckTraces().get(0).getContent().contains("反思要克制却与人争吵"), "理由入 trace");
    }

    /** fail-open：AI 判定失败/解析失败 → 不标记（降级，零 trace 干扰）。 */
    @Test
    void aiFailureFailsOpenNoTrace() throws Exception {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("我要沉稳", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        when(promptTemplateService.render(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), eq(100L), eq(SelfCheckStrategy.SCENE_SELF_CHECK), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");
        when(aiChatGateway.chatCompletion(anyLong(), eq(100L), any(), any(AiChatRequest.class)))
                .thenThrow(new RuntimeException("AI 超时"));

        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "与人争吵"));

        assertTrue(selfCheckTraces().isEmpty(), "AI 失败 fail-open：不写漂移 trace");
    }

    /** 批6 收口：self_check 属批4 记忆链·角色视角——AI 判定请求前置 段1A+1C+2D+2E。 */
    @Test
    void aiRequestPrependsMemoryChainContext() throws Exception {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("遇事要冷静", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        stubGateway("{\"consistent\": true, \"reason\": \"一致\"}");
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.memoryChainContext(eq(100L), eq("npc:3"), any()))
                .thenReturn("【世界观】青禾村…\n\n【我的身份与处境】沈砚秋…\n\n【当前世界动态】…\n\n【当前状态】…");
        ReflectionTestUtils.setField(strategy, "aiContextInjector", injector);

        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "先退一步", "reason", "冷静处理"));

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), eq(100L), any(), cap.capture());
        assertTrue(cap.getValue().messages().get(1).content().startsWith("【世界观】"),
                "self_check 请求必须前置 记忆链段位");
    }

    /** 非法判定输出（无 consistent 键）→ 降级不标记。 */
    @Test
    void invalidVerdictFailsOpen() throws Exception {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenReturn(List.of(reflection("我要沉稳", LocalDateTime.now())));
        when(worldClockService.currentEngineGameSecond(100L)).thenReturn(100L * 3600L);
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        stubGateway("{\"foo\": \"bar\"}");

        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "与人争吵"));

        assertTrue(selfCheckTraces().isEmpty(), "非法判定输出 fail-open");
    }

    /** 校验异常隔离：仓库异常不抛出（绝不干扰主决策流程）。 */
    @Test
    void exceptionIsolated() {
        captureTraces();
        when(memoryRepository.findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                100L, 3L, ActorMemoryTypes.TYPE_REFLECTION, 0))
                .thenThrow(new RuntimeException("仓库炸了"));

        strategy.check(ctx(NpcKind.SPECIAL, "full"), Map.of("action", "吃饭"));

        assertTrue(selfCheckTraces().isEmpty(), "异常隔离，不抛出不 trace");
    }

    // ==================== 纯逻辑 ====================

    /** 判定解析：合法 JSON → 一致。 */
    @Test
    void parseVerdictConsistent() {
        SelfCheckStrategy.SelfCheckVerdict v = SelfCheckStrategy.parseVerdict(
                "{\"consistent\": true, \"reason\": \"符合\"}");
        assertNotNull(v);
        assertTrue(v.consistent());
        assertEquals("符合", v.reason());
    }

    /** 判定解析：合法 JSON → 漂移。 */
    @Test
    void parseVerdictDrift() {
        SelfCheckStrategy.SelfCheckVerdict v = SelfCheckStrategy.parseVerdict(
                "{\"consistent\": false, \"reason\": \"矛盾\"}");
        assertNotNull(v);
        assertFalse(v.consistent());
    }

    /** 判定解析：null/空白/非法 → null。 */
    @Test
    void parseVerdictInvalid() {
        assertNull(SelfCheckStrategy.parseVerdict(null));
        assertNull(SelfCheckStrategy.parseVerdict("  "));
        assertNull(SelfCheckStrategy.parseVerdict("不是 JSON"));
        assertNull(SelfCheckStrategy.parseVerdict("{\"foo\": 1}"));
    }

    /** 决策文本提取：特殊链 ReAct 键集优先 action。 */
    @Test
    void extractDecisionTextPrecedence() {
        assertEquals("去茶馆", SelfCheckStrategy.extractDecisionText(Map.of(
                "action", "去茶馆", "actionDetail", "detail", "type", "move")));
        assertEquals("detail", SelfCheckStrategy.extractDecisionText(Map.of(
                "actionDetail", "detail")));
        assertNull(SelfCheckStrategy.extractDecisionText(Map.of("type", "move")));
        assertNull(SelfCheckStrategy.extractDecisionText(null));
    }

    /** 反思窗口判定：窗口内 true / 窗口外 false。 */
    @Test
    void withinReflectionWindow() {
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(10L);
        // 当前 100 游戏小时，创建时刻换算 10 游戏小时 → 差 90 小时 > 24 → 窗口外
        assertFalse(strategy.withinReflectionWindow(100L, LocalDateTime.now(), 100L));
        // 差 20 小时 < 24 → 窗口内
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(80L);
        assertTrue(strategy.withinReflectionWindow(100L, LocalDateTime.now(), 100L));
        // 无法换算 → 窗口外
        when(worldClockService.gameHourAt(eq(100L), any(LocalDateTime.class))).thenReturn(null);
        assertFalse(strategy.withinReflectionWindow(100L, LocalDateTime.now(), 100L));
        assertFalse(strategy.withinReflectionWindow(100L, null, 100L));
    }

    /** 字符串截断。 */
    @Test
    void abbreviateTrims() {
        assertEquals("abc", SelfCheckStrategy.abbreviate("abc", 10));
        assertEquals("abcd…", SelfCheckStrategy.abbreviate("abcdefg", 4));
        assertNull(SelfCheckStrategy.abbreviate(null, 4));
        assertNull(SelfCheckStrategy.abbreviate("  ", 4));
    }
}
