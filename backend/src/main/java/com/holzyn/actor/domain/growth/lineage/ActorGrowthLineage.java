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

package com.holzyn.actor.domain.growth.lineage;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 谱系边实体，对应表 actor_growth_lineage。
 * <p><b>只增不改</b>（历史不可篡改——谱系是家族史）：</p>
 * <ul>
 * <li>{@code marriage} 婚姻边：from=夫，to=妻；{@code active=1} 表示婚姻存续（离婚/丧偶 → active=0，
 * 历史行保留）；</li>
 * <li>{@code parent_child} 亲子边：from=父母，to=子女，{@code relation}=father/mother——
 * 出生信息（birth_game_second + KIND_BIRTH 里程碑 + parent_child 边）由谱系共同承载
 * ；</li>
 * <li>{@code sibling} 同胞边：由 LineageService 派生查询（不落库，防双写）——统一走
 * 「共同父母」判定。</li>
 * </ul>
 * <p><b>与 actor_relation 的分工</b>：{@code actor_relation} 是社会关系（含 ROMANTIC/MARRIAGE/
 * PARENT_CHILD/SIBLING 值，供感知/对话/决策消费）；本表是<b>谱系事实</b>（婚姻缔结/生育事实的
 * 权威记录，供家族树/血亲禁忌/遗产继承查询）。两表由 MarriageService/OffspringGenerator 同写，
 * 语义不可互相替代：关系可断（分手/决裂），谱系不可断。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_lineage", indexes = {
        @Index(name = "idx_growth_lineage_from", columnList = "project_id, from_ref"),
        @Index(name = "idx_growth_lineage_to", columnList = "project_id, to_ref"),
        @Index(name = "idx_growth_lineage_type", columnList = "edge_type")
})
public class ActorGrowthLineage {

    /** 边类型：婚姻边 */
    public static final String EDGE_MARRIAGE = "marriage";
    /** 边类型：亲子边 */
    public static final String EDGE_PARENT_CHILD = "parent_child";

    /** 亲子关系：父亲 */
    public static final String REL_FATHER = "father";
    /** 亲子关系：母亲 */
    public static final String REL_MOTHER = "mother";

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目归属（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 边类型：marriage / parent_child */
    @Column(name = "edge_type", length = 20, nullable = false)
    private String edgeType;

    /** 发起方引用（统一 npc:{id}；marriage=夫，parent_child=父母） */
    @Column(name = "from_ref", length = 50, nullable = false)
    private String fromRef;

    /** 目标方引用（统一 npc:{id}；marriage=妻，parent_child=子女） */
    @Column(name = "to_ref", length = 50, nullable = false)
    private String toRef;

    /** 关系语义：father / mother（parent_child 用；marriage 恒空） */
    @Column(length = 16)
    private String relation;

    /** 边发生时的世界时刻（游戏秒；结婚/出生时刻） */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 存续标记：1=存续（婚姻中/亲子永远存续），0=终止（离婚/丧偶后婚姻边失效） */
    private Integer active = 1;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (active == null) active = 1;
    }
}
