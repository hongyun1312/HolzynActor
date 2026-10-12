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

package com.holzyn.actor.domain.usage.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用用量日志实体，对应表 actor_usage_log。
 * <p>职责：记录每次 AI 调用的用量与成本（用户/项目/角色/供应商/模型/场景/token/耗时），
 * 供 管理后台「AI 用量统计」聚合与 世界模拟成本控制使用。
 * scene 取值：card_gen 角色卡生成 / dialog 对话 / action 行动 / crowd 人群。</p>
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

    /**
     * 场景码（card_gen / dialog / action / crowd / persona_bootstrap_fill_a …）。
     * <p><b>阶段④ 修复</b>：原 {@code length = 20} 装不下 新增的
     * 两段式建档场景码 {@code persona_bootstrap_fill_a} / {@code persona_bootstrap_fill_b}（各 <b>24</b>字符，
     * 见 {@code PersonaBootstrapService.SCENE_FILL_A/B}），DB 侧实际报
     * 该失败导致两段式建档在真库上 100% 不可用（AI 已产出内容却全部作废）。</p>
     * <p>放宽到 <b>48</b>，与同源的 {@code actor_ai_call_log.scene}（{@code ActorAiCallLog} 亦为 48）一致。
     * 依据 = 全仓库最长**场景码** 33 字符，
     * 留 15 字符余量给后续场景码。</p>
     * <p>⚠️ 口径澄清：{@code crowd_schedule_affiliation}（26 字符）是
     * {@code PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION} —— 它是 <b>Prompt 模板编码</b>，
     * 经 {@code PromptTemplateService.render(userId, projectId, code, ph)} 的 {@code code} 形参传入，
     * 不是经本列的 scene（该路径的 scene 是 {@code crowd_ai}）。因此它<b>不</b>是本列宽度的依据；
     * 本列只需容纳场景码。</p>
     */
    @Column(length = 48, nullable = false)
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

    /** 成本 */
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