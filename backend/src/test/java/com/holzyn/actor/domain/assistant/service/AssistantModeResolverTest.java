package com.holzyn.actor.domain.assistant.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助手双模式判定器单元测试。
 * <p>职责：验证三层判定——显式指令（逐轮覆盖）> 意图分类（数值/精确需求）> 会话偏好（默认叙事）。</p>
 */
class AssistantModeResolverTest {

    private final AssistantModeResolver resolver = new AssistantModeResolver();

    @Test
    void explicitFactInstructionWins() {
        var d = resolver.resolve("沈砚秋饥饿值多少？用数据说话", "narrative");
        assertEquals("fact", d.mode());
        assertEquals("沈砚秋饥饿值多少？", d.cleanQuestion());
    }

    @Test
    void explicitNarrativeInstructionWins() {
        var d = resolver.resolve("粮价多少？用叙事的方式说", "fact");
        assertEquals("narrative", d.mode());
        assertEquals("粮价多少？", d.cleanQuestion());
    }

    @Test
    void numericIntentAutoFact() {
        var d = resolver.resolve("沈砚秋的饥饿值是多少", "narrative");
        assertEquals("fact", d.mode());
    }

    @Test
    void casualQuestionFallsBackToSessionDefaultNarrative() {
        var d = resolver.resolve("沈砚秋在做什么", "narrative");
        assertEquals("narrative", d.mode());
    }

    @Test
    void casualQuestionFallsBackToSessionDefaultFact() {
        var d = resolver.resolve("沈砚秋在做什么", "fact");
        assertEquals("fact", d.mode());
    }

    @Test
    void blankQuestionDefaultsNarrative() {
        var d = resolver.resolve("   ", null);
        assertEquals("narrative", d.mode());
    }

    @Test
    void intentKeywordListCoversCommonCases() {
        assertTrue(resolver.resolve("粮价最近为什么涨", "narrative").mode().equals("fact"));
        assertTrue(resolver.resolve("帮我排查一下这次调用失败", "narrative").mode().equals("fact"));
    }

    // ==================== M5：三参重载（API 层逐轮覆盖，不污染问题文本） ====================

    @Test
    void overrideFactWinsAndKeepsQuestionIntact() {
        var d = resolver.resolve("沈砚秋在做什么", "narrative", "fact");
        assertEquals("fact", d.mode());
        // 覆盖值不拼入问题：净问题保持原样（旧实现会残留「fact」）
        assertEquals("沈砚秋在做什么", d.cleanQuestion());
    }

    @Test
    void overrideNarrativeWinsOverSessionFact() {
        var d = resolver.resolve("粮价多少？", "fact", "narrative");
        assertEquals("narrative", d.mode());
        assertEquals("粮价多少？", d.cleanQuestion());
    }

    @Test
    void invalidOverrideFallsBackToFullResolution() {
        // 非法覆盖 → 回退完整判定链（意图分类仍生效）
        var d = resolver.resolve("沈砚秋的饥饿值是多少", "narrative", "bogus");
        assertEquals("fact", d.mode());
    }

    @Test
    void nullOverrideUsesDefaultResolution() {
        var d = resolver.resolve("沈砚秋在做什么", "narrative", null);
        assertEquals("narrative", d.mode());
        assertEquals("沈砚秋在做什么", d.cleanQuestion());
    }

    @Test
    void overrideWithExplicitInstructionStripsIt() {
        // 覆盖为 fact 且问题含显式叙事指令：覆盖层优先，且显式指令剥离防污染正文
        var d = resolver.resolve("粮价多少？用叙事的方式说", "narrative", "fact");
        assertEquals("fact", d.mode());
        assertEquals("粮价多少？", d.cleanQuestion());
    }
}
