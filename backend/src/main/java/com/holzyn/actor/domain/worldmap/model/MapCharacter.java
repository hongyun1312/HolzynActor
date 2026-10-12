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

package com.holzyn.actor.domain.worldmap.model;

import lombok.Data;

/**
 * 世界地图生成 · 角色上下文（AI 输入 / 机制兜底共用的角色摘要）。
 * <p>供住所归属（同居分组、户主选定、家名派生）与 AI 输入组装使用。</p>
 */
@Data
public class MapCharacter {

    /** 角色统一引用（npc:{id}） */
    private String ref;

    /** 角色名 */
    private String name;

    /** 类型（special / common） */
    private String type;

    /** 职业（可空） */
    private String occupation;

    /** 归属/势力（可空） */
    private String affiliation;

    /** 当前地点名（可空） */
    private String location;

    /** 当前地点 ID（可空） */
    private Long locationId;

    /** 重要度（特殊 NPC；普通 NPC 1） */
    private Integer importance;

    /** 是否户主候选（特殊 NPC / 长者优先，由同居分组填充） */
    private boolean ownerCandidate;
}
