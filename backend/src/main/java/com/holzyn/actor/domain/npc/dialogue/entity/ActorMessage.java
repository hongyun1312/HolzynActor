/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.npc.dialogue.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 消息实体，对应表 actor_dialogue_message（九模块规范化重构 原 actor_message，对话模块）。
 * <p>职责：存储对话消息——角色（user/assistant/system）、类型（text/action/event）、
 * 正文与 SSE 流式原始增量、SSE 状态（streaming/done/failed）及 token 用量。</p>
 */
@Data
@Entity
@Table(name = "actor_dialogue_message")
public class ActorMessage {

    /**
     * 世界事件注入 AI 上下文的前缀（语义梳理， A5）：
     * 事件消息存储为 role=system/type=event，组装侧统一以该前缀作为「情境段」注入
     * （单聊 ChatService.toAiMessage 与群聊 GroupChatService 共用，避免前缀漂移）；
     * 语义：事件是「世界发生了什么」的情境，不是系统指令。
     */
    public static final String EVENT_CONTEXT_PREFIX = "【世界事件】";

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