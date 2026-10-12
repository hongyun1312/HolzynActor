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
 * 时间季节系统-统一环境服务配置（，前缀 holzyn.actor.environment.*）。
 * <p>职责：承载 EnvironmentContextService 与三处注入/极值高权重 的可调参数——
 * 感知/对话/决策三处注入开关、环境描述 AI 润色开关与每日预算、极值高权重阈值
 * （暴雪/暴雨出行降权体感阈值、深夜起点小时、深夜+疲劳能量阈值、极端低温体感阈值）。
 * 代码默认值与 application.yml 同步。</p>
 * <p>设计对齐：《HolzynActor_记忆系统重构与世界时间季节系统_设计方案》§8 environment.*。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.environment")
public class EnvironmentProperties {

    /** 感知段注入开关（默认 true：决策/行动感知带【当前环境】段，置顶） */
    private boolean injectPerception = true;

    /** 对话【当前环境】段注入开关（默认 true：角色闲聊自然带出天气/季节） */
    private boolean injectDialogue = true;

    /** 决策情境注入开关（默认 true：即时/恢复决策注入时间+环境段/极值约束/性格加权） */
    private boolean injectDecision = true;

    /**
     * 环境描述 AI 润色开关（默认 true：每日帧/天气切换后异步预生成当日各时段描写，
     * 缓存+每日预算门控；false=仅确定性模板组合，零 AI 成本）
     */
    private boolean aiPolish = true;

    /** AI 润色每日预算（次/游戏日/项目，默认 8：4 时段基础 + 天气切换后重润色余量；耗尽回退模板） */
    private int polishDailyCap = 8;

    /** AI 润色结果缓存上限（条，默认 2000：超限清空防内存膨胀；键=项目+游戏日+时段+天气指纹） */
    private int polishCacheMaxEntries = 2000;

    /** 极值高权重：暴雪/暴雨「天气恶劣」判定体感阈值（℃，默认 5：体感 ≤ 该值且雨/雪 → 出行降权） */
    private double stormTravelFeelslike = 5;

    /** 极值高权重：深夜起点小时（默认 22：22:00 后） */
    private int deepNightHour = 22;

    /** 极值高权重：深夜+疲劳能量阈值（默认 30：energy < 该值判定疲劳，强制回家倾向） */
    private int deepNightFatigueEnergy = 30;

    /** 极值高权重：极端低温体感阈值（默认 -15：体感 ≤ 该值户外活动降权） */
    private double extremeColdC = -15;

    /** 注入文本单条长度上限（字符，默认 80：环境段截断防撑爆 Prompt） */
    private int injectMaxLength = 80;
}
