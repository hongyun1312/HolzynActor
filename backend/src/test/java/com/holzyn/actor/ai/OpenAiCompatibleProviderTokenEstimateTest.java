package com.holzyn.actor.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiCompatibleProvider 本地 token 估算逻辑单元测试。
 * <p>职责：验证流式用量兜底的启发式估算（CJK 按 0.55/字符、ASCII 按 0.28/字符 + 单词开销），
 * 保证供应商未返回 usage 时估算值非 0 且量级合理。</p>
 */
class OpenAiCompatibleProviderTokenEstimateTest {

    /**
     * 空/空串估算为 0。
     */
    @Test
    void estimateTokensBlankIsZero() {
        assertEquals(0, OpenAiCompatibleProvider.estimateTokens(null));
        assertEquals(0, OpenAiCompatibleProvider.estimateTokens(""));
        assertEquals(0, OpenAiCompatibleProvider.estimateTokens("   "));
    }

    /**
     * 中文文本估算非 0 且随长度增长。
     */
    @Test
    void estimateTokensChineseNonZero() {
        int shortText = OpenAiCompatibleProvider.estimateTokens("你好");
        int longText = OpenAiCompatibleProvider.estimateTokens("我是来自东方的旅行者，带着一条重要的消息赶来见你。");
        assertTrue(shortText >= 1, "中文短文本至少 1 token");
        assertTrue(longText > shortText, "长文本估算应大于短文本");
    }

    /**
     * 英文单词估算：单词数计入，越长越多。
     */
    @Test
    void estimateTokensEnglishWords() {
        int one = OpenAiCompatibleProvider.estimateTokens("hello");
        int two = OpenAiCompatibleProvider.estimateTokens("hello world");
        assertTrue(one >= 1);
        assertTrue(two > one, "两个单词应大于一个单词");
    }

    /**
     * 消息组 prompt 估算：单条消息按 4 + 内容 token；空消息组至少 1。
     */
    @Test
    void estimatePromptTokensSumsMessages() {
        assertEquals(1, OpenAiCompatibleProvider.estimatePromptTokens(null));
        assertEquals(1, OpenAiCompatibleProvider.estimatePromptTokens(List.of()));
        List<AiChatRequest.ChatMessage> msgs = List.of(
                new AiChatRequest.ChatMessage("system", "你是助手"),
                new AiChatRequest.ChatMessage("user", "今天天气怎么样"));
        int total = OpenAiCompatibleProvider.estimatePromptTokens(msgs);
        assertTrue(total > 4, "两条消息的 prompt 估算应大于单条开销");
    }
}
