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
 * 事件冷存储，对应表 {@code actor_event_archive}。
 *
 * <p><b>为什么存在</b>：热表 {@code actor_event} 按项目上限滚动（默认 5000），
 * 超限即物理删除最旧事件。二日实跑里这一上限让事件表全程恒为 5000——
 * 叙事原料（编年史提炼、时间线回放、角色经历）与审计证据在 48 个游戏小时后已不复存在。</p>
 *
 * <p><b>写入语义</b>：只由 {@code WorldEventBusImpl#enforceRetention} 在删除前搬运
 * （同一事务：先 {@code saveAll} 归档，再 {@code deleteAll} 热表；归档失败则本轮不删）。
 * 本表只追加、不更新、不参与任何业务查询——与 {@code actor_memory_archive} /
 * {@code actor_world_chronicle_archive} 同一定位。</p>
 *
 * <p><b>列口径</b>：与 {@link ActorEvent} 一一对应（含因果链三列与 decision_source），
 * 另加 {@code event_id}（原主键）、{@code game_day}（按游戏日回捞用）、
 * {@code archived_at}（归档时刻）。</p>
 */
@Data
@Entity
@Table(name = "actor_event_archive")
public class ActorEventArchive {

    /** 冷表主键（自增，与原事件主键无关） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原事件主键（{@code actor_event.id}） */
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 项目 ID */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 事件类别（{@code actor_event.kind}） */
    @Column(nullable = false, length = 30)
    private String kind;

    /** 事件来源（{@code actor_event.source}） */
    @Column(nullable = false, length = 30)
    private String source;

    /** 决策来源（决策源可追溯） */
    @Column(name = "decision_source", length = 32)
    private String decisionSource;

    /** 主角色 ID */
    @Column(name = "character_id")
    private Long characterId;

    /** 参与角色 ref 列表（逗号分隔） */
    @Column(name = "character_ids", columnDefinition = "TEXT")
    private String characterIds;

    /** 关联场景 ID */
    @Column(name = "scene_id")
    private Long sceneId;

    /** 关联演化会话 ID */
    @Column(name = "evolution_id")
    private Long evolutionId;

    /** 发生地点 ID */
    @Column(name = "location_id")
    private Long locationId;

    /** 地点名快照 */
    @Column(name = "location_name", length = 100)
    private String locationName;

    /** 可见性 */
    @Column(length = 20)
    private String visibility;

    /** 标题 */
    @Column(length = 200)
    private String title;

    /** 正文 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 结构化载荷 */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** 重要度 1-5 */
    private Integer importance;

    /** 游戏时刻（小时） */
    @Column(name = "game_hour")
    private Long gameHour;

    /** 幂等键 */
    @Column(name = "source_key", length = 100)
    private String sourceKey;

    /** 直接原因事件 ID（因果链） */
    @Column(name = "cause_event_id")
    private Long causeEventId;

    /** 因果链根标识 */
    @Column(name = "correlation_id", length = 40)
    private String correlationId;

    /** 因果链深度 */
    private Integer depth;

    /** 原事件创建时间 */
    @Column(name = "event_created_at")
    private LocalDateTime eventCreatedAt;

    /** 原事件更新时间 */
    @Column(name = "event_updated_at")
    private LocalDateTime eventUpdatedAt;

    /**
     * 归档事件所属游戏日（{@code floorDiv(gameSecond, 86400)}）。
     * <p>口径：以<b>事件自己发生的日子</b>（由 {@code game_hour} 推导）为准，事件无时刻时才回退
     * 归档时的当前游戏日——否则按游戏日回捞会把不同年份的事件全堆在同一天。</p>
     */
    @Column(name = "game_day")
    private Long gameDay;

    /** 归档时刻（真实时间） */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /**
     * 由热表事件构造冷表行（同构拷贝 + 归档元数据）。
     *
     * @param e       热表事件（可空）
     *
     * @param gameDay 归档时的游戏日（可空）
     *
     * @param now     归档时刻（可空=当前时间）
     *
     * @return 冷表行；{@code e == null} 返回 {@code null}
     */
    public static ActorEventArchive of(ActorEvent e, Long gameDay, LocalDateTime now) {
        if (e == null) {
            return null;
        }
        ActorEventArchive a = new ActorEventArchive();
        a.setEventId(e.getId());
        a.setProjectId(e.getProjectId());
        a.setKind(e.getKind());
        a.setSource(e.getSource());
        a.setDecisionSource(e.getDecisionSource());
        a.setCharacterId(e.getCharacterId());
        a.setCharacterIds(e.getCharacterIds());
        a.setSceneId(e.getSceneId());
        a.setEvolutionId(e.getEvolutionId());
        a.setLocationId(e.getLocationId());
        a.setLocationName(e.getLocationName());
        a.setVisibility(e.getVisibility());
        a.setTitle(e.getTitle());
        a.setContent(e.getContent());
        a.setPayloadJson(e.getPayloadJson());
        a.setImportance(e.getImportance());
        a.setGameHour(e.getGameHour());
        a.setSourceKey(e.getSourceKey());
        a.setCauseEventId(e.getCauseEventId());
        a.setCorrelationId(e.getCorrelationId());
        a.setDepth(e.getDepth());
        a.setEventCreatedAt(e.getCreatedAt());
        a.setEventUpdatedAt(e.getUpdatedAt());
        a.setGameDay(gameDay);
        a.setArchivedAt(now == null ? LocalDateTime.now() : now);
        return a;
    }
}
