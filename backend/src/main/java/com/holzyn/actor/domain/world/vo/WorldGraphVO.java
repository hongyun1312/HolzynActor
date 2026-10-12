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

package com.holzyn.actor.domain.world.vo;

import java.util.List;
import java.util.Map;

/**
 * 世界状态图谱查询层视图对象容器（只读）。
 * <p>职责：承载 {@code WorldGraphQueryService} 统一暴露的<b>六维单域 + 三种聚合视角 +
 * 全局概览</b>查询结果——地点/时间/角色/事件/关系/势力+物资六维节点、角色视角/地点视角/
 * 势力视角聚合视图、全局概览。全部为不可变 record，纯只读投影（无缓存直查，与库内实时一致）。</p>
 */
public final class WorldGraphVO {

    private WorldGraphVO() {
    }

    // ==================== 六维单域 ====================

    /** 地点节点（actor_world_location 投影）。 */
    public record LocationNode(
            Long id,
            String name,
            String type,
            String intro,
            Integer importance,
            Integer sortOrder) {
    }

    /** 时间节点（actor_world_clock 投影；权威=引擎推进位置）。 */
    public record TimeNode(
            Long gameSecond,
            Long gameHour,
            Long gameDay,
            Integer paused,
            Integer worldRunning,
            String calendarName,
            String gameTimeText) {
    }

    /** 角色节点（actor_profile 投影；ref=统一身份 npc:{id}）。 */
    public record CharacterNode(
            Long id,
            String ref,
            String name,
            String title,
            String type,
            String affiliation,
            String occupation,
            String location,
            Long locationId,
            String stage,
            Integer importance,
            Integer isProtagonist) {
    }

    /** 事件节点（actor_event 投影；参与角色名列表为解析快照）。 */
    public record EventNode(
            Long id,
            String kind,
            String title,
            String content,
            Long gameHour,
            Long locationId,
            String locationName,
            String visibility,
            Integer importance,
            List<String> characterNames) {
    }

    /** 关系边（actor_relation 投影；from→to 社会关系）。 */
    public record RelationEdge(
            Long id,
            String fromRef,
            String fromName,
            String toRef,
            String toName,
            String relationType,
            Integer affinity,
            Integer trust,
            String relationState,
            String description) {
    }

    /** 势力节点（actor_meso_group 投影；memberCount=活跃成员数；relations=组织间关系分）。 */
    public record FactionNode(
            Long id,
            String name,
            String type,
            String status,
            Double influence,
            String leaderRef,
            Long memberCount,
            Map<String, Integer> relations) {
    }

    /** 宏观/物资节点（MacroStateService 快照投影——人口/粮价/库存/民怨/季节/收成）。 */
    public record MacroNode(
            Long population,
            Double grainStock,
            Double grainProduction,
            Double grainPrice,
            Double marketActivity,
            Double bankDeposit,
            Double currencySupply,
            Double grievance,
            String season,
            Double harvestFactor,
            String topFaction,
            String topIssue,
            String summary) {
    }

    // ==================== 三种聚合视角 ====================

    /** 角色视角：角色 + 所在位置 + 关系 + 所属组织 + 参与事件。 */
    public record CharacterView(
            CharacterNode character,
            LocationNode location,
            List<RelationEdge> relations,
            List<FactionNode> factions,
            List<EventNode> events) {
    }

    /** 地点视角：地点 + 在场角色 + 近期事件 + 关联实体状态。 */
    public record LocationView(
            LocationNode location,
            List<CharacterNode> characters,
            List<EventNode> events,
            List<EntityNode> entities) {
    }

    /** 势力视角：势力 + 成员 + 组织间关系分。 */
    public record FactionView(
            FactionNode faction,
            List<MemberNode> members,
            Map<String, Integer> relations) {
    }

    /** 实体状态节点（actor_world_entity 当前有效行投影，as-of 当前）。 */
    public record EntityNode(
            Long id,
            String entityType,
            String entityKey,
            String name,
            String currentState,
            String recentChange,
            Long validFromGameDay) {
    }

    /** 组织成员节点（actor_meso_membership + 角色名）。 */
    public record MemberNode(
            Long actorId,
            String actorRef,
            String actorName,
            String role,
            Double influence,
            String status) {
    }

    // ==================== 全局概览 ====================

    /** 概览计数（六维存量）。 */
    public record Counts(
            long locations,
            long characters,
            long events,
            long relations,
            long factions) {
    }

    /** 编年史条目节点（actor_world_chronicle 投影，概览最近条目）。 */
    public record ChronicleNode(
            Long id,
            Long gameDay,
            String title,
            String content,
            Integer importance,
            String season,
            String source) {
    }

    /** 全局概览：时间 + 计数 + 宏观物资 + 最近事件 + 最近编年史。 */
    public record Overview(
            TimeNode time,
            Counts counts,
            MacroNode macro,
            List<EventNode> recentEvents,
            List<ChronicleNode> recentChronicles) {
    }
}
