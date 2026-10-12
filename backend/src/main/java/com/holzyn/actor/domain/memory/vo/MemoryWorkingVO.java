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

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * L1 短期（工作记忆）视图对象。
 * <p>对外契约：工作记忆列表 API 返回的条目——含 item_type（draft/task/context/perception）、
 * status（active/consolidated/expired）、importance_hint（巩固重要度建议）、
 * expires_at（TTL 过期）/ consolidated_at（巩固时间）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.1（L1 表结构）。</p>
 */
@Data
public class MemoryWorkingVO {

    /** 主键 */
    private Long id;

    /** 项目归属 */
    private Long projectId;

    /** 归属角色（special:{id}/crowd:{id}；NULL=项目级工作项） */
    private String characterRef;

    /** 工作项来源会话标识（conversation_id 或 "auto"） */
    private String sessionKey;

    /** 工作项类型：draft 待复核草稿 / task 进行中任务 / context 上下文快照 / perception 感知快照 */
    private String itemType;

    /** 工作项内容 */
    private String content;

    /** 来源（dialog/action/event/perception/planning/manual） */
    private String source;

    /** 状态：active / consolidated / expired */
    private String status;

    /** 巩固进 L2 时的重要度建议（1~5） */
    private Integer importanceHint;

    /** TTL 过期时间 */
    private LocalDateTime expiresAt;

    /** 巩固时间（status=consolidated 时） */
    private LocalDateTime consolidatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 实体 → VO。
     *
     * @param w L1 工作项实体
     *
     * @return VO（非空）
     */
    public static MemoryWorkingVO of(ActorMemoryWorking w) {
        MemoryWorkingVO vo = new MemoryWorkingVO();
        vo.setId(w.getId());
        vo.setProjectId(w.getProjectId());
        vo.setCharacterRef(w.getCharacterRef());
        vo.setSessionKey(w.getSessionKey());
        vo.setItemType(w.getItemType());
        vo.setContent(w.getContent());
        vo.setSource(w.getSource());
        vo.setStatus(w.getStatus());
        vo.setImportanceHint(w.getImportanceHint());
        vo.setExpiresAt(w.getExpiresAt());
        vo.setConsolidatedAt(w.getConsolidatedAt());
        vo.setCreatedAt(w.getCreatedAt());
        return vo;
    }
}
