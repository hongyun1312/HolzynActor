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

import java.time.LocalDateTime;

/**
 * 角色关系行视图对象（阶段 D：手动 CRUD / AI 精修返回）。
 * <p>含动态字段 affinity/trust/relation_state/updated_at/last_interaction_at 与
 * 正式 characterRef（fromRef/toRef，修）——前端关系列表抽屉与拓扑右键编辑的数据载体。</p>
 *
 * @param id 关系主键
 *
 * @param projectId 归属项目 ID
 *
 * @param fromRef 发起方 characterRef（special:{id}/crowd:{id}；幽灵=null）
 *
 * @param toRef 目标方 characterRef（同上）
 *
 * @param fromName 发起方名称快照
 *
 * @param toName 目标方名称快照
 *
 * @param relationType 关系类型
 *
 * @param description 关系描述
 *
 * @param affinity 好感 -100~100
 *
 * @param trust 信任 0~100
 *
 * @param relationState 关系状态键（stranger/acquaintance/friend/intimate/rival/hostile）
 *
 * @param relationStateLabel 关系状态中文标签（展示）
 *
 * @param updatedAt 动态字段最近更新时间
 *
 * @param lastInteractionAt 最近一次互动时间
 */
public record CharacterRelationVO(
        Long id,
        Long projectId,
        String fromRef,
        String toRef,
        String fromName,
        String toName,
        String relationType,
        String description,
        Integer affinity,
        Integer trust,
        String relationState,
        String relationStateLabel,
        LocalDateTime updatedAt,
        LocalDateTime lastInteractionAt
) {
}
