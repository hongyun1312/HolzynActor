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

package com.holzyn.actor.domain.world.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目事件实体，对应表 actor_event。
 * <p>职责：存储项目级事件（时间线统一节点 + 世界事件总线落库）——手动新增 / AI 从世界观识别生成 /
 * 世界演化归档 / 世界模拟系统事件 / 特殊 NPC 行动（action）/ 居民活动聚合（crowd）/ 引擎世界事件（world）；
 * kind 区分事件性质、source 区分来源；关联场景（scene_id）与演化会话（evolution_id）支持
 * 「仅场景当事人记忆」的归档追溯。</p>
 * <p>B1 扩展（世界事件总线，）：新增 location_id/location_name/character_ids/visibility/
 * payload_json 五列；
 * visibility 可见性语义：global=全项目知晓 / location=同地点知晓（缺省）/ private=仅当事人；
 * character_ids 参与角色 ref 列表（逗号分隔：special:3,crowd:12；兼容单值）；source_key 幂等键
 * （A5 已预埋，B1 起 publish 前按 project_id+source_key 去重，防重复发布，修）；
 * importance 事件重要度（B4 / 记忆沉淀依据，缺省 3）。</p>
 */
@Data
@Entity
@Table(name = "actor_event")
public class ActorEvent {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
     * 事件类型：event 世界事件 / manual 手动 / evolution 演化归档 / system 系统
     * + B1 扩展：action 特殊NPC行动 / crowd 居民活动聚合 / world 引擎生成世界事件
     * <p><b>阶段④ 加宽</b>：20 → 30。本列在<b>导入链路</b>上是无界写入——
     * {@code HolzynImportService} 把导入包 {@code actor/events.jsonl} 里的 {@code kind} 原样落库
     * （无白名单归一、无截断），以及 {@code TimelineService} 在 {@code events-enabled=false} 兜底路径上
     * 把 HTTP 请求体的 {@code kind} 原样落库（{@code TimelineController.POST /projects/{id}/events}）。
     * 两者都可被任意长字符串触发 {@code Value too long}（H2 22001）——与 usage_log.scene 同一种故障形态。
     * 加宽到 30 降低触发面。</p>
     */
    @Column(nullable = false, length = 30)
    private String kind = "event";

    /** 事件标题 */
    @Column(length = 200)
    private String title;

    /** 事件内容（含时间地点经过影响） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 关联角色 ID（可空） */
    @Column(name = "character_id")
    private Long characterId;

    /** 关联场景 ID（演化场景，可空） */
    @Column(name = "scene_id")
    private Long sceneId;

    /** 关联演化会话 ID（演化归档时非空） */
    @Column(name = "evolution_id")
    private Long evolutionId;

    /**
     * 来源：manual 手动 / ai AI识别 / simulation 世界模拟 / evolution 演化归档 / dialog 对话抽取
     * + B1 扩展：action 行动 / crowd 居民活动
     * <p><b>阶段④ 加宽</b>：20 → 30。理由同 {@link #kind}——
     * {@code HolzynImportService} 导入链路把 {@code source} 原样落库、无归一无截断。</p>
     */
    @Column(nullable = false, length = 30)
    private String source = "manual";

    /**
     * 决策来源（P0-03 决策源可追溯，VARCHAR(32) 存 {@code DecisionSource} 枚举名）：
     * AI 真实决策 / AI_RETRY 重试决策 / FALLBACK_PROGRAMMATIC 程序化兜底 /
     * BUDGET_EXHAUSTED 预算耗尽降级 / DECISION_MISSING 决策缺失。
     * 行动事件（kind=action）由行动决策的来源打标；空值=旧数据/非决策来源事件。
     */
    @Column(name = "decision_source", length = 32)
    private String decisionSource;

    /** 游戏时刻（小时数，自纪元起，可空） */
    @Column(name = "game_hour")
    private Long gameHour;

    /** 发生地点 ID（引用 actor_world_location.id；无则 null，B1 新增） */
    @Column(name = "location_id")
    private Long locationId;

    /** 地点名快照（兜底显示与路由，B1 新增） */
    @Column(name = "location_name", length = 100)
    private String locationName;

    /** 参与角色 ref 列表（逗号分隔：special:3,crowd:12；兼容单值，B1 新增） */
    @Column(name = "character_ids", columnDefinition = "TEXT")
    private String characterIds;

    /** 可见性：global 全项目知晓 / location 同地点知晓（缺省）/ private 仅当事人（B1 新增） */
    @Column(length = 20)
    private String visibility = "location";

    /** 结构化载荷（行动决策 JSON / 居民状态快照 / 事件正文结构化版，B1 新增） */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /**
     * 幂等键（A5 预埋， 部分； 世界事件总线落地后用于防重复发布，
     * 如 plan:{planId} / crowd:{projectId}:{gameHour}——届时 publish 前按 source_key 去重）
     */
    @Column(name = "source_key", length = 100)
    private String sourceKey;

    /** 事件重要度（1-5，B4 / 记忆沉淀依据：参与者经历记忆/项目级大事记写入阈值来源；缺省 3） */
    private Integer importance = 3;

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
        if (kind == null || kind.isBlank()) kind = "event";
        if (source == null || source.isBlank()) source = "manual";
        if (importance == null) importance = 3;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
