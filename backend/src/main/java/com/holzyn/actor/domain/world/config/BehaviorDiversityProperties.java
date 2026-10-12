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

package com.holzyn.actor.domain.world.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 宏观社会演化 · 行为多样性注入器配置。
 * <p>职责：行为多样性注入器（BehaviorDiversityService）可调参数——总开关（enabled=false 零注入，
 * 行为回到 之前语义=回滚）、三类噪声强度（偏好波动 OCEAN / 情绪干扰 PAD / 社会参照
 * 组+关系）、噪声衰减半衰期（随游戏时间回落 0，λ 可配）、人格稳定度钳制上限（高尽责性/高一致性
 * 角色波动小——不破坏长期人格一致性）、上下文长度预算。</p>
 * <p>档位联动（AiIntensityGate）：programmatic 整体关闭 / light 仅偏好波动（弱幅度 ×0.5）/
 * full 三类全开（默认幅度）/ max 三类全开 + 社会参照加权进 AI 决策上下文。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.behavior-diversity")
public class BehaviorDiversityProperties {

    /** 注入器总开关（默认 true；false=零注入=回滚语义） */
    private boolean enabled = true;

    /** 偏好波动强度（OCEAN 派生，默认 0.5） */
    private double preferenceStrength = 0.5;

    /** 情绪干扰权重（PAD 偏离基线，默认 0.4） */
    private double emotionWeight = 0.4;

    /** 社会参照强度（同组/关系网近期行为从众，默认 0.4） */
    private double socialReferenceStrength = 0.4;

    /** 噪声衰减半衰期（游戏小时，默认 12；超过后强度指数衰减回落 0） */
    private double decayHours = 12.0;

    /** 人格稳定度钳制上限（默认 0.7；高尽责性/高一致性角色噪声幅度上限 = (1-稳定度)×cap） */
    private double personalityStabilityCap = 0.7;

    /** light 档弱幅度系数（默认 0.5） */
    private double lightMultiplier = 0.5;

    /** 注入段最大长度（默认 150 字） */
    private int maxContextLength = 150;

    /** 社会参照窗口（游戏日，默认 3；同组近期行为统计窗口） */
    private int socialWindowDays = 3;

    /** 社会参照最小从众人数（≥2 名同组成员同类行为才注入从众方向；默认 2） */
    private int socialMinActors = 2;
}
