package com.holzyn.actor.domain.usage.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用用量日志实体，对应表 actor_usage_log。
 * <p>职责：记录每次 AI 调用的用量与成本（用户/项目/角色/供应商/模型/场景/token/耗时），
 * 供 P2 管理后台「AI 用量统计」聚合与 P4 世界模拟成本控制使用。
 * scene 取值：card_gen 角色卡生成 / dialog 对话 / action 行动 / crowd 人群。</p>
 * <p>所属模块：model/entity（实体层-用量子域）</p>
 */
@Data
@Entity
@Table(name = "actor_usage_log")
public class ActorUsageLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 ID（关联 sys_user.id） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 项目 ID（项目维度统计；角色卡/对话/行动均解析到所属项目） */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色 ID（可空：对话/行动场景归属角色） */
    @Column(name = "character_id")
    private Long characterId;

    /** 供应商/API 配置主键（关联 actor_model_provider.id） */
    @Column(name = "provider_id")
    private Long providerId;

    /** 实际使用的模型名 */
    @Column(length = 100)
    private String model;

    /** 场景：card_gen / dialog / action / crowd */
    @Column(length = 20, nullable = false)
    private String scene;

    /** 输入 token 数 */
    @Column(name = "token_in")
    private Integer tokenIn = 0;

    /** 输出 token 数 */
    @Column(name = "token_out")
    private Integer tokenOut = 0;

    /** 输入中命中上下文缓存的 token 数（DeepSeek prompt_cache_hit_tokens，可空） */
    @Column(name = "prompt_cache_hit_tokens")
    private Integer cacheHitTokens = 0;

    /** 输入中未命中缓存、需全量计算的 token 数（可空） */
    @Column(name = "prompt_cache_miss_tokens")
    private Integer cacheMissTokens = 0;

    /** 调用耗时（毫秒） */
    @Column(name = "duration_ms")
    private Integer durationMs = 0;

    /** 成本（可按模型单价折算，P2 先留空） */
    @Column(precision = 10, scale = 4)
    private BigDecimal cost;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (tokenIn == null) tokenIn = 0;
        if (tokenOut == null) tokenOut = 0;
        if (cacheHitTokens == null) cacheHitTokens = 0;
        if (cacheMissTokens == null) cacheMissTokens = 0;
        if (durationMs == null) durationMs = 0;
    }
}