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
 * 开工结果（幂等重放时 {@code replay=true}，且不会再扣一次料）。
 *
 * @param jobId            作业 ID
 * @param recipeKey        配方键
 * @param outputItemKey    产出物品键
 * @param outputQty        总产出
 * @param batches          份数
 * @param finishGameSecond 预计完工游戏秒
 * @param replay           是否幂等重放
 */
public record CraftJobResult(Long jobId, String recipeKey, String outputItemKey, int outputQty,
                             int batches, long finishGameSecond, boolean replay) {
}
