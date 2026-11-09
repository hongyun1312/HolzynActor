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

package com.holzyn.actor.domain.world.effect.service;

import com.holzyn.actor.domain.world.adjudicate.model.EffectOp;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;

import java.util.List;

/**
 * 效果统一入口（§4.5）。
 *
 * <p>所有效果必须经此落地：裁决后、行动执行后、对话抽取后。实现类负责
 * schema 校验、幂等键、事务内零 AI、任一 op 失败整批回滚。</p>
 */
public interface EffectApplier {

    /**
     * 应用一批效果（失败抛 {@code EffectApplyException}）。
     *
     * @param ops 效果列表
     * @param ctx 上下文
     */
    void apply(List<EffectOp> ops, EffectContext ctx);

    /**
     * 应用并返回每个 op 的落地结果。
     *
     * <p>默认实现兼容只实现 {@link #apply} 的测试替身；真实实现会覆盖并返回
     * 事实/计划 ID 与跳过状态。</p>
     *
     * @param ops 效果列表
     * @param ctx 上下文
     * @return 落地报告
     */
    default EffectReport applyWithReport(List<EffectOp> ops, EffectContext ctx) {
        apply(ops, ctx);
        return EffectReport.allApplied(ops);
    }
}
