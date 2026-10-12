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
 * 重大世界帧事件触发演绎配置。
 * <p>需求来源：主开发方案 §4.5「重大世界帧事件触发演绎（可选，开关默认关）」+
 * （AI 选角 + 自动建会话 + 预算门控 + 每项目每游戏日限频 + source=engine 限定 + 归档排除防环 + 幂等去重）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.evolution-dramatize")
public class EvolutionDramatizeProperties {

    /** 自动演绎总开关（默认关；false=事件总线订阅者不注册，零成本） */
    private boolean enabled = false;

    /**
     * 触发重要度阈值（world_frame 事件 importance ≥ 此值才演绎；扩散到达事件 importance=1 天然排除；
     * 默认 4 对齐世界帧紧急更新阈值）
     */
    private int importanceThreshold = 4;

    /** 每项目每游戏日触发上限（内存 Map，超限跳过不报错；重启丢失可接受） */
    private int maxPerDay = 1;
}
