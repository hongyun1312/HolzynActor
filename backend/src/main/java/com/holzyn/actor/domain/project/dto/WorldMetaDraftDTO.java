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

package com.holzyn.actor.domain.project.dto;

/**
 * 世界名/世界简介生成的「未落库设定草稿」入参。
 *
 * <p><b>为什么不用 {@code WorldSettingDTO}</b>：那个 DTO 是保存入参，{@code name} 上有
 * {@code @NotBlank}——创意发散刚生成完、还没落库时并没有世界名（正是要 AI 起名的场景），
 * 套用它会直接 400。本 DTO 全部字段可选，只作为提示词素材。</p>
 *
 * @param name        参考名（可空；留空表示「从设定里重新起名」，不会被 AI 回声复用）
 *
 * @param genre       题材（八类之一）
 *
 * @param tone        世界程度（casual/light/standard/hard）
 *
 * @param era         时代背景
 *
 * @param geography   地理/地图设定
 *
 * @param factions    势力/阵营
 *
 * @param magicSystem 规则体系
 *
 * @param culture     文化/风俗
 *
 * @param history     历史背景
 *
 * @param freeText    补充设定（创意发散的定调/本质一段话最适合放这里）
 */
public record WorldMetaDraftDTO(
        String name,
        String genre,
        String tone,
        String era,
        String geography,
        String factions,
        String magicSystem,
        String culture,
        String history,
        String freeText
) {
}
