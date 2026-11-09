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
 * 效果落地失败。
 *
 * <p>携带失败 op 的下标，供调用方写 {@code EFFECT_FAILED:{index}}；
 * 异常从 {@code EffectApplier.apply} 抛出后由外层事务整批回滚。</p>
 */
public class EffectApplyException extends RuntimeException {

    private final int index;
    private final String op;

    /**
     * 构造失败异常。
     *
     * @param index 失败 op 下标
     * @param op    失败 op 名
     * @param cause 原始异常
     */
    public EffectApplyException(int index, String op, Throwable cause) {
        super("效果落地失败: op[" + index + "]=" + op + " : " + (cause == null ? "" : cause.getMessage()), cause);
        this.index = index;
        this.op = op;
    }

    /** 失败 op 下标。 */
    public int index() {
        return index;
    }

    /** 失败 op 名。 */
    public String op() {
        return op;
    }
}
