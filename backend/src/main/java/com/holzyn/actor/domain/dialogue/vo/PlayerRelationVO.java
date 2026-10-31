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

package com.holzyn.actor.domain.dialogue.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 玩家-角色关系视图对象。
 * <p>职责：作为玩家-角色关系接口（GET/PUT /api/worlds/{projectId}/player-profile/relations/*）的
 * 返回载体——好感/信任/关系阶段/备注（M3 可编辑基线）+ AI 维护的关系状态摘要/沉淀版本
 * （M6 沉淀写回，本编辑器只读展示）。</p>
 */
@Data
public class PlayerRelationVO {

    /** 归属项目 ID */
    private Long projectId;

    /** 对谈角色引用（npc:{id}） */
    private String characterRef;

    /** 好感（0~100；M3 可编辑基线，M6 沉淀 AI 判定 delta） */
    private Integer affinity;

    /** 信任（0~100） */
    private Integer trust;

    /** 关系阶段（陌生/相识/熟络/…） */
    private String stage;

    /** 备注（用户可编辑） */
    private String note;

    /** AI 维护的「关系状态摘要」（M6 沉淀写回，只读） */
    private String stateSummary;

    /** 沉淀版本（幂等；M6 每次沉淀 +1） */
    private Integer version;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
