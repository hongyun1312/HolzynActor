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

package com.holzyn.actor.domain.craft.model;

/**
 * 一样材料的缺口（R-CRAFT-01 判「材料不齐」时的证据）。
 *
 * <p>为什么不只返回 boolean：失败要说得出「少什么、少几份」——旁人的可见文本
 * （{@code observedText}）和提示词都靠它，否则失败只能写成「少一样东西」，
 * 角色下一次决策还是不知道该去凑什么。</p>
 *
 * @param itemKey 物品键
 * @param need    需要的总量
 * @param have    身上的总量
 */
public record MaterialGap(String itemKey, int need, int have) {

    /**
     * 还差多少。
     *
     * @return 缺口数量（≥0）
     */
    public int missing() {
        return Math.max(0, need - have);
    }

    /**
     * 是否真的缺。
     *
     * @return true=身上不够
     */
    public boolean isShort() {
        return missing() > 0;
    }

    /**
     * 人话描述（失败文本/提示词共用，保证同一状态文本一致）。
     *
     * @return 如「面粉 差 2 份（有 1）」
     */
    public String describe() {
        return itemKey + " 差 " + missing() + " 份（有 " + have + "）";
    }
}
