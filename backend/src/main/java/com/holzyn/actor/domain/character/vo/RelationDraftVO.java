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

package com.holzyn.actor.domain.character.vo;

/**
 * AI 识别出的单条角色关系草稿（生成预览 / 批量入库共用）。
 *
 * @param from 发起方角色名（可为角色表/人群表不存在的「幽灵」名，入库时名称兜底）
 *
 * @param to 目标方角色名
 *
 * @param relationType 关系类型（亲属/师徒/敌对等）
 *
 * @param description 关系描述（可空）
 */
public record RelationDraftVO(String from, String to, String relationType, String description) {
}
