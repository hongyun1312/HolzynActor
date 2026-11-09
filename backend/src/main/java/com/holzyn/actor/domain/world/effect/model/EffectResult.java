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

package com.holzyn.actor.domain.world.effect.model;

/**
 * 单个效果 op 的落地结果。
 *
 * <p>{@code factId}/{@code planId} 供调用方回写 knowledge 或日志；
 * {@code status=SKIPPED_NO_INVENTORY} 表示物品域未装配，按降级不抛异常。</p>
 *
 * @param index   原 ops 列表下标
 * @param op      op 名
 * @param status  applied / skipped_no_inventory
 * @param factId  事实 ID（fact.* 成功时）
 * @param planId  计划 ID（schedule.plan 成功时）
 * @param message 说明（跳过原因等）
 */
public record EffectResult(int index, String op, String status, Long factId, Long planId, String message) {

    /** 已落地。 */
    public static final String APPLIED = "applied";

    /** 物品域未装配，降级跳过。 */
    public static final String SKIPPED_NO_INVENTORY = "skipped_no_inventory";

    /** 能力未启用（如市场开关关闭），降级跳过。 */
    public static final String SKIPPED_DISABLED = "skipped_disabled";

    /**
     * 构造“已落地”结果。
     *
     * @param index 下标
     * @param op    op 名
     * @param factId 事实 ID（可空）
     * @param planId 计划 ID（可空）
     * @return 结果
     */
    public static EffectResult applied(int index, String op, Long factId, Long planId) {
        return new EffectResult(index, op, APPLIED, factId, planId, null);
    }

    /**
     * 构造“跳过”结果。
     *
     * @param index 下标
     * @param op    op 名
     * @param message 跳过原因
     * @return 结果
     */
    public static EffectResult skipped(int index, String op, String message) {
        return new EffectResult(index, op, SKIPPED_NO_INVENTORY, null, null, message);
    }

    /**
     * 构造“能力未启用”的跳过结果。
     *
     * @param index 下标
     * @param op    op 名
     * @param message 跳过原因
     * @return 结果
     */
    public static EffectResult disabled(int index, String op, String message) {
        return new EffectResult(index, op, SKIPPED_DISABLED, null, null, message);
    }
}
