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

package com.holzyn.actor.domain.item.model;

/**
 * 持有量不足（决策 A：严格拒绝，整批回滚）。
 *
 * <p>抛出后由 {@code EffectApplierImpl} 包成 {@code EffectApplyException}，
 * 事务回滚、{@code actor_plan.reason_code} 记 {@code EFFECT_FAILED:{i}}。
 * 世界因此不会出现「凭空多出来的东西」，也不会出现负库存。</p>
 */
public class InsufficientItemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 物品键 */
    private final String itemKey;

    /** 来源持有者 */
    private final String holderRef;

    /** 需要的数量 */
    private final int required;

    /** 实际持有量 */
    private final int available;

    /**
     * 构造。
     *
     * @param itemKey   物品键
     * @param holderRef 来源持有者
     * @param required  需要数量
     * @param available 实际数量
     */
    public InsufficientItemException(String itemKey, String holderRef, int required, int available) {
        super("物品不足：" + itemKey + " @" + holderRef + "，需要 " + required + "，实际 " + available);
        this.itemKey = itemKey;
        this.holderRef = holderRef;
        this.required = required;
        this.available = available;
    }

    /** @return 物品键 */
    public String getItemKey() {
        return itemKey;
    }

    /** @return 来源持有者 */
    public String getHolderRef() {
        return holderRef;
    }

    /** @return 需要数量 */
    public int getRequired() {
        return required;
    }

    /** @return 实际数量 */
    public int getAvailable() {
        return available;
    }
}
