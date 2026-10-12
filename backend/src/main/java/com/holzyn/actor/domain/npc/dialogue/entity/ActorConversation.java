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

package com.holzyn.actor.domain.npc.dialogue.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会话实体，对应表 actor_dialogue（九模块规范化重构 原 actor_conversation，对话模块）。
 * <p>职责：承载一次对话会话（单聊 single / 群聊 group），记录归属用户、模式、
 * 标题与世界事件注入开关；lastMessageAt 用于会话列表排序。</p>
 */
@Data
@Entity
@Table(name = "actor_dialogue")
public class ActorConversation {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 模式：single 单聊 / group 群聊 */
    @Column(length = 10, nullable = false)
    private String mode = "single";

    /** 会话标题 */
    @Column(length = 100)
    private String title;

    /** 对话所在地（场景地点；空=通过手机等远程通讯软件对话） */
    @Column(length = 200)
    private String location;

    /** 归一化地点 ID（：引用 actor_world_location.id；对话所在地归一后落此， 事件按地点路由基准） */
    @Column(name = "location_id")
    private Long locationId;

    /** 对话发生时的世界时间快照（世界时钟 gameTimeText，如「世界历 0025年03月12日 14时30分」） */
    @Column(name = "game_time_text", length = 120)
    private String gameTimeText;

    /** 是否启用世界事件注入：0否/1是 */
    @Column(name = "world_event_enabled")
    private Integer worldEventEnabled = 0;

    /**
     * 会话类型（新增）：null/空=常规会话（按 mode 推断 single/group）；
     * npc_group=「地点 NPC 内群」（无玩家自发对话的承载会话，WorldEngine 在剧情节点/概率
     * 触发群聊轮；玩家可围观与插话）。
     */
    @Column(name = "conversation_type", length = 20)
    private String conversationType;

    /**
     * 会话状态（新增）：active 正常 / archived 已归档。
     * 常规会话保持 active；NPC 内群一轮自发闲聊收尾后置 archived（下次触发重新建会话，
     * 归档时沉淀参与成员经历记忆）。
     */
    @Column(name = "status", length = 20)
    private String status = "active";

    /**
     * 玩家参与时间（新增）：玩家向 NPC 内群发送首条消息即视为「参与」，
     * 记录参与时刻（UI 展示用，参与后世界推进仍继续自动轮）；可空=纯 NPC 内群。
     */
    @Column(name = "player_participated_at")
    private LocalDateTime playerParticipatedAt;

    /** 最后消息时间（列表排序用） */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (mode == null) mode = "single";
        if (worldEventEnabled == null) worldEventEnabled = 0;
        if (status == null) status = "active";
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}