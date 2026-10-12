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

package com.holzyn.actor.domain.npc.memory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * L1 模糊状态实体，对应表 actor_memory_fuzz_state。
 * <p>职责：1:1 挂 {@link ActorMemoryWorking} 中 item_type=short_term 的短期事件记忆行，
 * 承载「自然遗忘」式渐进模糊的全部状态——原始内容（永不丢失）/当前模糊版本/模糊度/艾宾浩斯
 * 下次触发时刻/模糊历史/检索次数/embedding 噪声幅度。</p>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§3.1.2
 * （fuzz_state 表）+ §3.2（L1FuzzificationService 三维降级）+ 用户新增语义
 * （睡眠清洗：L1 有效期=一段清醒时间，隔夜琐事合并/重要模糊/重复或高重要蒸馏）。</p>
 */
@Data
@Entity
@Table(name = "actor_memory_fuzz_state")
public class ActorMemoryFuzzState {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级归属 */
    @Column(name = "project_id")
    private Long projectId;

    /** 1:1 关联 actor_memory_working.id（short_term 行） */
    @Column(name = "memory_working_id")
    private Long memoryWorkingId;

    /** 归属角色（special:{id}/crowd:{id}；冗余自 working 行，模糊扫描按角色查询用） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** 原始内容（永不丢失；模糊历史回溯源） */
    @Column(name = "original_content", columnDefinition = "TEXT", nullable = false)
    private String originalContent;

    /** 当前模糊版本（检索/注入读此；fuzz_level=0 时=原文） */
    @Column(name = "current_content", columnDefinition = "TEXT", nullable = false)
    private String currentContent;

    /** 模糊度 0~1：0=完全清晰，1=完全模糊（达到 1 前若未蒸馏则保留最模糊版本，不删除） */
    @Column(name = "fuzz_level")
    private Double fuzzLevel = 0.0;

    /** 上次模糊触发游戏秒 */
    @Column(name = "last_fuzz_game_second")
    private Long lastFuzzGameSecond;

    /** 下次触发游戏秒（艾宾浩斯间隔：初始 1800 游戏秒=30 游戏分钟，后续 ×1.8 指数增长，封顶 1 游戏日） */
    @Column(name = "next_fuzz_game_second")
    private Long nextFuzzGameSecond;

    /** 模糊触发次数 */
    @Column(name = "fuzz_count")
    private Integer fuzzCount = 0;

    /** 模糊历史 JSON：[{gameSecond, fromLevel, toLevel, ai, prompt}] 幂等可追溯 */
    @Column(name = "fuzz_history_json", columnDefinition = "TEXT")
    private String fuzzHistoryJson;

    /** 检索命中次数（模糊/蒸馏触发依据：≥3 触发 L1→L2 归类） */
    @Column(name = "retrieval_count")
    private Integer retrievalCount = 0;

    /** 重要度 0~1（与 working.importance_score 同步；模糊速率约束 + 蒸馏快车道依据） */
    @Column(name = "importance_score")
    private Double importanceScore = 0.2;

    /** 当前施加的向量噪声幅度（=fuzz_level×max-noise；检索难度上升维度） */
    @Column(name = "embedding_noise")
    private Double embeddingNoise = 0.0;

    /** 状态：active / distilled（已蒸馏进 L2）/ archived（睡眠清洗琐事合并后归档） */
    @Column(name = "status", length = 20)
    private String status = "active";

    /** 蒸馏产物 L2 记忆 id（谱系锚点） */
    @Column(name = "distilled_memory_id")
    private Long distilledMemoryId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (fuzzLevel == null) fuzzLevel = 0.0;
        if (fuzzCount == null) fuzzCount = 0;
        if (retrievalCount == null) retrievalCount = 0;
        if (importanceScore == null) importanceScore = 0.2;
        if (embeddingNoise == null) embeddingNoise = 0.0;
        if (status == null) status = "active";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
