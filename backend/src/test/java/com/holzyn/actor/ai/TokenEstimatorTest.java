package com.holzyn.actor.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenEstimator 本地 token 估算单元测试（AI 接入层重构 M5：随旧 {@code OpenAiCompatibleProvider}
 * 物理删除，原 OpenAiCompatibleProviderTokenEstimateTest 迁移至本公共工具——口径与旧路径完全一致）。
 * <p>职责：验证流式用量兜底的启发式估算（CJK 按 0.55/字符、ASCII 按 0.28/字符 + 单词开销），
 * 保证供应商未返回 usage 时估算值非 0 且量级合理。</p>
 */
class TokenEstimatorTest {

    /**
     * 空/空串估算为 0。
     */
    @Test
    void estimateTokensBlankIsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(null));
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(0, TokenEstimator.estimateTokens("   "));
    }

    /**
     * 中文文本估算非 0 且随长度增长。
     */
    @Test
    void estimateTokensChineseNonZero() {
        int shortText = TokenEstimator.estimateTokens("你好");
        int longText = TokenEstimator.estimateTokens("我是来自东方的旅行者，带着一条重要的消息赶来见你。");
        assertTrue(shortText >= 1, "中文短文本至少 1 token");
        assertTrue(longText > shortText, "长文本估算应大于短文本");
    }

    /**
     * 英文单词估算：单词数计入，越长越多。
     */
    @Test
    void estimateTokensEnglishWords() {
        int one = TokenEstimator.estimateTokens("hello");
        int two = TokenEstimator.estimateTokens("hello world");
        assertTrue(one >= 1);
        assertTrue(two > one, "两个单词应大于一个单词");
    }

    /**
     * 消息组 prompt 估算：单条消息按 4 + 内容 token；空消息组至少 1。
     */
    @Test
    void estimatePromptTokensSumsMessages() {
        assertEquals(1, TokenEstimator.estimatePromptTokens(null));
        assertEquals(1, TokenEstimator.estimatePromptTokens(List.of()));
        List<AiChatRequest.ChatMessage> msgs = List.of(
                new AiChatRequest.ChatMessage("system", "你是助手"),
                new AiChatRequest.ChatMessage("user", "今天天气怎么样"));
        int total = TokenEstimator.estimatePromptTokens(msgs);
        assertTrue(total > 4, "两条消息的 prompt 估算应大于单条开销");
    }
}
