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
 * 配方的一项材料（{@code actor_item_recipe.inputs_json} 的元素）。
 *
 * <p>为什么单独建模而不是在服务里到处 {@code json.get("itemKey")}：材料比对是 R-CRAFT-01 的
 * 核心，口径必须只有一处——{@code qty} 是「一份」的用量，实际扣多少由批次数乘出来。</p>
 *
 * @param itemKey 物品键
 * @param qty     每份用量（正数）
 */
public record RecipeInput(String itemKey, int qty) {

    /**
     * 指定批次数的总用量。
     *
     * @param batches 份数
     *
     * @return 总用量（批次非法时按 0 处理）
     */
    public int totalFor(int batches) {
        return qty <= 0 || batches <= 0 ? 0 : qty * batches;
    }
}
