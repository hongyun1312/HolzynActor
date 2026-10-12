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

package com.holzyn.actor.ai.route;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场景-模型路由配置。
 * <p>职责：承载「场景-模型路由」全部可调参数——全局默认档位 / 类别默认档位 / 场景→类别映射 /
 * 逐场景覆盖表。代码默认值与 application.yml 同步，全量可经环境变量覆盖。</p>
 * <p>设计决策（用户逐题确认，）：</p>
 * <ul>
 * <li><b>双层路由</b>：供应商选择规则（当前运行一律本地 Qwen3.6-35B=默认 chat provider，
 * 路由表 provider-id 预留——将来接入更强/更多模型只改配置不动代码）+ 模型参数档位
 * （不同场景不同温度/输出长度，让角色对话有性格、决策稳定合理）；</li>
 * <li><b>全场景质量优先、降级可选</b>：路由表默认全部场景走质量优先供应商（本地 67），
 * 每个场景可显式覆盖；</li>
 * <li><b>按类别默认档位 + 可逐场景覆盖</b>：decision 0.3 / dialog 0.8 / memory 0.2 /
 * world 0.7 / crowd 0.6 / tool 0.5；</li>
 * <li>云端 Ark 仅作 离线 judge（不走本路由，不参与运行时生成）。</li>
 * </ul>
 * <p>⚠️ 绑定注意事项：嵌套档位类不用 record（Spring Boot 构造器绑定
 * 依赖 -parameters 编译标志，项目未启用 → 静默绑定失败全部回退默认），改回普通 POJO
 * （getter/setter）与项目既有 @ConfigurationProperties 风格一致；全局默认键为
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.routing")
public class SceneRoutingProperties {

    /** 路由总开关（默认开；false=网关按调用方原样参数与默认供应商调用，行为回退现状） */
    private boolean enabled = true;

    /** 全局默认档位（未归类/未覆盖场景回退） */
    private Tier defaultTier = new Tier(0.5, 2048);

    /** 类别默认档位（scene → category 命中后使用） */
    private Map<String, Tier> tiers = new LinkedHashMap<>();

    /** 场景→类别映射（键=AiCallContext scene；未列出场景=defaultTier） */
    private Map<String, String> categoryMap = new LinkedHashMap<>();

    /** 逐场景覆盖表（优先级：运行时覆盖 &gt; 本表 &gt; 类别档位 &gt; 全局默认） */
    private Map<String, SceneRouteOverride> scenes = new LinkedHashMap<>();

    /**
     * 参数档位（温度 + 输出长度上限；任一为空=沿用上层回退）。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tier {
        /** 采样温度（可空） */
        private Double temperature;
        /** 最大输出 token（可空） */
        private Integer maxTokens;
    }

    /**
     * 逐场景路由覆盖（任一字段为空=沿用类别/默认）。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneRouteOverride {
        /** 采样温度（可空） */
        private Double temperature;
        /** 最大输出 token（可空） */
        private Integer maxTokens;
        /** 供应商配置主键（可空=默认 chat provider；指定=该场景固定走该供应商） */
        private Long providerId;
    }
}
