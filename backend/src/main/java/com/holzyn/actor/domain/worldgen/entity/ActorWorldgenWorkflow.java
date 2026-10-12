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
 * 世界观生成工作流定义实体，对应表 actor_worldgen_workflow。
 * <p>职责：存储「工作流定义」——版本化节点图（nodes + edges + variables 三段 JSON），
 * 首期内置 {@code worldgen_build_v1}（6 维链式世界观生成模板），预留 character / story-plan 扩展。
 * 节点图 JSON 格式见 {@code domain/worldgen/model/WorkflowNode/WorkflowEdge/WorkflowVariable}
 * 与资源文件 {@code worldgen/worldgen_build_v1.json}（启动 seed，仿 PromptTemplateService 幂等 upsert）。</p>
 */
@Data
@Entity
@Table(name = "actor_worldgen_workflow")
public class ActorWorldgenWorkflow {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工作流编码（唯一，如 worldgen_build_v1） */
    @Column(name = "code", length = 100, nullable = false, unique = true)
    private String code;

    /** 工作流名称（中文，如 世界观生成（6 维链式）） */
    @Column(name = "name", length = 200, nullable = false)
    private String name;

    /** 定义版本号（内容变更自增） */
    private Integer version = 1;

    /** 节点列表 JSON（[{id,label,node_type,agent_type,first_message,output_variable,...}]） */
    @Column(name = "nodes_json", columnDefinition = "LONGTEXT")
    private String nodesJson;

    /** 边列表 JSON（[{source,target}]，含 __start__/__end__ 伪节点） */
    @Column(name = "edges_json", columnDefinition = "LONGTEXT")
    private String edgesJson;

    /** 输入变量定义 JSON（[{key,name,type,default,required,description}]） */
    @Column(name = "variables_json", columnDefinition = "LONGTEXT")
    private String variablesJson;

    /** 启用标记（1 启用 / 0 停用，停用不可新建任务） */
    private Integer enabled = 1;

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
        if (version == null) version = 1;
        if (enabled == null) enabled = 1;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
