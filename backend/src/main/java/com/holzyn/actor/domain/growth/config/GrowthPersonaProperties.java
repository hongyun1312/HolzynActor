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

package com.holzyn.actor.domain.growth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 角色生命与人格系统 · 档案与建档配置，。
 * <p>对应待收口项③「人格引导在恢复基线后每次重跑（43 次 AI、avg 72–96s）」的幂等化治理：
 * 恢复基线启动 / 世界初始化补档等<b>批处理路径</b>默认复用既有 11 维档案（
 * 允许复用基线既有 11 维人格，档案已完整就不重跑）；显式「重新生成」（编辑器按钮）走
 * {@code force} 强制重跑，不受本开关影响。</p>
 * <p>⚠️ 嵌套档位一律普通 POJO。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.growth.persona")
@Getter
@Setter
public class GrowthPersonaProperties {

    /**
     * AI 建档幂等跳过开关（默认 true）。
     * <p>true：{@code PersonaBootstrapService} 非 force 路径在「该段维度已全覆盖」时跳过 AI 调用，
     * 直接复用既有档案（基线还原后不再重跑 43 次引导）；false：一律重跑（调试重建用）。</p>
     */
    private boolean bootstrapIdempotent = true;
}
