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

package com.holzyn.actor.domain.world.fact.entity;

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
 * 事实认知行，对应表 {@code actor_world_fact_knowledge}。
 *
 * <p>不变量决策只读「我知道的事实」，不读全项目事实。一条事实对每个角色最多一行
 * （唯一键 {@code project_id + fact_id + knower_ref}），{@code source_type} 记录获知方式：
 * own / perceive / told / rumor / infer。</p>
 */
@Data
@Entity
@Table(name = "actor_world_fact_knowledge", indexes = {
        @Index(name = "ix_factknow_ref", columnList = "project_id,knower_ref,learned_game_second")
})
public class ActorWorldFactKnowledge {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目/世界 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 事实行 ID */
    @Column(name = "fact_id", nullable = false)
    private Long factId;

    /** 知情者 ref（npc:{id}；玩家同构后也是 npc:{id}） */
    @Column(name = "knower_ref", nullable = false, length = 64)
    private String knowerRef;

    /** 获知游戏秒（可空： learn 不带时间，广播时补） */
    @Column(name = "learned_game_second")
    private Long learnedGameSecond;

    /** 获知方式：own / perceive / told / rumor / infer */
    @Column(name = "source_type", nullable = false, length = 12)
    private String sourceType = "perceive";

    /** 来源 ref（谁告诉的 / 哪个传闻包） */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    /** 可信度 0~100 */
    @Column(nullable = false)
    private Integer confidence = 100;

    /** 失真 JSON（传闻走样：夸大/遗漏/换人） */
    @Column(name = "distortion_json", columnDefinition = "TEXT")
    private String distortionJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (sourceType == null || sourceType.isBlank()) sourceType = "perceive";
        if (confidence == null) confidence = 100;
    }
}
