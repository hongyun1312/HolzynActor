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
 * 信息扩散与三层过滤配置。
 * <p>职责：承载 信息包扩散引擎（InfoDiffusionService 每游戏小时规则扩散）与感知三层过滤
 * （空间→社会→认知）的可调参数——扩散总开关、注入上限/预算、AI 失真改写参数、距离兜底值、
 * 到达事件开关、失真缓存上限。代码默认值与 application.yml 同步（同字段缺省值，环境变量可覆盖）。</p>
 * <p>设计对齐（主开发方案《HolzynActor_世界演化与游戏时间系统_开发路径》§6.2 world-info 段 +
 * 开工前用户逐题确认）：
 * <ul>
 * <li>扩散引擎纯规则零成本（diffusion-enabled 默认 true），每游戏小时边界逐小时物化；</li>
 * <li>【世界动态】注入 ≤ inject-max-packets 条（默认 3）、每条 ≤ max-inject-text-length（默认 60）字、
 * 总预算 inject-budget-tokens（默认 300）；</li>
 * <li>认知失真 = AI 改写（distortion-ai-enabled 默认 true）+（NPC/区域 × 信息包 × 游戏日）内存缓存
 * 收敛成本 + 失败回退原文；scene=info_distortion 记账；</li>
 * <li>距离模型 = 沙盘坐标欧氏距离（1 单位=1 米），无坐标回退 distance-fallback-km（默认 1.0）；</li>
 * <li>扩散到达事件（每包每区域首达 1 条，区域传闻版）由 arrival-event-enabled（默认 true）控制。</li>
 * </ul></p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.world-info")
public class WorldInfoProperties {

    /** 扩散总开关（默认 true；false=扩散引擎不物化，感知三层过滤仅空间侧退化为「包未过期且 NPC 在场」） */
    private boolean diffusionEnabled = true;

    /** 【世界动态】注入开关 */
    private boolean injectEnabled = true;

    /** 【世界动态】注入条数上限（默认 3：三层过滤后可见信息包 ≤3 条注入） */
    private int injectMaxPackets = 3;

    /** 【世界动态】注入总预算（字符，默认 300：超出截断/丢弃多余条，防撑爆 Prompt） */
    private int injectBudgetTokens = 300;

    /** 单条注入文本长度上限（字符，默认 60：失真/原文统一截断） */
    private int maxInjectTextLength = 60;

    /** AI 失真改写开关（默认 true；false=认知失真走规则模板：按可信度/距离/渠道加传闻前缀） */
    private boolean distortionAiEnabled = true;

    /** AI 失真改写温度（默认 0.7：改写属创作性任务，适中温度更自然） */
    private double distortionTemperature = 0.7;

    /** AI 失真改写最大输出 token（默认 256：单条消息改写，输出量小） */
    private int distortionMaxTokens = 256;

    /** 失真改写结果缓存上限（条，默认 20000：超限清空防内存膨胀；键=NPC/区域+信息包+游戏日） */
    private int distortionCacheMaxEntries = 20000;

    /** 无坐标时回退的名义距离 */
    private double distanceFallbackKm = 1.0;

    /**
     * 扩散到达事件开关（默认 true：每包每区域首达发布 1 条 kind=world_frame 低重要度事件，
     * 内容=区域传闻版；false=仅物化扩散行不发事件）
     */
    private boolean arrivalEventEnabled = true;

    /**
     * 到达事件发布并行度。
     * <p>到达事件含区域传闻 AI 改写 ——此前在
     * 扩散事务内串行同步执行，导致世界引擎单线程冻结 4 分钟以上、H2 行锁跨 AI 存活。现改为「扩散行
     * 物化（事务内、零 AI）+ 事务提交后异步发布」，本参数控制异步发布线程池并行度：≥1；4 路并发足以
     * 在 1 游戏小时（rate 60 下现实 1 分钟）内消化新到区域，同时避免瞬时数百并发触发供应商限流。</p>
     */
    private int arrivalPublishParallelism = 4;
}
