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

import java.util.List;

/**
 * 一份配方的只读视图（裁决/提示词/结算共用）。
 *
 * <p><b>为什么要有这个视图而不是直接用实体</b>：规则层（{@code DefaultActionAdjudicator}）
 * 不能碰 JPA 实体——它的单测不加载 Spring；而且提示词需要「逐字节稳定」的文本，
 * 实体上的懒加载字段会带来不确定性。视图只带裁决真正要用的字段。</p>
 *
 * @param recipeKey       配方键
 * @param outputItemKey   产出物品键（制作=物品；建造=落成的设施/物件键）
 * @param outputQty       单份产出数量
 * @param outputKind      产出形态（item/facility/prop），见 {@link CraftOutputKind}
 * @param facilityType    需要的设施类型（null=随处可做）
 * @param durationMinutes 单份耗时（游戏分钟）
 * @param inputs          材料清单（每份用量）
 */
public record CraftRecipeView(String recipeKey, String outputItemKey, int outputQty,
                              String outputKind, String facilityType, int durationMinutes,
                              List<RecipeInput> inputs) {

    /**
     * 是否需要设施。
     *
     * @return true=配方写了设施类型
     */
    public boolean needsFacility() {
        return facilityType != null && !facilityType.isBlank();
    }

    /**
     * 是否建造类（产物落到世界里，而不是进背包）。
     *
     * @return true=facility/prop
     */
    public boolean isBuild() {
        return CraftOutputKind.FACILITY.equalsIgnoreCase(outputKind)
                || CraftOutputKind.PROP.equalsIgnoreCase(outputKind);
    }

    /**
     * 指定批次数的总产出。
     *
     * @param batches 份数
     *
     * @return 总产出（批次非法时按 0 处理）
     */
    public int totalOutput(int batches) {
        return outputQty <= 0 || batches <= 0 ? 0 : outputQty * batches;
    }

    /**
     * 指定批次数的总耗时（游戏分钟）。
     *
     * @param batches 份数
     *
     * @return 总耗时
     */
    public int totalMinutes(int batches) {
        int per = Math.max(0, durationMinutes);
        return per * Math.max(1, batches);
    }
}
