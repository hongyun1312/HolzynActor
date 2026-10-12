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

package com.holzyn.actor.domain.agent.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 世界事件发布规格（B1 世界事件总线，domain/agent/event）。
 * <p>发布方通过 {@link WorldEventBus#publish(Long, Long, EventSpec)} 描述「世界发生了什么」：
 * 事件类别（kind）/ 来源（source）/ 参与角色（characterRefs，统一 characterRef：special:{id}/crowd:{id}）/
 * 地点（locationId + locationName 快照）/ 可见性（visibility）/ 标题内容 / 结构化载荷（payloadJson）/
 * 事件重要度（importance，B4 / 记忆沉淀依据）/ 游戏时刻（gameHour，缺省由总线从世界时钟解析）/
 * 幂等键（sourceKey）。</p>
 * <p>对齐系统框架 §3.1（R2）：复用 kind/source/game_hour，不另设 event_type/game_time 同义列；
 * kind 枚举扩展 action/crowd/world；visibility=global/location（缺省）/private。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSpec {

    /**
     * 事件类别：action 特殊NPC行动 / crowd 居民活动聚合 / world 引擎世界事件 / evolution 演化归档 /
     * event 世界事件 / manual 手动 / system 系统；缺省 event
     */
    private String kind;

    /** 来源：action / crowd / world / evolution / manual / ai / simulation / dialog；缺省 manual */
    private String source;

    /** 主角色 ID（特殊 NPC 单值，回填 actor_event.character_id 兼容旧字段；可空） */
    private Long characterId;

    /** 参与角色 ref 列表（special:{id} / crowd:{id}；与 characterId 可并用，去重后写入 character_ids） */
    private List<String> characterRefs;

    /** 关联场景 ID（演化归档追溯用，可空） */
    private Long sceneId;

    /** 关联演化会话 ID（演化归档追溯用，可空） */
    private Long evolutionId;

    /** 发生地点 ID（引用 actor_world_location.id；可空） */
    private Long locationId;

    /** 地点名快照（兜底显示与路由，可空） */
    private String locationName;

    /** 可见性：global / location（缺省）/ private */
    private String visibility;

    /** 事件标题（可空，兜底「世界事件」） */
    private String title;

    /** 事件内容（时间地点经过影响；可空） */
    private String content;

    /** 结构化载荷（行动决策 JSON / 居民状态快照 / 事件正文结构化版；可空） */
    private String payloadJson;

    /**
     * 事件重要度（1-5，B4 / 记忆沉淀依据：参与者经历记忆/项目级大事记的写入阈值来源；
     * 缺省 3；重大世界事件（world/evolution）由发布方显式给 4+ 以进【世界大事记】）
     */
    private Integer importance;

    /** 游戏时刻（小时数，自纪元起；缺省由总线从世界时钟解析） */
    private Long gameHour;

    /**
     * 幂等键（如 plan:{planId} / crowd:{projectId}:{gameHour} / evolution:{evolutionId}；
     * 发布前按 project_id+source_key 去重，防重复发布，修）
     */
    private String sourceKey;
}
