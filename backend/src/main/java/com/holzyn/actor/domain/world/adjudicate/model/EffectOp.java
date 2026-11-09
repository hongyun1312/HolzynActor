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

package com.holzyn.actor.domain.world.adjudicate.model;

import tools.jackson.databind.JsonNode;

/**
 * 裁决产生的效果草稿（§4.6）。
 *
 * <p>只校验并落 {@code actor_plan.effects_json}；真正应用由 
 * {@code EffectApplier} 承担（本类不落库、不改状态）。</p>
 *
 * @param op             操作码（fact.upsert / relation.delta / memory.add ...）
 * @param body           操作体
 * @param idempotencyKey 幂等键（可空，补齐）
 */
public record EffectOp(String op, JsonNode body, String idempotencyKey) {
}
