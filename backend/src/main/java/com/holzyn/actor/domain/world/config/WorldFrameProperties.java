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

import java.util.ArrayList;
import java.util.List;

/**
 * 每日世界帧体系配置。
 * <p>职责：承载世界帧生成的可调参数——AI 主通道开关/低温度/输出上限、每帧信息包上限、
 * 紧急更新限频与触发阈值。代码默认值与 application.yml 同步（同字段缺省值，环境变量可覆盖）。</p>
 * <p>设计对齐：
 * AI 主通道默认开（失败/超时回退机制兜底帧）；温度 0.3（低温度保结构稳定）；紧急更新
 * ≤1 次/游戏日（内存 Map 计数器）；事件重要度 ≥ 阈值自动触发紧急更新（默认 4）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.world-frame")
public class WorldFrameProperties {

    /** AI 主通道开关（默认 true；false=每日帧直接走机制兜底，零 AI 成本） */
    private boolean aiEnabled = true;

    /** AI 生成温度（默认 0.3：低温度保证 JSON 结构与世界观连续性稳定） */
    private double temperature = 0.3;

    /** AI 最大输出 token（默认 4096：帧 JSON 含天气/宏观事件/信息包/环境变化，对齐 worldgen 口径） */
    private int maxTokens = 4096;

    /** 每帧信息包上限（默认 5：超出丢弃多余包，防注入膨胀/刷屏） */
    private int maxPacketsPerFrame = 5;

    /** 紧急更新限频（次/游戏日，默认 1：超出抛 400；内存 Map 计数器，重启丢失可接受） */
    private int maxEmergencyPerDay = 1;

    /** 紧急更新自动触发的事件重要度阈值（默认 4：重大世界事件才触发；world_frame 自身事件不触发防环） */
    private int emergencyImportanceThreshold = 4;

    /** 世界观越界关键词（规则②，默认空=不校验；命中则帧校验失败回退机制兜底） */
    private List<String> bannedKeywords = new ArrayList<>();
}
