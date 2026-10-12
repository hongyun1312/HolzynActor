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

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长期记忆视图对象。
 * <p>职责：向前端暴露记忆条目——归属（projectId/characterId）、类型（type 四类 +
 * subtype 会话摘要子类型）、内容、重要度与创建时间；不含软删/审计字段。</p>
 */
@Data
public class MemoryVO {

    /** 记忆主键 */
    private Long id;

    /** 项目级记忆归属 */
    private Long projectId;

    /** 角色 ID（空=项目级记忆） */
    private Long characterId;

    /** L2 类型：semantic 语义 / episodic 情节 / reflection 反思 / plan 计划 */
    private String type;

    /** 子类型（type=semantic 且为会话摘要时为 "summary"，否则空——前端「摘要/事实」标签用） */
    private String subtype;

    /** 记忆内容 */
    private String content;

    /** 重要度（1-5） */
    private Integer importance;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 实体转 VO。
     *
     * @param m 记忆实体
     *
     * @return VO 对象
     */
    public static MemoryVO of(ActorMemory m) {
        MemoryVO vo = new MemoryVO();
        vo.setId(m.getId());
        vo.setProjectId(m.getProjectId());
        vo.setCharacterId(m.getCharacterId());
        vo.setType(m.getType());
        vo.setSubtype(ActorMemoryTypes.isSummary(m.getType(), m.getContextJson())
                ? ActorMemoryTypes.SUBTYPE_SUMMARY : null);
        vo.setContent(m.getContent());
        vo.setImportance(m.getImportance());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }
}
