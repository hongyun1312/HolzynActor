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

package com.holzyn.actor.domain.dialogue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 对谈会话实体，对应表 actor_talk_session。
 * <p>职责：承载「一次对谈」——与「浮生」主模式同级的世界静止聊天会话。单聊
 * （character_ref 非空）或群聊（type=group，location_id/location_name 场景，M5）均可；
 * <b>冻结时刻</b>（frozen_game_second + frozen_snapshot_json）在进入对谈时由
 * {@code DialogueFreezeService} 快照，聊天期间角色与世界状态一律以冻结快照为「当下」。</p>
 * <p><b>表名说明（设计偏差 2026-10-12 M1）</b>：设计稿 §7 拟定 actor_dialogue_session，但
 * 既有「对话」（世界联动聊天）模块已占用 actor_dialogue / actor_dialogue_member /
 * actor_dialogue_config / actor_dialogue_message 表——对谈表族整体改用 actor_talk_*
 * 前缀，与旧世界联动对话表彻底区分（两个同级模式并存，表名不混淆）。</p>
 * <p><b>零 AI</b>：本表仅会话元信息，创建/列表/关闭均为纯本地 DB 短事务，无 AI 调用。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "actor_talk_session")
public class ActorDialogueSession {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（对谈按项目隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属用户 ID（本地单用户恒 1；保留扩展位） */
    @Column(name = "user_id")
    private Long userId = 1L;

    /** 会话标题（自动取首条玩家消息/角色名；可后续修改） */
    @Column(length = 100)
    private String title;

    /** 会话类型：single 单聊 / group 群聊（M5） */
    @Column(length = 16)
    private String type = "single";

    /** 单聊对谈角色（npc:{id}）；群聊为 NULL */
    @Column(name = "character_ref", length = 32)
    private String characterRef;

    /** 群聊场景地点 ID（M5 换场用；单聊 NULL） */
    @Column(name = "location_id")
    private Long locationId;

    /** 群聊场景地点名（M5 换场用；单聊 NULL） */
    @Column(name = "location_name", length = 128)
    private String locationName;

    /** 冻结时刻：进入对谈时的权威世界时间（引擎位置 last_game_second） */
    @Column(name = "frozen_game_second")
    private Long frozenGameSecond;

    /** 冻结快照 JSON：角色冻结状态（位置/情绪）+ 同地点在场快照 + 世界动态行 */
    @Column(name = "frozen_snapshot_json", columnDefinition = "LONGTEXT")
    private String frozenSnapshotJson;

    /** 会话状态：active 进行中 / closed 已关闭 / archived 已归档 */
    @Column(length = 16)
    private String status = "active";

    /** 滚动窗口摘要缓存（M4 超窗自动摘要落点；随对话滚动增量更新） */
    @Column(length = 1000)
    private String summary;

    /** 已折叠进摘要的消息条数（M4 增量摘要水位：摘要覆盖前 N 条超窗消息） */
    @Column(name = "summary_message_count")
    private Integer summaryMessageCount = 0;

    /** 作者注释（M4，可选；常驻提示引导氛围） */
    @Column(name = "author_note", length = 1000)
    private String authorNote;

    /** 作者注释位置（深度，M4） */
    @Column(name = "author_note_depth")
    private Integer authorNoteDepth;

    /** 作者注释频率（每 N 条注入，M4） */
    @Column(name = "author_note_frequency")
    private Integer authorNoteFrequency;

    /** 沉淀后关系档案版本（幂等，M6） */
    @Column(name = "player_relation_version")
    private Integer playerRelationVersion = 0;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间（列表按此倒序；每次新消息刷新） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
