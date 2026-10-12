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

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 长期记忆实体（L2 主干），对应表 actor_memory。
 * <p>职责：存储<b>L2 长期记忆</b>——对话 AI 抽取的事实/会话摘要 + 行动/事件经历（情节）+ 反思洞察 + 日计划，
 * 双粒度归属：角色级（character_id 或 character_ref 非空，仅该角色注入）+ 项目级
 * （character_id=NULL 且 character_ref=NULL，世界大事记，所有角色可见）。</p>
 * <p><b>记忆系统分层重构</b>：<code>kind</code>字段重命名为 <code>type</code>（不再沿用 kind 体系），
 * 旧值映射：fact/summary→semantic（会话摘要经 context_json subtype=summary 区分）、observation→episodic、
 * reflection→reflection、plan→plan；<code>context_json</code>（情节绑定/计划结构化/摘要子类型）、
 * <code>source</code>（写入来源）、<code>supersedes_id</code>（版本链，被取代旧条→归档）、<code>version</code>（版本号）。
 * 归属：special:{id} 双写 character_id + character_ref，crowd:{id} 仅 character_ref。</p>
 */
@Data
@Entity
@Table(name = "actor_memory")
public class ActorMemory {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级记忆归属（关联 actor_project.id；NULL=历史旧数据未归属） */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色 ID（NULL=项目级记忆；非空=特殊 NPC 角色记忆，与 character_ref 双轨） */
    @Column(name = "character_id")
    private Long characterId;

    /** 统一角色引用（special:{id}/crowd:{id}；特殊 NPC 双写、普通 NPC 仅此列、项目级=NULL） */
    @Column(name = "character_ref", length = 50)
    private String characterRef;

    /** L2 类型：semantic 语义 / episodic 情节 / reflection 反思 / plan 计划 */
    @Column(name = "type", length = 20)
    private String type = ActorMemoryTypes.TYPE_SEMANTIC;

    /** 记忆内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 重要度（1-5，低重要度滚动淘汰） */
    private Integer importance = 1;

    /**
     * 上下文 JSON（TEXT）：情节绑定 who/when/where/why（episodic）/ 计划结构化（plan）/
     * 会话摘要子类型标记（type=semantic 且含 {"subtype":"summary"} 表示会话摘要）
     */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /** 写入来源（dialog/action/event/reflection/planning/consolidation/import/manual） */
    @Column(name = "source", length = 20)
    private String source = ActorMemoryTypes.SOURCE_DIALOG;

    /** 被本条取代的旧记忆 id */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /** 版本号（默认 1；/覆盖 +1，供版本化反思与回滚） */
    private Integer version = 1;

    /** 软删除标记：0正常/1已删 */
    private Integer deleted = 0;

    /** 最近一次检索命中时间（recency 衰减依据；检索时更新） */
    @Column(name = "last_access_at")
    private LocalDateTime lastAccessAt;

    /** 检索命中次数（访问频率强化，遗忘工程参考） */
    @Column(name = "access_count")
    private Integer accessCount = 0;

    /** 反思洞察的证据记忆 id 指针（TEXT 逗号分隔；type=reflection 时指向本次反思依据的记忆 id 列表） */
    @Column(name = "evidence_ids", columnDefinition = "TEXT")
    private String evidenceIds;

    /** staleness 瞬态标记（@Transient 不落库）：重要度≥阈值且超 N 游戏日未复核的高重要事实置 true，渲染「（可能过时）」 */
    @Transient
    private boolean stale;

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
        if (type == null) type = ActorMemoryTypes.TYPE_SEMANTIC;
        if (importance == null) importance = 1;
        if (deleted == null) deleted = 0;
        if (accessCount == null) accessCount = 0;
        if (version == null) version = 1;
        if (source == null) source = ActorMemoryTypes.SOURCE_DIALOG;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
