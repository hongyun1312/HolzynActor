package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.MacroProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观 AI 服务单元测试（宏观社会演化系统 M2/M5，domain/world/service，同包，mock 网关）。
 * <p>职责：验证重大行动 AI 判定（JSON 解析/非法宏拒绝/delta 钳制/失败回退 null）、
 * 阈值 AI 叙事（解析/失败回退 null/因果上下文）、独立每日预算（耗尽转兜底零阻断）、
 * M5 AI 强度档（programmatic 零 AI / max 无预算限制）。</p>
 */
class MacroAiServiceTest {

    private MacroProperties props;
    private PromptTemplateService promptTemplateService;
    private AiSpringChatGateway aiChatGateway;
    private UsageLogService usageLogService;
    private MacroAiService service;
    private AiIntensityGate aiIntensityGate;

    @BeforeEach
    void setUp() {
        props = new MacroProperties();
        props.setDailyAiCap(8);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        // 真实闸门 + mock 仓库（无引擎行 → full 默认档）
        ActorWorldEngineRepository engineRepo = Mockito.mock(ActorWorldEngineRepository.class);
        when(engineRepo.findByProjectId(anyLong())).thenReturn(Optional.empty());
        aiIntensityGate = new AiIntensityGate(engineRepo);
        MacroStateService macroStateService = Mockito.mock(MacroStateService.class);
        service = new MacroAiService(props, promptTemplateService, aiChatGateway, usageLogService,
                aiIntensityGate, macroStateService, new ObjectMapper());
        when(promptTemplateService.render(anyLong(), any(), anyString(), any())).thenReturn("prompt");
        when(promptTemplateService.systemMessage(anyLong(), any(), anyString(), any())).thenReturn("sys");
    }

    private void stubAi(String content) {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(content, "local-model", 100, 50, 150, 1L));
    }

    /**
     * 重大行动 AI 判定：有效 JSON → 判定对象（economy market_activity +1.2）。
     */
    @Test
    void judgeImpactParsesValidJson() {
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\","
                + "\"delta\":1.2,\"reason\":\"持续经营抬升集市\"}");
        MacroAiService.ImpactJudgment j = service.judgeImpact(1L, 100L, 5L, "special:110", "沈砚秋",
                "在集市经营米行", "trade", "青禾集市", 4, 5L * 86400L);
        assertNotNull(j);
        assertTrue(j.hasImpact());
        assertEquals("economy", j.targetMacro());
        assertEquals("market_activity", j.indicator());
        assertEquals(1.2, j.delta(), 0.001);
    }

    /**
     * hasImpact=false → 不叠加。
     */
    @Test
    void judgeImpactFalseWhenNoImpact() {
        stubAi("{\"hasImpact\":false,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\","
                + "\"delta\":0,\"reason\":\"一次普通寒暄\"}");
        MacroAiService.ImpactJudgment j = service.judgeImpact(1L, 100L, 5L, "special:110", "林大",
                "与邻居闲谈", "social", "茶馆", 4, 5L * 86400L);
        assertNotNull(j);
        assertTrue(!j.hasImpact());
    }

    /**
     * 非法 targetMacro / 超钳制 delta → 拒绝或钳制（防 AI 离谱值）。
     */
    @Test
    void judgeImpactRejectsInvalidMacroAndClampsDelta() {
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"galaxy\",\"indicator\":\"x\",\"delta\":999,\"reason\":\"x\"}");
        MacroAiService.ImpactJudgment bad = service.judgeImpact(1L, 100L, 5L, "special:110", "a",
                "行动", "custom", null, 4, 5L * 86400L);
        assertTrue(bad == null || !bad.hasImpact(), "非法目标子系统应被拒绝");
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"faction\",\"indicator\":\"王氏宗族\",\"delta\":999,\"reason\":\"x\"}");
        MacroAiService.ImpactJudgment clamp = service.judgeImpact(1L, 100L, 5L, "special:110", "a",
                "行动", "custom", null, 4, 5L * 86400L);
        assertNotNull(clamp);
        assertTrue(clamp.delta() <= 10.0, "势力 delta 应被钳制在 ±10");
    }

    /**
     * AI 失败（网关抛异常）→ 返回 null（调用方规则映射兜底，零阻断）。
     */
    @Test
    void judgeImpactFallsBackToNullOnFailure() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("AI 调用失败"));
        MacroAiService.ImpactJudgment j = service.judgeImpact(1L, 100L, 5L, "special:110", "a",
                "行动", "custom", null, 4, 5L * 86400L);
        assertNull(j);
    }

    /**
     * 独立每日预算：耗尽后不再调用 AI（返回 null），预算跨日重置。
     */
    @Test
    void dailyBudgetExhaustedThenReset() {
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\","
                + "\"delta\":0.5,\"reason\":\"x\"}");
        props.setDailyAiCap(2);
        assertNotNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L));
        assertNotNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L));
        assertNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L),
                "预算耗尽后应转规则兜底");
        // 跨游戏日重置
        assertNotNull(service.judgeImpact(1L, 100L, 6L, "r", "n", "a", "custom", null, 4, 6L * 86400L));
    }

    /**
     * 阈值 AI 叙事：有效 JSON → Narrative（title/narrative）。
     */
    @Test
    void narrateParsesJson() {
        stubAi("{\"title\":\"粮行告急\",\"narrative\":\"本村集市的粮行前挤满了人……\"}");
        MacroAiService.Narrative n = service.narrate(1L, 100L, 5L, "economy", "price_surge",
                "grain_price", 100.0, 130.0, "规则草稿");
        assertNotNull(n);
        assertEquals("粮行告急", n.title());
        assertTrue(n.content().contains("粮行前挤满了人"));
    }

    /**
     * 叙事失败（无 JSON）→ 返回 null（调用方规则模板兜底）。
     */
    @Test
    void narrateFallsBackOnFailure() {
        stubAi("这不是 JSON");
        MacroAiService.Narrative n = service.narrate(1L, 100L, 5L, "economy", "price_surge",
                "grain_price", 100.0, 130.0, "规则草稿");
        assertNull(n);
        verify(aiChatGateway, Mockito.atMost(2)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    /**
     * 预算为 0 → 直接不调用 AI。
     */
    @Test
    void zeroBudgetNeverCallsAi() {
        props.setDailyAiCap(0);
        MacroAiService.ImpactJudgment j = service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom",
                null, 4, 5L * 86400L);
        assertNull(j);
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== M5 AI 强度档（programmatic/light/full/max） ====================

    /**
     * programmatic 纯规则档：宏观 AI 全部跳过（判定/叙事均不调用网关）。
     */
    @Test
    void programmaticIntensityBlocksAllAi() {
        aiIntensityGate.setIntensity(100L, AiIntensityGate.PROGRAMMATIC);
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\",\"delta\":1.2,\"reason\":\"x\"}");
        assertNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L),
                "纯规则档重大行动判定应跳过");
        assertNull(service.narrate(1L, 100L, 5L, "economy", "price_surge", "grain_price", 100.0, 130.0, "草稿"),
                "纯规则档叙事应跳过");
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
        aiIntensityGate.setIntensity(100L, AiIntensityGate.FULL);
    }

    /**
     * max 全 AI 档：无预算限制——单日预算 1 也能连续多次调用。
     */
    @Test
    void maxIntensityBypassesBudget() {
        aiIntensityGate.setIntensity(100L, AiIntensityGate.MAX);
        props.setDailyAiCap(1);
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\",\"delta\":0.5,\"reason\":\"x\"}");
        assertNotNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L));
        assertNotNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L),
                "max 档预算不限，连续调用不耗尽");
        aiIntensityGate.setIntensity(100L, AiIntensityGate.FULL);
    }

    /**
     * light 轻 AI 档：预算减半（cap=8 → 4）。
     */
    @Test
    void lightIntensityHalvesBudget() {
        aiIntensityGate.setIntensity(100L, AiIntensityGate.LIGHT);
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\",\"delta\":0.5,\"reason\":\"x\"}");
        for (int i = 0; i < 4; i++) {
            assertNotNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L),
                    "light 档 4 次内应成功（第 " + (i + 1) + " 次）");
        }
        assertNull(service.judgeImpact(1L, 100L, 5L, "r", "n", "a", "custom", null, 4, 5L * 86400L),
                "light 档第 5 次应耗尽（预算 4）");
        aiIntensityGate.setIntensity(100L, AiIntensityGate.FULL);
    }

    // ==================== 批5/批6 世界视角前缀（段1A 世界观 + 段1C 角色 + 段2D 世界动态） ====================

    /**
     * 宏观事件叙事（macro_event）属「世界视角」——前置 段1A【世界观】+ 段2D 当前世界动态
     * （世界历时刻/季节/天气），叙事贴合世界设定与当下世界。
     */
    @Test
    void narrate_prependsWorldDynamicsPrefix() {
        stubAi("{\"title\":\"粮行告急\",\"narrative\":\"本村集市的粮行前挤满了人……\"}");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.worldContextPrefix(100L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n青禾村历 0420年09月15日 午时 · 秋季 · 天气晴");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);

        MacroAiService.Narrative n = service.narrate(1L, 100L, 5L, "economy", "price_surge",
                "grain_price", 100.0, 130.0, "规则草稿");
        assertNotNull(n);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.startsWith("【世界观】"), "应前置段1A 世界观: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + user);
        assertTrue(user.contains("秋季"), "应含季节: " + user);
        assertTrue(user.contains("prompt"), "模板 prompt 保留: " + user);
    }

    /**
     * 重大行动宏观影响判定（macro_impact）属「世界视角」——前置 段1A【世界观】+ 段1C【角色身份】
     * （行动者 NPC）+ 段2D 当前世界动态（2026-10-10 用户指示：至少注入世界观与角色身份）。
     */
    @Test
    void judgeImpact_prependsWorldDynamicsPrefix() {
        stubAi("{\"hasImpact\":true,\"targetMacro\":\"economy\",\"indicator\":\"market_activity\","
                + "\"delta\":1.2,\"reason\":\"持续经营抬升集市\"}");
        com.holzyn.actor.domain.agent.context.AiContextInjector inj =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(inj.worldContextPrefix(100L, "special:110"))
                .thenReturn("【世界观】青禾村\n\n【我的身份与处境】沈砚秋，青禾村村长……\n\n"
                        + "【当前世界动态】\n青禾村历 0420年09月15日 午时 · 秋季 · 天气晴");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", inj);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);

        MacroAiService.ImpactJudgment j = service.judgeImpact(1L, 100L, 5L, "special:110", "沈砚秋",
                "在集市经营米行", "trade", "青禾集市", 4, 5L * 86400L);
        assertNotNull(j);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(user.startsWith("【世界观】"), "应前置段1A 世界观: " + user);
        assertTrue(user.contains("【我的身份与处境】"), "应含段1C 行动者身份: " + user);
        assertTrue(user.contains("【当前世界动态】"), "应含段2D 当前世界动态: " + user);
        assertTrue(user.contains("prompt"), "模板 prompt 保留: " + user);
    }

    /** 注入器未装配 → 宏观请求不注入段前缀（零阻断，行为与批5 前一致）。 */
    @Test
    void narrate_skipsPrefixWhenInjectorMissing() {
        stubAi("{\"title\":\"粮行告急\",\"narrative\":\"本村集市的粮行前挤满了人……\"}");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", null);
        org.mockito.ArgumentCaptor<com.holzyn.actor.ai.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.holzyn.actor.ai.AiChatRequest.class);

        MacroAiService.Narrative n = service.narrate(1L, 100L, 5L, "economy", "price_surge",
                "grain_price", 100.0, 130.0, "规则草稿");
        assertNotNull(n);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), Mockito.isNull(), reqCaptor.capture());
        String user = String.valueOf(reqCaptor.getValue().messages().get(1).content());
        assertTrue(!user.contains("【当前世界动态】"), "未装配 → 不注入段2D: " + user);
        assertTrue(user.contains("prompt"), "模板 prompt 保留: " + user);
    }
}
