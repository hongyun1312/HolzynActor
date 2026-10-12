package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PromptService 单测（domain/character/service，同包）。
 * <p>职责：锁定「本场背景」占位符契约（2026-09-25 社会对话层说话意愿修复 A 层）——
 * 群聊编排 Prompt 必须把触发本次交谈的缘由（世界事件文本 / 日常闲聊说明）传给模板，
 * 缺失时给兜底文案（不得留空占位符）。</p>
 */
class PromptServiceTest {

    private PromptTemplateService templateService;
    private PromptService service;

    @BeforeEach
    void setUp() {
        templateService = Mockito.mock(PromptTemplateService.class);
        service = new PromptService(new ObjectMapper(), templateService);
        when(templateService.render(anyLong(), any(), anyString(), any())).thenReturn("rendered");
    }

    /** 背景传入 → 占位符 background = 原文（开场轮「有话可议」的事实源）。 */
    @Test
    void groupOrchestratorPromptPassesBackground() {
        service.buildGroupOrchestratorPrompt(1L, 1L, "成员摘要", "最近消息", "世界事件后的自发回应——村口老槐树被雷劈了");

        Map<String, Object> ph = capturePlaceholders();
        assertEquals("世界事件后的自发回应——村口老槐树被雷劈了", ph.get("background"));
        assertEquals("成员摘要", ph.get("members_summary"));
        assertEquals("最近消息", ph.get("context"));
    }

    /** 背景为空 → 兜底文案（明确告知「本场为日常自发交谈，无特定缘由」，不留空段）。 */
    @Test
    void groupOrchestratorPromptFallsBackWhenBackgroundBlank() {
        service.buildGroupOrchestratorPrompt(1L, 1L, "成员摘要", "", "   ");

        Map<String, Object> ph = capturePlaceholders();
        assertTrue(String.valueOf(ph.get("background")).contains("日常自发交谈"),
                "空背景必须给兜底文案");
    }

    /** 4 参重载（无背景调用方）委托 5 参重载，同样给兜底文案（占位符契约不破裂）。 */
    @Test
    void groupOrchestratorPromptFourArgDelegates() {
        service.buildGroupOrchestratorPrompt(1L, 1L, "成员摘要", "最近消息");

        Map<String, Object> ph = capturePlaceholders();
        assertTrue(ph.containsKey("background"), "4 参重载也必须填充 background 占位符");
        assertTrue(String.valueOf(ph.get("background")).contains("日常自发交谈"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePlaceholders() {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_GROUP), cap.capture());
        return cap.getValue();
    }

    // ==================== 主动记忆检索注入（P1 ②-1，2026-10-03） ====================

    /** action_gen 6 参重载：主动段传入 → {{proactive_memory}} 占位符 = 段原文。 */
    @Test
    void actionGenPromptPassesProactiveMemory() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "【主动想起】此刻你主动想起…");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("【主动想起】此刻你主动想起…", ph.get("proactive_memory"));
        assertEquals("人设", ph.get("persona_summary"));
        assertEquals("情境", ph.get("situation"));
    }

    /** action_gen 5 参重载委托 6 参：proactive_memory 空占位（不注入，行为不变）。 */
    @Test
    void actionGenPromptFiveArgDelegatesWithEmptyProactive() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("", ph.get("proactive_memory"), "5 参重载 proactive_memory 空占位");
    }

    /** action_gen 6 参：主动段空白 → 空占位（模板零噪音）。 */
    @Test
    void actionGenPromptBlankProactiveIsEmpty() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "   ");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        assertEquals("", cap.getValue().get("proactive_memory"), "空白段 → 空占位");
    }

    // ==================== MUSE 策略切换注入（P1 ①-3，2026-10-02） ====================

    /** action_gen 7 参重载：策略偏好段传入 → {{strategy_preference}} 占位符 = 段原文。 */
    @Test
    void actionGenPromptPassesStrategyPreference() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "【主动想起】…",
                "【策略偏好】面对「农事耕作」这类事你自认把握不大，更倾向谨慎行事");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("【策略偏好】面对「农事耕作」这类事你自认把握不大，更倾向谨慎行事",
                ph.get("strategy_preference"));
        assertEquals("【主动想起】…", ph.get("proactive_memory"), "7 参重载同时传递主动段");
    }

    /** action_gen 6 参重载委托 7 参：strategy_preference 空占位（不注入，行为不变）。 */
    @Test
    void actionGenPromptSixArgDelegatesWithEmptyStrategyPreference() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "【主动想起】…");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("", ph.get("strategy_preference"), "6 参重载 strategy_preference 空占位");
        assertEquals("【主动想起】…", ph.get("proactive_memory"));
    }

    /** action_gen 7 参：策略偏好段空白 → 空占位（模板零噪音）。 */
    @Test
    void actionGenPromptBlankStrategyPreferenceIsEmpty() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "", "   ");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        assertEquals("", cap.getValue().get("strategy_preference"), "空白段 → 空占位");
    }

    // ==================== 潜在世界预测注入（P1 ③-2，2026-10-03） ====================

    /** action_gen 8 参重载：世界走向段传入 → {{world_outlook}} 占位符 = 段原文。 */
    @Test
    void actionGenPromptPassesWorldOutlook() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "【主动想起】…",
                "【策略偏好】谨慎行事", "【未来一天世界走向】明日粮价恐再涨");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("【未来一天世界走向】明日粮价恐再涨", ph.get("world_outlook"));
        assertEquals("【主动想起】…", ph.get("proactive_memory"), "8 参重载同时传递主动段");
        assertEquals("【策略偏好】谨慎行事", ph.get("strategy_preference"), "8 参重载同时传递策略段");
    }

    /** action_gen 7 参重载委托 8 参：world_outlook 空占位（不注入，行为不变）。 */
    @Test
    void actionGenPromptSevenArgDelegatesWithEmptyWorldOutlook() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "【主动想起】…",
                "【策略偏好】谨慎行事");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        Map<String, Object> ph = cap.getValue();
        assertEquals("", ph.get("world_outlook"), "7 参重载 world_outlook 空占位");
        assertEquals("【主动想起】…", ph.get("proactive_memory"));
        assertEquals("【策略偏好】谨慎行事", ph.get("strategy_preference"));
    }

    /** action_gen 8 参：世界走向段空白 → 空占位（模板零噪音）。 */
    @Test
    void actionGenPromptBlankWorldOutlookIsEmpty() {
        service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom", "", "", "   ");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(anyLong(), any(), eq(PromptTemplateService.CODE_ACTION), cap.capture());
        assertEquals("", cap.getValue().get("world_outlook"), "空白段 → 空占位");
    }

    // ==================== 批 6 收口：段位骨架前缀（2026-10-10） ====================

    /** action_gen 前置 段1A【世界观】稳定简报（决策骨架 段1A+1C+2D+2E 补齐）。 */
    @Test
    void actionGenPromptPrependsWorldviewBrief() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldBrief(1L)).thenReturn("青禾村四面环山，村西有溪，沈氏宗族掌村。");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        String prompt = service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom");
        assertTrue(prompt.startsWith("【世界观】\n青禾村四面环山"),
                "action_gen 必须前置 段1A【世界观】稳定简报");
        assertTrue(prompt.contains("rendered"), "模板正文须保留在段 1A 之后");
    }

    /** action_gen 注入器未装配 → 原样渲染（fail-open 零阻断）。 */
    @Test
    void actionGenPromptNoPrefixWhenInjectorMissing() {
        String prompt = service.buildActionGenPrompt(1L, 1L, "人设", "情境", "tom");
        assertEquals("rendered", prompt, "注入器未装配时不得注入前缀");
    }

    /** world_event 前置 段1A【世界观】+ 段2D【当前世界动态】（世界视角）。 */
    @Test
    void worldEventPromptPrependsWorldDynamics() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年05月15日 06时00分 · 夏 · 天气晴");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        String prompt = service.buildWorldEventPrompt(1L, 1L, "世界观", "情境");
        assertTrue(prompt.startsWith("【世界观】"), "world_event 必须前置 段1A 世界观");
        assertTrue(prompt.contains("【当前世界动态】"), "world_event 应含段2D 世界动态");
        assertTrue(prompt.contains("rendered"));
    }

    /** group_orchestrator 前置 段1A【世界观】+ 段2D【当前世界动态】（世界视角统一收口）。 */
    @Test
    void groupOrchestratorPromptPrependsWorldDynamics() {
        com.holzyn.actor.domain.agent.context.AiContextInjector injector =
                Mockito.mock(com.holzyn.actor.domain.agent.context.AiContextInjector.class);
        when(injector.worldContextPrefix(1L, null))
                .thenReturn("【世界观】青禾村\n\n【当前世界动态】\n世界历 0420年05月15日 06时00分 · 夏 · 天气晴");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiContextInjector", injector);

        String prompt = service.buildGroupOrchestratorPrompt(1L, 1L, "成员摘要", "最近消息");
        assertTrue(prompt.startsWith("【世界观】"), "group_orchestrator 必须前置 段1A 世界观");
        assertTrue(prompt.contains("【当前世界动态】"), "group_orchestrator 应含段2D 世界动态");
        assertTrue(prompt.contains("rendered"));
    }

    /** world_event / group_orchestrator 注入器未装配 → 原样渲染（fail-open 零阻断）。 */
    @Test
    void worldViewPromptsNoPrefixWhenInjectorMissing() {
        assertEquals("rendered", service.buildWorldEventPrompt(1L, 1L, "世界观", "情境"));
        assertEquals("rendered", service.buildGroupOrchestratorPrompt(1L, 1L, "成员摘要", "最近消息"));
    }
}