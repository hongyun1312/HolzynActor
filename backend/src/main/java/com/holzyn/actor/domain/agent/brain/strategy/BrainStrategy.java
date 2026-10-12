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

package com.holzyn.actor.domain.agent.brain.strategy;

import java.util.Map;
import java.util.Optional;

/**
 * 可插拔决策策略（P2-3 BrainStrategy）。
 * <p>把「角色此刻做什么」的决策管线显式化为<b>有序策略链</b>（双过程理论显式化：
 * 快通道[本能/习惯/脚本]优先、System 2 深思仅在快通道不足时介入）——每条策略
 * 是一个可独立测试、可插拔的决策单元，{@link BrainStrategyRouter} 按角色类型与
 * 脑力档位组链、按 {@link #priority()} 降序链式责任（首个产出决策者胜出）。</p>
 * <p><b>真实感语义</b>：生理危险（本能强制）→ 空间候选（快选择）→ AI 深思
 * （System 2）→ 程序化兜底（continue 当前行为），与真人「先本能后思考」一致；
 * 未来模块（如 O6 发育阶段门控）可在任意优先级插入新策略，不改既有链。</p>
 * <p><b>行为兼容</b>：各策略内部逻辑 = 既有管线对应段原样搬移（经既有服务门面
 * 委托），不新增/改变决策行为。</p>
 */
public interface BrainStrategy {

    /**
     * 策略名（trace/日志标识；恒唯一）。
     */
    String name();

    /**
     * 链内优先级（降序尝试；越大越先）。
     */
    int priority();

    /**
     * 该策略是否适用于当前决策上下文（角色类型/脑力档位过滤）。
     *
     * @param ctx 决策上下文
     *
     * @return true=进入本链尝试
     */
    boolean applies(DecisionContext ctx);

    /**
     * 产出决策（链式责任：本策略适用时被尝试）。
     *
     * @param ctx 决策上下文
     *
     * @return 内部规范决策 Map（空 = 本策略不命中，链继续尝试下一策略）
     */
    Optional<Map<String, Object>> decide(DecisionContext ctx);

    /**
     * 命中后是否执行调用方后处理（护栏/前瞻/想法；程序化兜底语义下不执行——
     * 与既有「programmatic 档直接返回、不走后处理」行为对齐）。
     */
    default boolean postProcess() {
        return true;
    }
}
