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

import java.util.List;

/**
 * 决策循环结果（阶段 C AgentRuntime 骨架，domain/agent/runtime）。
 * <p>职责：返回一次决策循环的执行摘要——触发源/角色引用/脑力档位/是否重入丢弃/
 * 执行步骤记录/结论消息，供调用方（接线后）观测与排障； 阶段仅被单测与壳方法使用。</p>
 *
 * @param trigger 触发源
 *
 * @param characterRef 统一角色引用（special:{id}/crowd:{id}）
 *
 * @param brainLevel 脑力档位（full/light/programmatic；未解析到返回 null）
 *
 * @param reentrant 是否因防重入被直接丢弃（true=本次未执行管线）
 *
 * @param success 管线是否整体成功（任一环节异常仍继续，仅记录）
 *
 * @param steps 已执行的管线步骤名（perception/memory/thinking/roleplay_action/learning）
 *
 * @param message 结论消息（执行摘要）
 */
public record AgentResult(AgentTrigger trigger, String characterRef, String brainLevel,
                          boolean reentrant, boolean success, List<String> steps, String message) {

    /**
     * 正常执行完成的结果。
     *
     * @param trigger 触发源
     *
     * @param characterRef 角色引用
     *
     * @param brainLevel 脑力档位
     *
     * @param steps 已执行步骤名
     *
     * @param message 结论消息
     *
     * @return 结果
     */
    public static AgentResult success(AgentTrigger trigger, String characterRef, String brainLevel,
                                      List<String> steps, String message) {
        return new AgentResult(trigger, characterRef, brainLevel, false, true, steps, message);
    }

    /**
     * 防重入丢弃的结果（running 中二次触发直接返回，不阻塞）。
     *
     * @param trigger 触发源
     *
     * @param characterRef 角色引用
     *
     * @return 结果
     */
    public static AgentResult reentrant(AgentTrigger trigger, String characterRef) {
        return new AgentResult(trigger, characterRef, null, true, false, List.of(),
                "决策循环正在执行中，本次触发已按防重入丢弃（不阻塞）");
    }
}
