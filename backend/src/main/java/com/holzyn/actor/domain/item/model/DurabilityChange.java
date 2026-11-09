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
 * 一次耐久变化的结果（磨损修复共用）。
 *
 * <p>{@code before = after = -1} 表示「根本没找到这件东西」——与「耐久没变」
 * （{@code before == after}）是两件事，裁决层需要区分：前者说明他手里没这件，
 * 后者说明这东西还完好。</p>
 *
 * @param itemKey 物品键
 * @param before  变化前耐久（-1=目标不存在）
 * @param after   变化后耐久（-1=目标不存在）
 * @param broke   是否因此损坏（耐久归零，物品已从持有栈移除）
 * @param replayed 是否因幂等键重复而被跳过
 */
public record DurabilityChange(String itemKey, int before, int after, boolean broke, boolean replayed) {

    /** 目标不存在（没这件东西）。 */
    public static DurabilityChange missing(String itemKey) {
        return new DurabilityChange(itemKey, -1, -1, false, false);
    }

    /** 是否真的找到了这件东西。 */
    public boolean present() {
        return before >= 0;
    }

    /** 耐久是否真的变了。 */
    public boolean changed() {
        return present() && before != after;
    }

    /**
     * 损坏程度 0–100（耐久百分比）。
     *
     * @return 0=断了，100=完好；目标不存在返回 -1
     */
    public int condition() {
        return present() ? Math.max(0, Math.min(100, after)) : -1;
    }
}
