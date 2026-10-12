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

package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宏观社会演化 · 可扩展入账规则实体。
 * <p>职责：把 的 MacroLedgerService 硬编码规则映射表迁移为「DB 配置驱动」——行动类型/
 * 关键词 → 宏观指标折算，插行即生效（热更新：启动加载 + POST/PUT 写库后内存缓存失效重建，
 * 不重启生效）：</p>
 * <ul>
 * <li><b>project_id</b>：0=全局默认（模板种子），&gt;0=项目覆盖（项目行优先）；</li>
 * <li><b>匹配</b>：actionType 优先（type 包含匹配）→ custom 按行动文本关键词（JSON 数组，
 * 任一命中）；type 与关键词同规则行需同时满足；</li>
 * <li><b>weight</b>：权重（涌现事件叠加乘；单点入账默认 1，emergence 叠加按
 * memberCount×typeBase 折算后写叠加入账行，规则本身 weight 预留扩展）；</li>
 * <li><b>种子</b>=现有 MacroLedgerService 硬编码映射迁移（trade→economy 三指标/农作/
 * 参战/布道/社交/行医/研习…）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_macro_delta_rule",
        uniqueConstraints = {
                // uk = 项目 × 行动类型 × 关键词 × 指标：同一 (type,keywords) 可对应多个指标行
                // （如 trade 一次命中 → market_activity/currency_supply/tax_revenue 三行）；
                // 项目行（>0）同签名覆盖全局行（0）实现项目级覆盖
                @UniqueConstraint(name = "uk_delta_rule", columnNames = {"project_id", "action_type", "keywords", "indicator"})
        },
        indexes = {
                @Index(name = "idx_delta_rule_proj", columnList = "project_id")
        })
public class ActorMacroDeltaRule {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID：0=全局默认（模板种子），>0=项目覆盖 */
    @Column(name = "project_id", nullable = false)
    private Long projectId = 0L;

    /** 行动类型（trade/work/fight/preach/social/treat/study/custom；type 优先匹配） */
    @Column(name = "action_type", length = 50)
    private String actionType;

    /** 自定义关键词（JSON 数组 ["囤","买粮"]；custom 按行动文本匹配；空=仅类型） */
    @Column(name = "keywords", length = 255)
    private String keywords;

    /** 目标宏观子系统：economy/faction/culture/tech */
    @Column(name = "target_macro", nullable = false, length = 20)
    private String targetMacro;

    /** 宏观指标名（market_activity/grain_production/tax_revenue/currency_supply/势力名/议题话题/科技维度） */
    @Column(name = "indicator", nullable = false, length = 50)
    private String indicator;

    /** 指标折算变化量 */
    @Column(name = "delta", nullable = false)
    private Double delta = 0.0;

    /** 权重（涌现事件叠加乘，默认 1） */
    @Column(name = "weight", nullable = false)
    private Double weight = 1.0;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (projectId == null) projectId = 0L;
        if (delta == null) delta = 0.0;
        if (weight == null) weight = 1.0;
        if (enabled == null) enabled = true;
    }
}
