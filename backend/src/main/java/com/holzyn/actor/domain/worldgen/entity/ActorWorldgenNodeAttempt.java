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

package com.holzyn.actor.domain.worldgen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 世界观生成节点尝试实体，对应表 actor_worldgen_node_attempt。
 * <p>职责：一次任务中「一个节点的一次执行」的完整记录（检查点语义，对齐 DeterminFlow）——
 * 节点输入（填充占位符后的 first_message / script_args）、输出 JSON、状态、错误、
 * 重试次数、Token 账本与耗时。同一节点多次执行（重试）追加多行，节点当前状态=最新行。</p>
 * <p>状态：running/succeeded/failed/skipped；retry/skip 通过 CAS（expected_attempt_count=当前行数）
 * 新行实现，任务从该节点原地续跑。</p>
 */
@Data
@Entity
@Table(name = "actor_worldgen_node_attempt",
        indexes = {
                @Index(name = "idx_wg_attempt_task_node", columnList = "task_id,node_id"),
                @Index(name = "idx_wg_attempt_task_order", columnList = "task_id,node_order")
        })
public class ActorWorldgenNodeAttempt {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联任务 ID（actor_worldgen_task.id） */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 节点 ID（定义内唯一，如 agent_corelaws / script_merge_world） */
    @Column(name = "node_id", length = 100, nullable = false)
    private String nodeId;

    /** 节点在链中的执行序号（1 起；同一节点多次尝试共享同一序号） */
    @Column(name = "node_order", nullable = false)
    private Integer nodeOrder;

    /** 节点类型：agent / script（见 domain/worldgen/model/WorldGenStepType） */
    @Column(name = "step_type", length = 10, nullable = false)
    private String stepType;

    /** 节点输入 JSON（agent=填充占位符后的 first_message；script=script_args 等） */
    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    /** 节点输出 JSON（成功后的产物；script 桩/检查点为占位结构） */
    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    /** 节点状态：running/succeeded/failed/skipped */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 错误信息（failed 时填充） */
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** 重试次数 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    /** 输入 Token 数 */
    @Column(name = "token_in")
    private Integer tokenIn = 0;

    /** 输出 Token 数 */
    @Column(name = "token_out")
    private Integer tokenOut = 0;

    /** 执行耗时（毫秒） */
    @Column(name = "duration_ms")
    private Long durationMs = 0L;

    /** 创建时间（=该次尝试的开始时间） */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "running";
        if (retryCount == null) retryCount = 0;
        if (tokenIn == null) tokenIn = 0;
        if (tokenOut == null) tokenOut = 0;
        if (durationMs == null) durationMs = 0L;
    }
}
