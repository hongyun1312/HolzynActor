package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatModelProbe 单元测试（AI 接入层重构 M5，2026-09-XX，方案 §9 M5「testProvider 改走 ChatModel 探测」）。
 * <p>职责：验证连通性探测——①成功：最小对话校验通过，返回同形结果 Map（connected/method/status/message/latencyMs）；
 * ②失败：调用异常 → connected=false + 友好消息；③已保存配置走工厂缓存 get()，未保存配置（id=null）
 * 直接 build() 不缓存（设置页 DTO 测试连接场景）。</p>
 */
class ChatModelProbeTest {

    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private ChatModelProbe probe;

    private static final ProviderConfig SAVED_CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk", "m", false);
    private static final ProviderConfig UNSAVED_CONFIG =
            new ProviderConfig(null, "https://api.example.com/v1", "sk", "m", false);

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        probe = new ChatModelProbe(factory);
    }

    /**
     * 成功路径：最小对话校验通过 → connected=true、method=chat、status=200、message 提示。
     */
    @Test
    void successReturnsConnectedMap() {
        when(factory.get(SAVED_CONFIG)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))), null));

        Map<String, Object> r = probe.testConnection(SAVED_CONFIG);

        assertTrue(Boolean.TRUE.equals(r.get("connected")));
        assertEquals("chat", r.get("method"));
        assertEquals(200, r.get("status"));
        assertTrue(((String) r.get("message")).contains("连接成功"));
        assertTrue(((Number) r.get("latencyMs")).longValue() >= 0);
    }

    /**
     * 失败路径：调用异常 → connected=false + 友好消息（含原始信息），不抛错。
     */
    @Test
    void failureReturnsDisconnectedMap() {
        when(factory.get(SAVED_CONFIG)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("401 Unauthorized"));

        Map<String, Object> r = probe.testConnection(SAVED_CONFIG);

        assertFalse(Boolean.TRUE.equals(r.get("connected")));
        assertTrue(((String) r.get("message")).contains("401 Unauthorized"));
        assertEquals(0, r.get("status"));
    }

    /**
     * 已保存配置（id=11）→ 走工厂缓存 get()；未保存配置（id=null）→ 直接 build()（不缓存）。
     */
    @Test
    void savedUsesCacheUnsavedBuildsFresh() {
        when(factory.get(SAVED_CONFIG)).thenReturn(chatModel);
        when(factory.build(UNSAVED_CONFIG)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))), null));

        probe.testConnection(SAVED_CONFIG);
        verify(factory).get(SAVED_CONFIG);
        probe.testConnection(UNSAVED_CONFIG);
        verify(factory).build(UNSAVED_CONFIG);
    }
}
