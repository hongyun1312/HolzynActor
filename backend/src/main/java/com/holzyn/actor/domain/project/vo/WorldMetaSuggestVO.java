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

package com.holzyn.actor.domain.project.vo;

/**
 * 世界名与世界简介建议 VO（新建世界向导「世界名与简介」步的 AI 建议结果）。
 *
 * <p>职责：把 AI 产出的两个短字段交给前端回填表单——不写库，用户改写后随
 * 「保存并进入 / 保存并初始化」一并落库（落库取用户最终值）。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 *
 * @param name    世界名（6-14 字，已按约束截断）
 * @param summary 世界简介（60-100 字，已按约束截断）
 */
public record WorldMetaSuggestVO(String name, String summary) {
}
