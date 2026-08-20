package com.holzyn.actor.ai;

/**
 * AI 对话补全结果（非流式）。
 * <p>用途：封装一次 Chat Completions 调用的返回（回复内容 + 用量统计 + 缓存命中统计），
 * 供业务层落库（对话消息 / 用量日志）与前端展示。
 * 缓存命中/未命中字段来自 OpenAI 兼容接口（DeepSeek prompt_cache_hit_tokens /
 * prompt_cache_miss_tokens），是「输入缓存命中率」统计与成本优化的数据基础。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 *
 * @param content           模型回复文本
 * @param model             实际使用的模型名
 * @param promptTokens      输入 token 数（可能为 null，部分供应商不返回）
 * @param completionTokens  输出 token 数（可能为 null）
 * @param totalTokens       总 token 数（可能为 null）
 * @param cacheHitTokens    输入中命中上下文缓存的 token 数（可空）
 * @param cacheMissTokens   输入中未命中缓存、需全量计算的 token 数（可空）
 * @param providerId        命中的 API 配置主键（用于用量日志）
 */
public record AiChatResult(String content, String model, Integer promptTokens, Integer completionTokens,
                           Integer totalTokens, Integer cacheHitTokens, Integer cacheMissTokens, Long providerId) {

    /**
     * 兼容旧签名：无缓存统计时创建（cacheHit/cacheMiss 取 null，聚合按 0 处理）。
     *
     * @param content          模型回复文本
     * @param model            实际使用的模型名
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数
     * @param providerId       命中的 API 配置主键
     */
    public AiChatResult(String content, String model, Integer promptTokens, Integer completionTokens,
                        Integer totalTokens, Long providerId) {
        this(content, model, promptTokens, completionTokens, totalTokens, null, null, providerId);
    }
}