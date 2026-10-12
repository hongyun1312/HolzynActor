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

package com.holzyn.actor.domain.memory.vo;

import com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory;

import java.time.LocalDateTime;

/**
 * L3 核心记忆「核心块」视图对象。
 * <p>职责：前端「核心记忆」分组的核心块条目——块键/显示名/内容/重要度/agent 可编辑标识/版本/时间。</p>
 *
 * @param id 核心块主键
 *
 * @param blockKey 块键（identity/personality/beliefs/values/goals/relations/custom:*）
 *
 * @param blockName 块显示名
 *
 * @param content 块内容
 *
 * @param importance 重要度（1~5）
 *
 * @param editableByAgent 1=agent 可自编辑 / 0=仅用户可改（只读块）
 *
 * @param version 版本号（自编辑/升级 +1）
 *
 * @param supersedesId 被本条取代的旧块 id（可空=首版）
 *
 * @param createdAt 创建时间
 *
 * @param updatedAt 更新时间
 */
public record CoreBlockVO(Long id, String blockKey, String blockName, String content,
                          Integer importance, Integer editableByAgent, Integer version,
                          Long supersedesId, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /**
     * 实体 → VO。
     *
     * @param b 核心块实体
     *
     * @return 视图对象
     */
    public static CoreBlockVO of(ActorCoreMemory b) {
        if (b == null) {
            return null;
        }
        return new CoreBlockVO(b.getId(), b.getBlockKey(), b.getBlockName(), b.getContent(),
                b.getImportance(), b.getEditableByAgent(), b.getVersion(),
                b.getSupersedesId(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
