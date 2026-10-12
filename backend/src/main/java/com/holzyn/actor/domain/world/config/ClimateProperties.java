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
 * 时间季节系统-季节模块配置（，前缀 holzyn.actor.climate.*）。
 * <p>职责：季节系统总开关——false 时季节模块退化为「纯日历上下文」（帧不加季节上下文、
 * 不发布季节更替事件、天气切换不做季节修正），EnvironmentContextService 仍可独立计算
 * （其自身由 holzyn.actor.environment.* 控制）。代码默认值与 application.yml 同步。</p>
 * <p>设计对齐：《HolzynActor_记忆系统重构与世界时间季节系统_设计方案》§8 climate.*。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.climate")
public class ClimateProperties {

    /** 季节系统总开关（默认 true；false=不注入季节上下文/不发布季节事件/天气切换不季节修正） */
    private boolean enabled = true;
}
