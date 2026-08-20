package com.holzyn.actor.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 消息实体，对应表 actor_message。
 * <p>职责：存储对话消息——角色（user/assistant/system）、类型（text/action/event）、
 * 正文与 SSE 流式原始增量、SSE 状态（streaming/done/failed）及 token 用量。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_message")
public class ActorMessage {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（关联 actor_conversation.id） */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 角色 ID（assistant 消息归属的角色，user/system 可为空） */
    @Column(name = "character_id")
    private Long characterId;

    /** 角色：user / assistant / system */
    @Column(length = 10, nullable = false)
    private String role;

    /** 类型：text 文本 / action 行动 / event 事件 */
    @Column(length = 10, nullable = false)
    private String type = "text";

    /** 正文（assistant 为最终落库文本） */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** SSE 流式原始增量（调试用，可清理） */
    @Column(name = "raw_stream", columnDefinition = "LONGTEXT")
    private String rawStream;

    /** SSE 状态：streaming / done / failed */
    @Column(length = 10, nullable = false)
    private String status = "done";

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

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (type == null) type = "text";
        if (status == null) status = "done";
        if (tokenIn == null) tokenIn = 0;
        if (tokenOut == null) tokenOut = 0;
        if (cacheHitTokens == null) cacheHitTokens = 0;
        if (cacheMissTokens == null) cacheMissTokens = 0;
    }
}