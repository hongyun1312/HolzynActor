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

package com.holzyn.actor.ai.decision.gate;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策模型 判定层 · 逐场景配置。
 * <p>配置段 {@code holzyn.actor.ai.decision.gate.*}（application.yml + 环境变量全覆盖）：</p>
 * <ul>
 * <li><b>enabled</b>：判定层总开关（默认 true；场景级开关仍需 {@code scenes.<code>.enabled=true}
 * 且 {@code DecisionCallClient.isEnabled()} 为真——三重门控 fail-closed）；</li>
 * <li><b>scenes.&lt;code&gt;.enabled</b>：逐场景开关（默认 false=未评估场景保守关；
 * 实测：social_willingness / crowd_decision 默认开）；</li>
 * <li><b>scenes.&lt;code&gt;.sampling</b>：采样模式 sample（按分布加权随机，默认，真实优先）/
 * argmax（确定性，测试与回放）；</li>
 * <li><b>scenes.&lt;code&gt;.escalation</b>：置信度升级策略——enabled=true 时，
 * top-1 概率 &lt; top1-prob（或熵 &gt; entropy，配置任一时按「任一超限即升级」）→ 判定不可信，
 * 返回「升级」（调用方回退 Qwen/程序化）。 标定：social_willingness top1Prob=0.5
 * （分布尖锐一致率高）；crowd/emotion 无有效阈值（分布平坦）→ 不配置（直接采纳）。
 * 设计 §6：概率未标定，只排序/采样不作硬阈值——升级阈值须实测标定后才启用。</li>
 * </ul>
 * <p>⚠️ Spring Boot 4.x 宽松绑定坑：空对象 {@code {}} 绑 Map 会报
 * ConverterNotFoundException（Value:"" 转 Map 失败）——<b>yml 中每个场景配置都必须给全键</b>
 * （或省略整行走代码默认值），不得写空 {@code {}}。嵌套档位类一律用普通 POJO
 * （record 绑定依赖 -parameters 编译标志，项目未启用 → 静默失败）。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.decision.gate")
@Getter
@Setter
public class DecisionGateProperties {

    /** 判定层总开关（默认 true；场景级 + 决策模型 client 双重门控后的第三重总闸） */
    private boolean enabled = true;

    /** 逐场景配置（键=场景码 {@link DecisionScenes}；未配置场景用代码默认=关） */
    private Map<String, SceneConfig> scenes = new LinkedHashMap<>();

    /**
     * 单场景配置。
     */
    @Getter
    @Setter
    public static class SceneConfig {
        /** 场景开关（默认 false=未评估场景保守关） */
        private boolean enabled = false;
        /** 采样模式 sample/argmax（默认 sample=按分布加权随机，真实优先） */
        private String sampling = "sample";
        /** 置信度升级策略（默认关=直接采纳；social_willingness 实测标定 top1Prob=0.5 已接线） */
        private Escalation escalation = new Escalation();
    }

    /**
     * 置信度升级策略（设计 §6：分布不尖锐 → 升 Qwen 深度处理）。
     */
    @Getter
    @Setter
    public static class Escalation {
        /** 升级机制开关（默认 false=直接采纳） */
        private boolean enabled = false;
        /** top-1 概率下限（null=不启用该维度；top-1 < 此值 → 升级） */
        private Double top1Prob = null;
        /** 熵上限（null=不启用该维度；熵 > 此值 → 升级） */
        private Double entropy = null;
    }
}
