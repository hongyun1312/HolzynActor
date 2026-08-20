package com.holzyn.actor.ai;

/**
 * AI 调用用量明细（含上下文缓存命中统计）。
 * <p>用途：承载一次 Chat Completions 调用的 token 用量，以及 OpenAI 兼容接口
 * （DeepSeek / 火山方舟等）返回的上下文缓存命中/未命中 token
 * （prompt_cache_hit_tokens / prompt_cache_miss_tokens），供流式回调与用量日志落库。
 * 缓存命中部分按平台折扣价计费，统计命中率是优化成本的依据。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 *
 * @param promptTokens     输入 token 数（可空，部分供应商不返回）
 * @param completionTokens 输出 token 数（可空）
 * @param cacheHitTokens   输入中命中上下文缓存的 token 数（可空）
 * @param cacheMissTokens  输入中未命中缓存、需全量计算的 token 数（可空）
 */
public record AiUsage(Integer promptTokens, Integer completionTokens,
                      Integer cacheHitTokens, Integer cacheMissTokens) {
}