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
 * 跨层巩固谱系实体，对应表 actor_memory_lineage。
 * <p>职责：记录每跳跨层巩固（L1→L2 / L2→L3）的谱系——源/目标层级与 id、巩固类型、
 * 游戏时点、信息缩减率（目标 token / 源 token，&lt;1 表示缩减），调试前端「记忆谱系」视图
 * 以任一 L3 记忆为根反向展开完整来源链。</p>
 * <p>设计依据：《HolzynActor 记忆内核扩展_L1动态模糊与跨层级巩固_设计方案》§4.2。</p>
 */
@Data
@Entity
@Table(name = "actor_memory_lineage")
public class ActorMemoryLineage {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级归属 */
    @Column(name = "project_id")
    private Long projectId;

    /** 源：L1 working id / L2 actor_memory id */
    @Column(name = "source_memory_id")
    private Long sourceMemoryId;

    /** 源层级：L1 / L2 */
    @Column(name = "source_tier", length = 10)
    private String sourceTier;

    /** 目标：L2 actor_memory id / L3 actor_memory_core id */
    @Column(name = "target_memory_id")
    private Long targetMemoryId;

    /** 目标层级：L2 / L3 */
    @Column(name = "target_tier", length = 10)
    private String targetTier;

    /** 巩固类型：L1-&gt;L2 / L2-&gt;L3 */
    @Column(name = "consolidate_type", length = 10)
    private String consolidateType;

    /** 巩固时游戏秒 */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 信息缩减率（目标 token 数 / 源 token 数，<1 表示缩减） */
    @Column(name = "reduction_ratio")
    private Double reductionRatio;

    /** 源 token 数 */
    @Column(name = "source_tokens")
    private Integer sourceTokens;

    /** 目标 token 数 */
    @Column(name = "target_tokens")
    private Integer targetTokens;

    /** 蒸馏 prompt 版本 */
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
