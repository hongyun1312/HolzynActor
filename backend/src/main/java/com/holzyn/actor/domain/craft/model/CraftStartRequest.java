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
 * 开工请求（{@code craft.make} 效果的入参）。
 *
 * <p>{@code facilityRef} / {@code locationId} / {@code locationName} 由裁决层解析后带下来：
 * 裁决层已经知道「他在哪儿、那儿有什么」，让服务层再查一遍仓库只会出现两套口径。</p>
 *
 * @param projectId      项目/世界 ID
 * @param actorRef       干活的人
 * @param recipeKey      配方键
 * @param batches        做几份（≥1）
 * @param facilityRef    使用/占用的设施 ref（可空=随处可做，或按角色当前地点推断）
 * @param locationId     地点 ID（可空）
 * @param locationName   地点名（可空）
 * @param eventId        触发事件 ID（可空）
 * @param gameSecond     开工时的游戏秒
 * @param idempotencyKey 幂等键（可空）
 */
public record CraftStartRequest(Long projectId, String actorRef, String recipeKey, int batches,
                                String facilityRef, Long locationId, String locationName,
                                Long eventId, long gameSecond, String idempotencyKey) {
}
