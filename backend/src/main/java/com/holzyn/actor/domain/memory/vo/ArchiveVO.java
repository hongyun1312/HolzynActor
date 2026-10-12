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

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;

import java.time.LocalDateTime;

/**
 * 归档（冷存储）视图对象。
 * <p>职责：前端「记忆归档」页的归档条目——来源表/类型/原文/重要度/归档原因/时间/还原状态。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.4/§8。</p>
 *
 * @param id 归档主键
 *
 * @param projectId 项目归属
 *
 * @param characterRef 归属角色（special:{id}/crowd:{id}；null=项目级/无归属）
 *
 * @param sourceTable 来源表：actor_memory / actor_core_memory / actor_memory_working
 *
 * @param sourceMemoryId 原记忆 id（null=无来源，如纯手动存档）
 *
 * @param type 归档时类型（episodic/semantic/reflection/plan/core/working…）
 *
 * @param content 归档内容（原文保留，不摘要化）
 *
 * @param contextJson 归档时上下文快照（可选）
 *
 * @param importanceAtArchive 归档时重要度
 *
 * @param archiveReason 归档原因：age 超龄 / superseded 被覆盖 / downgraded 反思降级 / evicted 预算淘汰 / manual 手动
 *
 * @param archivedAt 归档时间
 *
 * @param gameDayAtArchive 归档时游戏日
 *
 * @param restored 是否已还原（0 未还原 / 1 已还原回来源表）
 *
 * @param createdAt 创建时间
 */
public record ArchiveVO(Long id, Long projectId, String characterRef, String sourceTable, Long sourceMemoryId,
                        String type, String content, String contextJson, Integer importanceAtArchive,
                        String archiveReason, LocalDateTime archivedAt, Long gameDayAtArchive,
                        Integer restored, LocalDateTime createdAt) {

    /**
     * 实体 → VO。
     *
     * @param a 归档实体
     *
     * @return 视图对象
     */
    public static ArchiveVO of(ActorMemoryArchive a) {
        if (a == null) {
            return null;
        }
        return new ArchiveVO(a.getId(), a.getProjectId(), a.getCharacterRef(), a.getSourceTable(),
                a.getSourceMemoryId(), a.getType(), a.getContent(), a.getContextJson(),
                a.getImportanceAtArchive(), a.getArchiveReason(), a.getArchivedAt(),
                a.getGameDayAtArchive(), a.getRestored(), a.getCreatedAt());
    }
}
