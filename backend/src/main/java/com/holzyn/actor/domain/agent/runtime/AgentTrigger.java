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

package com.holzyn.actor.domain.agent.runtime;

/**
 * 智能体触发源枚举（阶段 C AgentRuntime 骨架，domain/agent/runtime）。
 * <p>权威=六组件设计 §4.2 / 系统框架 §5.2：六种触发源 + 优先级
 * （高=对话/事件/引擎实时；中=引擎 tick/手动；低=Learning 周期异步）。
 * 只声明不接线（由对话/引擎/手动接入 AgentRuntime 统一入口）。
 * 实时行为决策系统：新增第七种 {@link #STIMULUS}（高优先级）——
 * 刺激驱动的即时决策通道触发源（与周期通道 ENGINE_TICK 正交，由 RealTimeDecisionService 触发）。</p>
 */
public enum AgentTrigger {

    /** 对话消息/事件回应（由 ChatService/GroupChatService 桥接） */
    DIALOG(1, "对话消息/事件回应（ChatService/GroupChatService 桥接）"),

    /** 世界事件注入后的在场回应 */
    EVENT(1, "世界事件注入后的在场回应"),

    /** 世界推进引擎逐游戏小时/跨日触发（由 WorldEngine 调） */
    ENGINE_TICK(2, "世界推进引擎逐游戏小时/跨日触发"),

    /** 页面手动触发行动/演化 */
    MANUAL(2, "页面手动触发行动/演化"),

    /** 引擎实时调用 */
    ENGINE_API(1, "引擎实时调用（P2 预留）"),

    /** Learning 周期（反思/关系/生态，低优先级异步） */
    CONSOLIDATION(3, "Learning 周期（反思/关系/生态）"),

    /**
     * 实时刺激即时决策（实时行为决策系统， 状态紧急/事件/他角色/环境 → 即时打断决策；
     * 与周期通道（ENGINE_TICK 每游戏小时）正交的即时通道触发源，由 RealTimeDecisionService 触发；
     * 非对话类 → 决策循环走完整行动执行（对齐主开发方案 §4.1 双通道））
     */
    STIMULUS(1, "实时刺激即时决策（状态紧急/事件/他角色/环境）");

    /** 优先级 */
    private final int priority;

    /** 中文说明 */
    private final String description;

    AgentTrigger(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    /**
     * 优先级（1=高 / 2=中 / 3=低）。
     *
     * @return 优先级
     */
    public int priority() {
        return priority;
    }

    /**
     * 中文说明。
     *
     * @return 说明文本
     */
    public String description() {
        return description;
    }

    /**
     * 是否为「对话/事件」类触发（决策循环 ④ 扮演桩的判定依据）。
     *
     * @return true 表示对话类触发
     */
    public boolean isDialogue() {
        return this == DIALOG || this == EVENT;
    }
}
