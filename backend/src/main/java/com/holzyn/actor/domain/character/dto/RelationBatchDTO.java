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

package com.holzyn.actor.domain.character.dto;

import com.holzyn.actor.domain.character.vo.RelationDraftVO;

import java.util.List;

/**
 * 角色关系批量入库请求（预览确认后的写入； 支持普通型 NPC 范围）。
 *
 * @param mode 重建/补充：rebuild=先清空相关范围再写入；supplement=仅追加
 *
 * @param characterId 单角色范围时的特殊 NPC ID（scope=character 时用于定位清空范围；可空）
 *
 * @param crowdId 单普通型 NPC 范围时的普通 NPC ID（scope=crowd 时用于定位清空范围；可空）
 *
 * @param items 确认后的关系草稿列表
 */
public record RelationBatchDTO(String mode, Long characterId, Long crowdId, List<RelationDraftVO> items) {
}
