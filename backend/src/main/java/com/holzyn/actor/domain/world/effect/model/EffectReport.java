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

import java.util.ArrayList;
import java.util.List;

/**
 * 一批效果 op 的落地报告。
 *
 * <p>成功返回每个 op 的状态；失败由 {@link EffectApplyException} 抛出，事务整批回滚，
 * 调用方据此把 {@code actor_plan.reason_code} 写成 {@code EFFECT_FAILED:{i}}。</p>
 *
 * @param results 按原 ops 顺序的结果
 */
public record EffectReport(List<EffectResult> results) {

    /**
     * 空报告。
     *
     * @return 不含结果的报告
     */
    public static EffectReport empty() {
        return new EffectReport(List.of());
    }

    /**
     * 将所有 op 视为已落地（默认实现用；不经过真实分支时使用）。
     *
     * @param ops 原始 ops
     * @return 报告
     */
    public static EffectReport allApplied(List<com.holzyn.actor.domain.world.adjudicate.model.EffectOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return empty();
        }
        List<EffectResult> list = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            list.add(EffectResult.applied(i, ops.get(i).op(), null, null));
        }
        return new EffectReport(list);
    }

    /**
     * 按下标查结果（不存在返回 null）。
     *
     * @param index 下标
     * @return 结果或 null
     */
    public EffectResult get(int index) {
        for (EffectResult r : results) {
            if (r.index() == index) {
                return r;
            }
        }
        return null;
    }
}
