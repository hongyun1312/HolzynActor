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

package com.holzyn.actor.domain.growth.genetic;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 遗传参数包实体，对应表 actor_growth_genome。
 * <p><b>一行一 NPC</b>（project_id + npc_ref 唯一）；承载：</p>
 * <ul>
 * <li>{@code gene_loci_json}：15 个位点的等位基因对（孟德尔重组输入）+ 由位点效应算出的
 * {@code derived_traits}（父母中点值 midparent，供遗传度框架映射）；</li>
 * <li>{@code epigenetics_json}：表观修饰因子（：父母孕前 90 游戏日重大事件 → 子代表达因子，
 * 出生到幼儿期末线性衰减，仅 → 单代，留痕可审计）；</li>
 * <li>{@code source_parent_a/b}：父母引用（actor_profile.id；孤儿/预设 NPC 为 null）。</li>
 * </ul>
 * <p><b>存量 NPC 无基因行</b>： 提供「程序化基因推断」（{@link MendelianRecombinator#inferGenome}）——
 * 按其既有 OCEAN 反向拟合位点，保证任何角色都可成为父母（测试/长跑不因缺基因而阻断）。</p>
 */
@Data
@Entity
@Table(name = "actor_growth_genome", uniqueConstraints = {
        @UniqueConstraint(name = "uk_growth_genome_ref", columnNames = {"project_id", "npc_ref"})
}, indexes = {
        @Index(name = "idx_growth_genome_project", columnList = "project_id")
})
public class ActorGrowthGenome {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目归属（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 统一角色引用：npc:{actor_profile.id} */
    @Column(name = "npc_ref", length = 50, nullable = false)
    private String npcRef;

    /**
     * 基因位点集 JSON（{@link GenomeData} 序列化）：
     */
    @Column(name = "gene_loci_json", columnDefinition = "TEXT")
    private String geneLociJson;

    /**
     * 表观修饰因子 JSON（{@link EpigeneticMarks} 序列化）：
     * {@code {marks:[{target,trait,factor,source_event,valid_until_game_second}]}}；无修饰为空数组。
     */
    @Column(name = "epigenetics_json", columnDefinition = "TEXT")
    private String epigeneticsJson;

    /** 父母 A（actor_profile.id；预设/孤儿为 null） */
    @Column(name = "source_parent_a")
    private Long sourceParentA;

    /** 父母 B（actor_profile.id；预设/孤儿为 null） */
    @Column(name = "source_parent_b")
    private Long sourceParentB;

    /** 基因来源：preset 预设 / inherited 遗传合成 / inferred 程序化推断 / newborn 新生儿合成 */
    @Column(length = 16)
    private String source = "preset";

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (source == null) source = "preset";
    }

    /** 前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
