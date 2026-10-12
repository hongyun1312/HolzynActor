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
 * 宏观社会演化 · 中观协调层配置。
 * <p>职责：中观层（组织/势力/群体）可调参数——总开关、指令触发阈值（重大事件 severity）、
 * 例行指令周期、指令有效期、AI 生成档位联动（AiIntensityGate 四档：programmatic 全规则 /
 * light·full 规则+重要事件 AI / max 全 AI）。可配置、可关闭、可回滚（enabled=false 回到
 * 前语义：无中观指令、无组织处境注入、响应不回收）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.meso")
public class MesoProperties {

    /** 中观层总开关（默认 true；false=不建组/不生成指令/不回收响应/不注入组织处境） */
    private boolean enabled = true;

    /** 指令触发的宏观事件最低 severity（默认 3；severity≥3 的重大事件才生成 react/crisis 指令） */
    private int minEventSeverity = 3;

    /** 例行指令开关（默认 true；每旬首日例行指令维持组织存在感） */
    private boolean routineEnabled = true;

    /** 例行指令周期（游戏日，默认 10=每旬；与势力 tick 同频） */
    private int routineIntervalDays = 10;

    /** 指令有效期（游戏秒，默认 3 游戏日=259200；到期失效） */
    private long ttlSeconds = 3L * 86400L;

    /** 单事件最大指令数（防膨胀护栏；默认 30） */
    private int maxDirectivesPerEvent = 30;

    /** 手动建组开关（调试/冒烟用；默认 true） */
    private boolean manualGroupEnabled = true;

    /** 感知【组织处境】段长度预算（默认 200 字） */
    private int perceptionBudget = 200;

    /** 决策【组织处境】段长度预算（默认 120 字） */
    private int decisionBudget = 120;
}
