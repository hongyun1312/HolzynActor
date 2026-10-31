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

package com.holzyn.actor.ai.decision.ab;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策模型 A/B 质量评估配置。
 * <p>配置段 {@code holzyn.actor.ai.decision.ab.*}（application.yml + 环境变量全覆盖）：</p>
 * <ul>
 * <li><b>enabled</b>：A/B 评估总开关（默认 false；关闭时批跑创建 400「A/B 评估未启用」——
 * 与 决策模型 判定调用开关独立，评估是离线设施不影响世界运行）；</li>
 * <li><b>sample-quotas</b>：三类场景样本配额（情绪评估 emotion-eval=75 / 候选选择
 * crowd-decision=75 / 社交意愿 social-willingness=50， ⑧按落地优先级加权，
 * 合计 ~200）；</li>
 * <li><b>concurrency</b>：异步批跑并发路数；</li>
 * <li><b>qwen.*</b>：Qwen 同题型重问参数（场景码 decision_ab_qwen / 温度 0.3 判定稳定 /
 * 输出上限 512）；</li>
 * <li><b>judge.*</b>：Judge 四维合理性评分模板（复用 JudgeChatClient，本段只配模板
 * 场景码与输入截断上限）；</li>
 * <li><b>escalation.*</b>：<b>置信度升级阈值参数化</b>——enabled 默认
 * false；top1-prob / entropy 为按场景建议阈值（标定结果回填，
 * 空=未标定）；设计 §6：分布不尖锐（top-1 概率低/熵高）→ 升 Qwen。</li>
 * </ul>
 * <p>⚠️ 冒烟教训：嵌套档位类禁用 record（构造器绑定依赖 -parameters
 * 编译标志，项目未启用 → 绑定静默失败全部回退默认）；一律用普通 POJO（@Getter/@Setter），
 * 注册方式与项目既有配置类一致 = @Component + @ConfigurationProperties。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.decision.ab")
@Getter
@Setter
public class DecisionAbProperties {

    /** A/B 评估总开关（默认 false；评估是离线设施，不影响世界运行） */
    private boolean enabled = false;

    /** 异步批跑并发路数（默认 4） */
    private int concurrency = 4;

    /** 样本配额 */
    private Map<String, Integer> sampleQuotas = new LinkedHashMap<>();

    /** 单场景样本配额兜底（未在 sample-quotas 配置时用；情绪评估） */
    private int defaultEmotionQuota = 75;

    /** 单场景样本配额兜底（候选选择） */
    private int defaultCrowdQuota = 75;

    /** 单场景样本配额兜底（社交意愿） */
    private int defaultSocialQuota = 50;

    /** Qwen 同题型重问参数 */
    private Qwen qwen = new Qwen();

    /** Judge 四维合理性评分参数 */
    private Judge judge = new Judge();

    /** 置信度升级阈值参数化 */
    private Escalation escalation = new Escalation();

    /** Qwen 同题型重问参数。 */
    @Getter
    @Setter
    public static class Qwen {
        /** Qwen 重问场景码（PromptSpecRegistry 登记；AiCallContext 场景归属） */
        private String scene = "decision_ab_qwen";
        /** 重问温度（判定稳定，0.3 对齐决策档位） */
        private double temperature = 0.3;
        /** 重问输出上限（token，对齐判定题 JSON 输出） */
        private int maxTokens = 512;
    }

    /** Judge 四维合理性评分参数。 */
    @Getter
    @Setter
    public static class Judge {
        /** Judge 模板场景码（PromptSpecRegistry 登记；供 trace/版本留痕） */
        private String scene = "decision_ab_judge";
        /** 样本输入长度上限 */
        private int maxInputChars = 4000;
    }

    /**
     * 置信度升级阈值参数化。
     * <p>语义（设计 §6）：决策模型 分布不尖锐（top-1 概率低 / 熵高）→ 判定不可信 →
     * 升级 Qwen 深度处理。阈值按场景标定， 接线时
     * 逐场景判定：top-1 概率 ≥ top1Prob 且熵 ≤ entropy 才采纳 决策模型，否则升 Qwen。</p>
     */
    @Getter
    @Setter
    public static class Escalation {
        /** 升级机制开关 */
        private boolean enabled = false;
        /** 按场景建议阈值：top-1 概率下限（空=未标定；键=emotion_eval/crowd_decision/social_willingness） */
        private Map<String, Double> top1Prob = new LinkedHashMap<>();
        /** 按场景建议阈值：熵上限（空=未标定） */
        private Map<String, Double> entropy = new LinkedHashMap<>();
    }
}
