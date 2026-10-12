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

package com.holzyn.actor.domain.crowd.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 普通型 NPC AI 批量生成入参。
 * <p>职责：承载「AI 依据世界观批量生成普通型 NPC」的数量参数（1~500，每批 30 自动分批）。</p>
 * <p><b>口径</b>：生成的居民落统一主表 {@code actor_profile} 且 {@code type='common'}；
 * 作息运行态（state/lastAction/dailyPlanJson）不在档案里，由状态模块惰性建
 * {@code actor_state} 行（见 {@code NpcActivityStateService}），故本 DTO 不含运行态字段。</p>
 *
 * @param count 生成数量（1~500）
 */
public record OrdinaryNpcGenerateDTO(
        @NotNull(message = "生成数量不能为空")
        @Min(value = 1, message = "生成数量最少 1 个")
        @Max(value = 500, message = "生成数量最多 500 个")
        Integer count
) {
}
