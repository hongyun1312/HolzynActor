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

package com.holzyn.actor.domain.crowd.vo;

/**
 * 普通型 NPC 标准字段数据视图（AI 拟定/预览/管理共用； 分类体系重构）。
 * <p>职责：承载单条字段字典（field + level1 + level2 + source）——race 种族（两级）、
 * affiliation 归属（一级）、occupation 职业（一级）；source 为出处（引用世界观，禁止编造）。</p>
 *
 * @param field 字段名：race/affiliation/occupation
 *
 * @param level1 一级值（race=种族大类，如 人族；affiliation=归属名，如 会馆；occupation=职业名）
 *
 * @param level2 二级值（仅 race 用：次级种族，如 汉族/猫妖；其余字段为空）
 *
 * @param source 出处（引用世界观的具体字段/段落）
 */
public record FieldDictVO(
        String field,
        String level1,
        String level2,
        String source
) {
}
