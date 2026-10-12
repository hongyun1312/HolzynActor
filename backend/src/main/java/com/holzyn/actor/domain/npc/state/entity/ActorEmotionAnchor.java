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

package com.holzyn.actor.domain.npc.state.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PAD 基础情绪锚点实体，对应表 actor_emotion_anchor。
 * <p>职责：承载 7 个基础情绪的 PAD 三维锚点（平静/高兴/愤怒/悲伤/恐惧/惊讶/厌恶）——情绪 = PAD
 * 空间一点，主导情绪 = 距各锚点最近者（欧氏距离）；锚点数值参考经典 PAD 常模（主开发方案 §7.1）。
 * 一行一情绪，按 code 唯一（项目无关的全局参考表，启动期幂等种子初始化，用户可调参）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§7.1（PAD 空间与基础情绪锚点）。
 * </p>
 */
@Data
@Entity
@Table(name = "actor_emotion_anchor", uniqueConstraints = {
        @UniqueConstraint(name = "uk_emotion_anchor_code", columnNames = "code")
})
public class ActorEmotionAnchor {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 情绪编码：calm/happy/angry/sad/fearful/surprised/disgusted（唯一；代码引用键） */
    @Column(name = "code", length = 20, nullable = false)
    private String code;

    /** 情绪名称（中文，如：平静/高兴/愤怒…） */
    @Column(name = "name", length = 20)
    private String name;

    /** PAD-愉悦 P（-1.00~1.00；主开发方案 §7.1 锚点数值） */
    @Column(name = "pleasure")
    private Double pleasure;

    /** PAD-唤醒 A（-1.00~1.00） */
    @Column(name = "arousal")
    private Double arousal;

    /** PAD-支配 D（-1.00~1.00） */
    @Column(name = "dominance")
    private Double dominance;

    /** 情绪说明（一句话，供展示/注释） */
    @Column(name = "description", length = 200)
    private String description;

    /** 排序（展示顺序） */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 软删除标记：0正常/1已删 */
    private Integer deleted = 0;

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
        if (deleted == null) deleted = 0;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
