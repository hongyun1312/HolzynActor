package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamicChatModelFactory 单元测试（AI 接入层重构 M1，2026-09-02，方案 §9 M1）。
 * <p>职责：验证模型工厂——①按 ProviderConfig 构建 OpenAiChatModel；②同配置缓存复用（同一实例）；
 * ③配置变更（Key/模型名/Base URL）惰性失效重建（用户决策 Q4 = 惰性校验 + 快照比对）；
 * ④模型名缺失抛 AiCallException；⑤invalidate 显式逐键清除。</p>
 */
class DynamicChatModelFactoryTest {

    private final DynamicChatModelFactory factory = new DynamicChatModelFactory();

    /**
     * 构建：合法配置返回非空模型，且模型名生效。
     */
    @Test
    void buildReturnsChatModel() {
        ProviderConfig config = new ProviderConfig(1L, "https://api.example.com/v1", "sk-test", "my-model", false);
        OpenAiChatModel model = factory.get(config);
        assertEquals("my-model", model.getOptions().getModel());
    }

    /**
     * 缓存复用：同一配置连续两次取到同一实例（避免重复构建）。
     */
    @Test
    void sameConfigReturnsCachedInstance() {
        ProviderConfig config = new ProviderConfig(1L, "https://api.example.com/v1", "sk-test", "my-model", false);
        OpenAiChatModel first = factory.get(config);
        OpenAiChatModel second = factory.get(config);
        assertSame(first, second, "同配置应缓存复用同一模型实例");
        assertEquals(1, factory.cachedCount());
    }

    /**
     * 惰性失效（Key 轮换）：apiKey 变化 → 重建新实例（不同对象），新实例用新 Key。
     */
    @Test
    void apiKeyChangeRebuildsModel() {
        ProviderConfig configA = new ProviderConfig(2L, "https://api.example.com/v1", "sk-old", "m", false);
        OpenAiChatModel first = factory.get(configA);
        ProviderConfig configB = new ProviderConfig(2L, "https://api.example.com/v1", "sk-new", "m", false);
        OpenAiChatModel second = factory.get(configB);
        assertNotSame(first, second, "Key 变化应重建模型");
        assertEquals("sk-new", second.getOptions().getApiKey());
        assertEquals(1, factory.cachedCount(), "同一 providerId 重建后缓存仍只有一条（快照替换）");
    }

    /**
     * 惰性失效（模型切换）：模型名变化 → 重建新实例。
     */
    @Test
    void modelChangeRebuildsModel() {
        ProviderConfig configA = new ProviderConfig(3L, "https://api.example.com/v1", "sk", "model-a", false);
        OpenAiChatModel first = factory.get(configA);
        ProviderConfig configB = new ProviderConfig(3L, "https://api.example.com/v1", "sk", "model-b", false);
        OpenAiChatModel second = factory.get(configB);
        assertNotSame(first, second);
        assertEquals("model-b", second.getOptions().getModel());
    }

    /**
     * 惰性失效（地址变更）：baseUrl 变化 → 重建新实例。
     */
    @Test
    void baseUrlChangeRebuildsModel() {
        ProviderConfig configA = new ProviderConfig(4L, "https://a.example.com/v1", "sk", "m", false);
        OpenAiChatModel first = factory.get(configA);
        ProviderConfig configB = new ProviderConfig(4L, "https://b.example.com/v1", "sk", "m", false);
        OpenAiChatModel second = factory.get(configB);
        assertNotSame(first, second);
        assertEquals("https://b.example.com/v1", second.getOptions().getBaseUrl());
    }

    /**
     * 不同 providerId 各自独立缓存。
     */
    @Test
    void distinctProvidersKeepDistinctCache() {
        OpenAiChatModel m1 = factory.get(new ProviderConfig(5L, "https://a.example.com", "sk1", "m", false));
        OpenAiChatModel m2 = factory.get(new ProviderConfig(6L, "https://b.example.com", "sk2", "m", false));
        assertNotSame(m1, m2);
        assertEquals(2, factory.cachedCount());
    }

    /**
     * 模型名缺失：抛 AiCallException（与旧 Provider「未指定模型」口径一致）。
     */
    @Test
    void missingModelThrowsAiCallException() {
        ProviderConfig config = new ProviderConfig(7L, "https://api.example.com/v1", "sk", null, false);
        AiCallException ex = assertThrows(AiCallException.class, () -> factory.get(config));
        assertTrue(ex.getMessage().contains("未指定模型"));
    }

    /**
     * 配置主键缺失：抛 AiCallException（无法建立缓存键）。
     */
    @Test
    void missingProviderIdThrows() {
        ProviderConfig config = new ProviderConfig(null, "https://api.example.com/v1", "sk", "m", false);
        assertThrows(AiCallException.class, () -> factory.get(config));
    }

    /**
     * invalidate：逐键清除后同配置重建为新实例。
     */
    @Test
    void invalidateClearsCacheKey() {
        ProviderConfig config = new ProviderConfig(8L, "https://api.example.com/v1", "sk", "m", false);
        OpenAiChatModel first = factory.get(config);
        factory.invalidate(8L);
        assertEquals(0, factory.cachedCount());
        OpenAiChatModel second = factory.get(config);
        assertNotSame(first, second);
    }
}
