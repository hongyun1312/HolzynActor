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
 * 实时刺激类型枚举。
 * <p>职责：标识一次「即时决策」的刺激来源——{@link RealTimeDecisionService} 检测到刺激后
 * 携带本类型进入 {@link AgentRuntime#trigger(AgentTrigger, String, DecisionContext)} 的
 * {@link AgentTrigger#STIMULUS} 触发链路，供决策上下文（stimulusType/stimulusText/reason）
 * 与后续 打断恢复 消费。</p>
 * <p>刺激源对齐主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.2 刺激源表：
 * ① 状态紧急升档（STATE_URGENCY，感知 tick 5 游戏分钟粒度）/
 * ② 世界事件/重大行动（WORLD_EVENT，事件总线毫秒级）/
 * ③ 他角色行动（NPC_ACTION，事件总线 kind=action 毫秒级）+ 他角色靠近/到达（NPC_APPROACH，
 * WorldSandboxStateService.onNpcMoved 挂钩）/
 * ④ 环境变化。</p>
 */
public enum StimulusType {

    /** 自身状态紧急升档（urgency 0→1/2/3；感知 tick 内检测，5 游戏分钟粒度） */
    STATE_URGENCY("state_urgency", "自身状态紧急（urgency 升档）"),

    /** 世界事件/重大行动（actor_event kind=world/evolution/system 等，事件总线实时） */
    WORLD_EVENT("world_event", "世界事件/重大行动"),

    /** 他角色行动 */
    NPC_ACTION("npc_action", "他角色行动"),

    /** 他角色靠近/到达（WorldSandboxStateService.onNpcMoved 挂钩，位置变化实时） */
    NPC_APPROACH("npc_approach", "他角色靠近/到达"),

    /**
     * 链式反应（：特殊 NPC 行动执行后，同地点其他特殊 NPC + 关系密切异地相关人物经
     * ChainReactionService 触发的完整即时决策；一跳不递归，防抖复用每角色每刺激源冷却）
     */
    CHAIN_REACTION("chain_reaction", "他角色行动引发的链式反应"),

    /**
     * 等待计划到期（：等待类计划到点后重新 AI 决策——角色自己的计划到期，非外部刺激，
     * 由 WaitPlanService 直接触发不经过打断/探针；供 reason/审计区分）
     */
    WAIT_PLAN_DUE("wait_plan_due", "等待计划到期重新决策"),

    /** 环境变化 */
    ENVIRONMENT("environment", "环境变化（M5 接入）"),

    /**
     * Unity 上报的玩家视野/交互刺激（U1 服务端对接层 外部世界观察到的玩家
     * 接近/注视/动作等 → 目标 NPC 即时决策；复用既有冷却[默认 10 游戏分钟/角色/源]与预算仲裁；
     * broadcast=true 时额外受项目级广播冷却约束，见 UnityPerceptionService）
     */
    UNITY_PERCEPTION("unity_perception", "Unity 上报的玩家视野/交互刺激"),

    /**
     * 内心冒出的念头/即时意向（高频微决策 每 tick 微决策「非继续当下事」且
     * 低概率命中时触发——自己想做的事，非外部刺激；走 STIMULUS 链路复用打断/预算/冷却护栏）
     */
    MENTAL_URGE("mental_urge", "内心冒出的念头/即时意向（N5 高频微决策）"),

    /**
     * 生理需求告急（生理需求→行动决策耦合：感知 tick 检测到任一生理四需低于
     * 危险阈值[hunger&lt;20 等]时触发——身体的本能驱动，优先级高于一切手头事务；
     * 走 STIMULUS 链路打断当前行动，决策环节由 {@code PhysioNeedService} 危险前置检查
     * 程序化强制（0 AI），对齐「饿了会放下手头事去吃饭」的真人行为）
     */
    PHYSIOLOGICAL_URGE("physiological_urge", "生理需求告急（危险区强制满足）");

    /** 稳定编码（写入 DecisionContext.stimulusType / reason 文本；对外契约用 code 不用枚举名） */
    private final String code;

    /** 中文说明 */
    private final String description;

    StimulusType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 稳定编码（对外契约：DecisionContext.stimulusType 的取值）。
     *
     * @return 编码（小写蛇形）
     */
    public String code() {
        return code;
    }

    /**
     * 中文说明。
     *
     * @return 说明文本
     */
    public String description() {
        return description;
    }
}
