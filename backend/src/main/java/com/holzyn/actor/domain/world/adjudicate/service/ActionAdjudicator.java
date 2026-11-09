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

package com.holzyn.actor.domain.world.adjudicate.service;

import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.adjudicate.model.AdjudicationContext;

/**
 * 行动裁决器（§4.5）：纯规则，零 AI。
 */
public interface ActionAdjudicator {

    /**
     * 裁决一次行动。
     *
     * @param ctx 只读上下文
     *
     * @return 规则结论（可能是 UNSURE，交上层决定是否走 AI）
     */
    ActionVerdict adjudicate(AdjudicationContext ctx);
}
