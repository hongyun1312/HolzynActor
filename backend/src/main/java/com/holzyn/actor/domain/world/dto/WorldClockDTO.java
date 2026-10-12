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

package com.holzyn.actor.domain.world.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;

/**
 * 世界时钟更新请求 DTO。
 * <p>职责：承载世界时钟的更新入参——速率（1~8760）、暂停开关、真实锚点与起始游戏时刻；
 * 全部字段可选，空字段表示保持原值。</p>
 * <p>B3（世界推进引擎）：新增 worldRunning「世界持续运行」常驻开关（0/1）——
 * 开启时从此刻重置引擎回补基准（last_engine_run_at），避免补推开启前积压的历史。</p>
 *
 * @param rate 速率（每真实小时推进的游戏小时数，1~8760）
 *
 * @param paused 暂停开关（true=暂停推进，临时冻结）
 *
 * @param worldRunning 世界持续运行常驻开关（1=持续运行，0=关闭；null=保持原值）
 *
 * @param worldStartAt 真实时刻锚点（默认=项目创建时刻）
 *
 * @param worldStartGameHour 锚点对应的游戏起始小时数
 */
public record WorldClockDTO(
        @Min(value = 1, message = "速率最小为 1") @Max(value = 8760, message = "速率最大为 8760") Integer rate,
        Boolean paused,
        Integer worldRunning,
        LocalDateTime worldStartAt,
        Long worldStartGameHour
) {
}
