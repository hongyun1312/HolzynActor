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

package com.holzyn.actor.ai.guard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一护栏策略配置。
 * <p>职责：承载「统一护栏策略对象」的全部可调参数——默认重试上限 / 空输出判定 / 输出重复检测
 * （复读机防护）/ 连续失败硬上限 / 每场景覆盖表。代码默认值与 application.yml 同步，全量可经
 * 环境变量（HOLOZYN_ACTOR_AI_GUARD_*）覆盖。</p>
 * <p>设计背景：六项护栏能力一轮全做——max-retry / 格式失败降级 /
 * 输出重复检测 / 轮次硬上限 / 超时统一 / 场景可覆盖；检测在 AI 网关统一执行，重试执行留在
 * 业务侧既有 attempt 循环（默认值与现状一致=2，行为不回退），本对象为唯一策略源。</p>
 * <p>超时说明：单次调用硬超时（connect30/read120/write120/call130s 看门狗）已由
 * {@code Http11OpenAiHttpClient} 在 HTTP 层最终修复，护栏侧将其统一纳入配置口径
 * （timeout-read-seconds 等沿用 holzyn.actor.ai.*，不再重复建参数）。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.guard")
public class GuardRailProperties {

    /** 护栏总开关（默认开；false=网关不执行空输出/重复检测，业务侧重试上限回退代码默认） */
    private boolean enabled = true;

    /** 默认重试上限（每场景可覆盖；与现状各业务 attempt 循环的硬编码 2 一致，行为不回退） */
    private int defaultMaxRetries = 2;

    /** 输出最短有效长度（trim 后字符数低于该值视为空输出，默认 1=全空才判空） */
    private int minOutputChars = 1;

    /** 输出重复检测总开关（默认开；流式对话豁免硬拦截仅告警——内容已逐 token 送达客户端） */
    private boolean duplicateEnabled = true;

    /** 重复检测窗口（连续 N 次输出全部两两相似才判定复读机，默认 3） */
    private int duplicateWindow = 3;

    /** 重复判定 Dice 二元组重叠阈值（0~1，与 MemoryDedup 同算法，默认 0.9） */
    private double duplicateDiceThreshold = 0.9;

    /** 参与重复检测指纹计算的文本长度上限（字符，默认 800；超长截断防计算开销） */
    private int duplicateMaxTextChars = 800;

    /** 连续失败硬上限（单调用链路连续失败 N 次触发告警；默认 10，业务侧循环解析） */
    private int maxConsecutiveFailures = 10;

    /** 每场景覆盖表（键=AiCallContext scene；未列场景用默认值） */
    private Map<String, SceneGuard> scenes = new LinkedHashMap<>();

    /**
     * 单场景护栏覆盖（任一字段为空=沿用默认/类别值）。
     */
    @Getter
    @Setter
    public static class SceneGuard {

        /** 该场景重试上限（空=默认 defaultMaxRetries） */
        private Integer maxRetries;

        /** 该场景空输出判定长度（空=默认 minOutputChars） */
        private Integer minOutputChars;

        /** 该场景重复检测开关（空=默认 duplicateEnabled） */
        private Boolean duplicateEnabled;

        /** 该场景重复窗口（空=默认 duplicateWindow） */
        private Integer duplicateWindow;

        /** 该场景重复阈值（空=默认 duplicateDiceThreshold） */
        private Double duplicateDiceThreshold;

        /** 该场景连续失败硬上限（空=默认 maxConsecutiveFailures） */
        private Integer maxConsecutiveFailures;
    }
}
