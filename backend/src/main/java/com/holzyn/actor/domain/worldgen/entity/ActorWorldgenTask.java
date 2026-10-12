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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 世界观生成任务实体，对应表 actor_worldgen_task。
 * <p>职责：一次「工作流执行实例」——创建时冻结 Workflow 定义引用与输入参数
 * （premise/genre/language + projectId），记录整体状态与完成时间。
 * 节点级执行历史落 {@link ActorWorldgenNodeAttempt}（检查点/尝试，重试/跳过原地续跑）。</p>
 * <p>状态机：created=running → succeeded | failed | canceled；
 * 失败后节点可 retry/skip（CAS expected_attempt_count）原地续跑（对齐 DeterminFlow）。</p>
 */
@Data
@Entity
@Table(name = "actor_worldgen_task")
public class ActorWorldgenTask {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联工作流定义 ID（actor_worldgen_workflow.id，启动时冻结） */
    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    /** 归属项目 ID */
    @Column(name = "project_id")
    private Long projectId;

    /** 任务输入 JSON（冻结的 inputs：premise/genre/language + 扩展，含 simulateFailNodeIds 测试钩子） */
    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    /** 任务状态：running/succeeded/failed/canceled（见 domain/worldgen/model/WorldGenStatus） */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 当前执行到的节点 ID（失败/断点定位；retry/skip 后推进） */
    @Column(name = "current_node_id", length = 100)
    private String currentNodeId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 完成时间（succeeded/failed/canceled 时填充） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (status == null) status = "running";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        // 无独立 updated_at 列（任务生命周期短，状态与完成时间已足够）
    }
}
