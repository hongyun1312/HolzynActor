package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 用量日志服务。
 * <p>职责：统一封装 actor_usage_log 写入（用户/项目/角色/供应商/模型/场景/token/耗时），
 * 供角色卡生成、对话、行动等 AI 调用场景记录用量（P2 用量统计 + P4 成本控制的基础）。</p>
 * <p>所属模块：service/usage（用量子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageLogService {

    /** 用量日志仓库 */
    private final ActorUsageLogRepository repository;

    /**
     * 记录一次 AI 调用用量（各字段可空，内部归一化为非空默认值；旧签名重载，缓存统计按 0 处理）。
     *
     * @param userId      归属用户 ID
     * @param projectId   项目 ID（项目维度统计）
     * @param characterId 角色 ID（可空）
     * @param providerId  API 配置主键（可空）
     * @param model       实际模型名（可空）
     * @param scene       场景：card_gen/dialog/action/crowd
     * @param tokenIn     输入 token（可空）
     * @param tokenOut    输出 token（可空）
     * @param durationMs  调用耗时毫秒（可空）
     */
    public void record(Long userId, Long projectId, Long characterId, Long providerId, String model,
                       String scene, Integer tokenIn, Integer tokenOut, Integer durationMs) {
        // 兼容旧签名：未提供缓存命中统计时按 0 处理（不影响聚合）
        record(userId, projectId, characterId, providerId, model, scene, tokenIn, tokenOut,
                null, null, durationMs);
    }

    /**
     * 记录一次 AI 调用用量（完整签名，含上下文缓存命中统计，各字段可空，内部归一化为非空默认值）。
     *
     * @param userId          归属用户 ID
     * @param projectId       项目 ID（项目维度统计）
     * @param characterId     角色 ID（可空）
     * @param providerId      API 配置主键（可空）
     * @param model           实际模型名（可空）
     * @param scene           场景：card_gen/dialog/action/crowd
     * @param tokenIn         输入 token（可空）
     * @param tokenOut        输出 token（可空）
     * @param cacheHitTokens  输入中命中上下文缓存的 token（可空，DeepSeek prompt_cache_hit_tokens）
     * @param cacheMissTokens 输入中未命中缓存的 token（可空）
     * @param durationMs      调用耗时毫秒（可空）
     */
    public void record(Long userId, Long projectId, Long characterId, Long providerId, String model,
                       String scene, Integer tokenIn, Integer tokenOut, Integer cacheHitTokens,
                       Integer cacheMissTokens, Integer durationMs) {
        try {
            ActorUsageLog log = new ActorUsageLog();
            log.setUserId(userId == null ? 1L : userId);
            log.setProjectId(projectId);
            log.setCharacterId(characterId);
            log.setProviderId(providerId);
            log.setModel(model);
            log.setScene(scene);
            log.setTokenIn(tokenIn == null ? 0 : tokenIn);
            log.setTokenOut(tokenOut == null ? 0 : tokenOut);
            log.setCacheHitTokens(cacheHitTokens == null ? 0 : cacheHitTokens);
            log.setCacheMissTokens(cacheMissTokens == null ? 0 : cacheMissTokens);
            log.setDurationMs(durationMs == null ? 0 : durationMs);
            repository.save(log);
        } catch (Exception e) {
            // 用量记录失败不应影响主业务流程，仅告警
            log.warn("用量日志写入失败: {}", e.getMessage());
        }
    }
}
