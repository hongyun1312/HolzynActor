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

/**
 * 项目季节骨架配置更新请求。
 * <p>职责：PUT /climate-config 入参——configJson 为四季骨架 JSON 文本
 * （如 {"spring":{"baseTempC":[12,22],"weatherTendency":{"晴":40,"雨":35,"阴":20,"风":5},
 * "daylightOffsetMin":0,"colorTone":"新绿"}, ...}）；不改变季节固定绑定表。</p>
 *
 * @param configJson 骨架 JSON 文本（必填）
 */
public record ClimateConfigDTO(String configJson) {
}
