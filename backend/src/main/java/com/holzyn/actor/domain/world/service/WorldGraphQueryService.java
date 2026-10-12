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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界状态图谱统一查询层（只读门面，调研报告 ③-1）。
 * <p>职责：把分散在 {@code WorldMemoryService}/{@code TimelineService}/{@code PerceptionService}/
 * {@code MacroStateService}/{@code LocationNormalizationService} 各自实现的世界状态查询，收拢为
 * <b>统一只读聚合查询门面</b>——感知/决策/对话/宏观/调试可复用，对齐「先查既有门面」纪律
 * （挂 {@code LocationNormalizationService} 同级，domain/world/service）。</p>
 * <ul>
 * <li><b>六维单域</b>：地点（actor_world_location）/ 时间（actor_world_clock，权威引擎位置）/
 * 角色（actor_profile）/ 事件（actor_event）/ 关系（actor_relation）/ 势力+物资
 * （actor_meso_group + MacroStateService 宏观快照）；</li>
 * <li><b>三种聚合视角</b>：角色视角（角色+位置+关系+所属组织+参与事件）/ 地点视角
 * （地点+在场角色+近期事件+关联实体状态）/ 势力视角（势力+成员+组织间关系）；</li>
 * <li><b>全局概览</b>：时间+六维计数+宏观物资+最近事件+最近编年史。</li>
 * </ul>
 * <p><b>关联口径（对齐既有）</b>：角色↔事件 = character_id 单值 ∪ character_ids LIKE
 * {@code npc:{id}}（与 MUSE 经历卷 {@code countByProjectIdAndCharacterRef} 同源）；
 * 角色↔势力 = actor_meso_membership（group_id→actor_id），无 membership 时按 affiliation 文本
 * 匹配势力名兜底；关系端点 = ref/名称解析，幽灵端点（id=0 仅名）按名称展示。</p>
 * <p><b>纯只读</b>：无缓存直查（项目量级百级，实时一致优先——世界高频变动，缓存漂移会破坏
 * 「图谱=当前世界真实快照」语义）；零 AI、零新表、零写入；JPA 查询只取所需列。</p>
 * <p><b>异常隔离</b>：每个公开查询独立 try/catch——单点数据源失败仅降级该段（warn 日志），
 * 不阻断整个视图；BizException(404) 透传（实体不存在）。</p>
 */
@Slf4j
@Service
public class WorldGraphQueryService {

    /** 组织类型：自发群体（势力维度排除——对齐 MacroStateService factions 派生口径 type≠adhoc） */
    private static final String GROUP_TYPE_ADHOC = "adhoc";

    /** 关系活跃状态（成员统计口径） */
    private static final String MEMBER_STATUS_ACTIVE = "active";

    /** 单域事件查询上限（防全量拉爆，默认 50） */
    private static final int EVENT_DEFAULT_LIMIT = 50;
    private static final int EVENT_MAX_LIMIT = 200;

    /** 视角内事件条数上限 */
    private static final int VIEW_EVENT_LIMIT = 20;

    /** 实体状态：地点类型（地点视角关联实体过滤） */
    private static final String ENTITY_TYPE_LOCATION = "location";

    /** Jackson 解析器（组织间关系 meta_json 解析；线程安全静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 依赖 ====================

    private final WorldLocationRepository locationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorEventRepository eventRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorWorldChronicleRepository chronicleRepository;
    private final ActorWorldClockRepository clockRepository;

    /** 世界时钟服务（时间换算/历法格式化；字段注入保持构造器不变，null=降级） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 宏观状态服务（势力+物资快照；字段注入保持构造器不变，null=降级空段） */
    @Autowired(required = false)
    private MacroStateService macroStateService;

    /** 实体状态仓库（地点视角关联实体状态；字段注入保持构造器不变，null=跳过） */
    @Autowired(required = false)
    private ActorWorldEntityRepository entityRepository;

    /**
     * 构造器（仓库全量构造注入，服务可选字段注入——对齐 WorldMemoryService 模式）。
     */
    public WorldGraphQueryService(WorldLocationRepository locationRepository,
                                  ActorCharacterRepository characterRepository,
                                  ActorEventRepository eventRepository,
                                  ActorCharacterRelationRepository relationRepository,
                                  ActorMesoGroupRepository groupRepository,
                                  ActorMesoMembershipRepository membershipRepository,
                                  ActorWorldChronicleRepository chronicleRepository,
                                  ActorWorldClockRepository clockRepository) {
        this.locationRepository = locationRepository;
        this.characterRepository = characterRepository;
        this.eventRepository = eventRepository;
        this.relationRepository = relationRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.chronicleRepository = chronicleRepository;
        this.clockRepository = clockRepository;
    }

    // ==================== 六维单域 ====================

    /**
     * 地点列表（actor_world_location，sortOrder 升序）。
     *
     * @param projectId 项目 ID
     *
     * @return 地点节点列表
     */
    public List<WorldGraphVO.LocationNode> locations(Long projectId) {
        try {
            return locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId).stream()
                    .map(l -> new WorldGraphVO.LocationNode(
                            l.getId(), l.getName(), l.getType(), l.getIntro(),
                            l.getImportance(), l.getSortOrder()))
                    .toList();
        } catch (Exception e) {
            log.warn("[图谱查询] 地点单域查询失败（降级空列表）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 时间节点（actor_world_clock，权威=引擎推进位置；无时钟行返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @return 时间节点（无时钟行 null）
     */
    public WorldGraphVO.TimeNode time(Long projectId) {
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
            if (clock == null) {
                return null;
            }
            long gameSecond = clock.getLastGameSecond() == null
                    ? (clock.getLastGameHour() == null ? 0L : clock.getLastGameHour() * 3600L)
                    : clock.getLastGameSecond();
            long gameHour = gameSecond / 3600L;
            long gameDay = Math.floorDiv(gameSecond, 86400L);
            String calendar = worldClockService == null ? null : worldClockService.calendarNameOf(projectId);
            String text = WorldClockService.formatWorldTimeMinute(gameSecond, calendar);
            return new WorldGraphVO.TimeNode(gameSecond, gameHour, gameDay,
                    clock.getPaused(), clock.getWorldRunning(), calendar, text);
        } catch (Exception e) {
            log.warn("[图谱查询] 时间单域查询失败（降级 null）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * 角色列表（actor_profile，未删除；可按类型/地点过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param type 类型过滤（special/common，可空）
     *
     * @param locationId 地点过滤（归一化 location_id，可空）
     *
     * @return 角色节点列表
     */
    public List<WorldGraphVO.CharacterNode> characters(Long projectId, String type, Long locationId) {
        try {
            List<ActorCharacter> all;
            if (locationId != null) {
                all = characterRepository.findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(projectId, locationId, 0);
            } else if (type != null && !type.isBlank()) {
                all = characterRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, type.trim(), 0);
            } else {
                all = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            }
            return all.stream().map(this::toCharacterNode).toList();
        } catch (Exception e) {
            log.warn("[图谱查询] 角色单域查询失败（降级空列表）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 事件列表（actor_event，新→旧；可按 kind/参与角色/上限过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param kind 事件类型过滤（可空）
     *
     * @param characterId 参与角色过滤（可空；口径=character_id ∪ character_ids LIKE npc:{id}）
     *
     * @param limit 条数上限（默认 50，上限 200）
     *
     * @return 事件节点列表
     */
    public List<WorldGraphVO.EventNode> events(Long projectId, String kind, Long characterId, Integer limit) {
        try {
            List<ActorEvent> all;
            if (characterId != null) {
                all = eventRepository.findByProjectIdAndCharacterRef(projectId, characterId,
                        NpcRefs.of(characterId));
            } else {
                all = eventRepository.findByProjectIdOrderByIdDesc(projectId);
            }
            int max = limit == null ? EVENT_DEFAULT_LIMIT : Math.max(1, Math.min(limit, EVENT_MAX_LIMIT));
            String kindFilter = kind == null ? null : kind.trim();
            List<ActorEvent> filtered = all.stream()
                    .filter(e -> kindFilter == null || kindFilter.equals(e.getKind()))
                    .limit(max)
                    .toList();
            return toEventNodes(projectId, filtered);
        } catch (Exception e) {
            log.warn("[图谱查询] 事件单域查询失败（降级空列表）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 关系列表（actor_relation；可按参与角色过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 参与角色过滤（可空；from/to 任一端点命中即含）
     *
     * @return 关系边列表
     */
    public List<WorldGraphVO.RelationEdge> relations(Long projectId, Long characterId) {
        try {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            String ref = characterId == null ? null : NpcRefs.of(characterId);
            return all.stream()
                    .filter(r -> characterId == null
                            || characterId.equals(r.getFromCharacterId())
                            || characterId.equals(r.getToCharacterId())
                            || (ref != null && (ref.equals(r.getFromRef()) || ref.equals(r.getToRef()))))
                    .map(r -> new WorldGraphVO.RelationEdge(
                            r.getId(), r.getFromRef(), r.getFromName(), r.getToRef(), r.getToName(),
                            r.getRelationType(), r.getAffinity(), r.getTrust(), r.getRelationState(),
                            r.getDescription()))
                    .toList();
        } catch (Exception e) {
            log.warn("[图谱查询] 关系单域查询失败（降级空列表）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 势力列表（actor_meso_group，正式组织 type≠adhoc；含活跃成员数与组织间关系分）。
     *
     * @param projectId 项目 ID
     *
     * @return 势力节点列表
     */
    public List<WorldGraphVO.FactionNode> factions(Long projectId) {
        try {
            List<ActorMesoGroup> groups = groupRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                    .filter(g -> !GROUP_TYPE_ADHOC.equals(g.getType()))
                    .toList();
            List<WorldGraphVO.FactionNode> out = new ArrayList<>();
            for (ActorMesoGroup g : groups) {
                out.add(toFactionNode(projectId, g));
            }
            return out;
        } catch (Exception e) {
            log.warn("[图谱查询] 势力单域查询失败（降级空列表）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 宏观/物资节点（MacroStateService 快照投影：人口/粮价/库存/民怨/季节/收成/势力 top/议题 top）。
     *
     * @param projectId 项目 ID
     *
     * @return 宏观物资节点（未装配/失败返回 null）
     */
    public WorldGraphVO.MacroNode macro(Long projectId) {
        try {
            if (macroStateService == null) {
                return null;
            }
            MacroState st = macroStateService.snapshotOf(projectId);
            MacroState.Economy eco = st.getEconomy();
            String topFaction = st.getFactions().values().stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Faction::getInfluence))
                    .map(MacroState.Faction::getName)
                    .orElse(null);
            String topIssue = st.getIssues().stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity))
                    .map(MacroState.Issue::getTopic)
                    .orElse(null);
            return new WorldGraphVO.MacroNode(
                    st.getPopulation().getTotal(),
                    eco.getGrainStock(), eco.getGrainProduction(), eco.getGrainPrice(),
                    eco.getMarketActivity(), eco.getBankDeposit(), eco.getCurrencySupply(),
                    st.getGrievance(), eco.getSeason(), eco.getHarvestFactor(),
                    topFaction, topIssue, macroStateService.buildSummary(st, ActorWorldMacroSnapshot.TICK_DAILY));
        } catch (Exception e) {
            log.warn("[图谱查询] 宏观物资单域查询失败（降级 null）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    // ==================== 三种聚合视角 ====================

    /**
     * 角色视角：角色 + 所在位置 + 关系 + 所属组织 + 参与事件。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @return 角色聚合视图（角色不存在抛 404）
     */
    public WorldGraphVO.CharacterView characterView(Long projectId, Long characterId) {
        ActorCharacter ch = characterRepository.findByIdAndProjectId(characterId, projectId)
                .orElseThrow(() -> new BizException(404, "角色不存在"));
        WorldGraphVO.CharacterNode node = toCharacterNode(ch);
        WorldGraphVO.LocationNode loc = null;
        if (ch.getLocationId() != null) {
            loc = locationRepository.findById(ch.getLocationId())
                    .map(l -> new WorldGraphVO.LocationNode(
                            l.getId(), l.getName(), l.getType(), l.getIntro(),
                            l.getImportance(), l.getSortOrder()))
                    .orElse(null);
        }
        // 关系：from/to 任一端点命中该角色
        List<WorldGraphVO.RelationEdge> rels = relations(projectId, characterId);
        // 所属组织：membership（npc:{id}）→ 组
        List<WorldGraphVO.FactionNode> factions = factionsOfCharacter(projectId, characterId);
        // 参与事件（口径=character_id ∪ character_ids LIKE）
        List<WorldGraphVO.EventNode> events = List.of();
        try {
            List<ActorEvent> involved = eventRepository.findByProjectIdAndCharacterRef(
                    projectId, characterId, NpcRefs.of(characterId)).stream()
                    .limit(VIEW_EVENT_LIMIT)
                    .toList();
            events = toEventNodes(projectId, involved);
        } catch (Exception e) {
            log.warn("[图谱查询] 角色视角·参与事件失败（降级空列表）: project={} npc={} : {}",
                    projectId, characterId, e.getMessage());
        }
        return new WorldGraphVO.CharacterView(node, loc, rels, factions, events);
    }

    /**
     * 地点视角：地点 + 在场角色 + 近期事件 + 关联实体状态。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID
     *
     * @return 地点聚合视图（地点不存在抛 404）
     */
    public WorldGraphVO.LocationView locationView(Long projectId, Long locationId) {
        ActorWorldLocation loc = locationRepository.findById(locationId)
                .filter(l -> projectId.equals(l.getProjectId()))
                .orElseThrow(() -> new BizException(404, "地点不存在"));
        WorldGraphVO.LocationNode node = new WorldGraphVO.LocationNode(
                loc.getId(), loc.getName(), loc.getType(), loc.getIntro(),
                loc.getImportance(), loc.getSortOrder());
        // 在场角色（归一化 location_id 匹配）
        List<WorldGraphVO.CharacterNode> present = List.of();
        try {
            present = characterRepository.findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(
                            projectId, locationId, 0).stream()
                    .map(this::toCharacterNode)
                    .toList();
        } catch (Exception e) {
            log.warn("[图谱查询] 地点视角·在场角色失败（降级空列表）: project={} loc={} : {}",
                    projectId, locationId, e.getMessage());
        }
        // 近期事件（地点路由）
        List<WorldGraphVO.EventNode> events = List.of();
        try {
            events = toEventNodes(projectId,
                    eventRepository.findByProjectIdAndLocationIdOrderByIdDesc(projectId, locationId).stream()
                            .limit(VIEW_EVENT_LIMIT)
                            .toList());
        } catch (Exception e) {
            log.warn("[图谱查询] 地点视角·近期事件失败（降级空列表）: project={} loc={} : {}",
                    projectId, locationId, e.getMessage());
        }
        // 关联实体状态（当前有效 location 实体按名称匹配）
        List<WorldGraphVO.EntityNode> entities = entitiesOfLocation(projectId, loc);
        return new WorldGraphVO.LocationView(node, present, events, entities);
    }

    /**
     * 势力视角：势力 + 成员 + 组织间关系分。
     *
     * @param projectId 项目 ID
     *
     * @param groupId 组织 ID
     *
     * @return 势力聚合视图（组织不存在抛 404）
     */
    public WorldGraphVO.FactionView factionView(Long projectId, Long groupId) {
        ActorMesoGroup g = groupRepository.findById(groupId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .orElseThrow(() -> new BizException(404, "势力不存在"));
        WorldGraphVO.FactionNode node = toFactionNode(projectId, g);
        // 成员（membership → 角色名）
        List<WorldGraphVO.MemberNode> members = List.of();
        try {
            List<ActorMesoMembership> ms = membershipRepository.findByGroupIdOrderByIdAsc(groupId);
            List<Long> ids = ms.stream().map(ActorMesoMembership::getActorId).filter(id -> id != null).toList();
            Map<Long, String> names = new LinkedHashMap<>();
            if (!ids.isEmpty()) {
                for (ActorCharacter c : characterRepository.findByIdIn(ids)) {
                    names.put(c.getId(), c.getName());
                }
            }
            members = ms.stream().map(m -> new WorldGraphVO.MemberNode(
                    m.getActorId(), m.getActorRef(), names.get(m.getActorId()),
                    m.getRole(), m.getInfluence(), m.getStatus())).toList();
        } catch (Exception e) {
            log.warn("[图谱查询] 势力视角·成员失败（降级空列表）: project={} group={} : {}",
                    projectId, groupId, e.getMessage());
        }
        return new WorldGraphVO.FactionView(node, members, node.relations());
    }

    // ==================== 全局概览 ====================

    /**
     * 全局概览：时间 + 六维计数 + 宏观物资 + 最近事件 + 最近编年史。
     *
     * @param projectId 项目 ID
     *
     * @return 全局概览
     */
    public WorldGraphVO.Overview overview(Long projectId) {
        try {
            WorldGraphVO.TimeNode t = time(projectId);
            WorldGraphVO.Counts counts = new WorldGraphVO.Counts(
                    locationRepository.countByProjectId(projectId),
                    characterRepository.countByProjectIdAndDeleted(projectId, 0),
                    eventRepository.countByProjectId(projectId),
                    relationRepository.findByProjectId(projectId).size(),
                    groupRepository.countByProjectIdAndTypeNot(projectId, GROUP_TYPE_ADHOC));
            WorldGraphVO.MacroNode macro = macro(projectId);
            List<WorldGraphVO.EventNode> recentEvents = toEventNodes(projectId,
                    eventRepository.findTop20ByProjectIdOrderByIdDesc(projectId));
            List<WorldGraphVO.ChronicleNode> chronicles = chronicleRepository
                    .findTop10ByProjectIdOrderByGameDayDescIdDesc(projectId).stream()
                    .map(c -> new WorldGraphVO.ChronicleNode(
                            c.getId(), c.getGameDay(), c.getTitle(), c.getContent(),
                            c.getImportance(), c.getSeason(), c.getSource()))
                    .toList();
            return new WorldGraphVO.Overview(t, counts, macro, recentEvents, chronicles);
        } catch (Exception e) {
            log.warn("[图谱查询] 全局概览失败（降级 null）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    // ==================== 内部组装 ====================

    /** 角色实体 → 角色节点。 */
    private WorldGraphVO.CharacterNode toCharacterNode(ActorCharacter c) {
        return new WorldGraphVO.CharacterNode(
                c.getId(), c.npcRef(), c.getName(), c.getTitle(), c.getType(),
                c.getAffiliation(), c.getOccupation(), c.getLocation(), c.getLocationId(),
                c.getStage(), c.getImportance(), c.getIsProtagonist());
    }

    /** 事件列表 → 事件节点（参与角色名批量解析）。 */
    private List<WorldGraphVO.EventNode> toEventNodes(Long projectId, List<ActorEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        // 批量收集参与角色 id（character_ids 逗号分隔 ref 列表 + character_id 单值）
        Set<Long> ids = new LinkedHashSet<>();
        for (ActorEvent e : events) {
            if (e.getCharacterId() != null) {
                ids.add(e.getCharacterId());
            }
            if (e.getCharacterIds() != null) {
                for (String part : e.getCharacterIds().split(",")) {
                    Long id = NpcRefs.parseLenient(part);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            try {
                for (ActorCharacter c : characterRepository.findByIdIn(new ArrayList<>(ids))) {
                    names.put(c.getId(), c.getName());
                }
            } catch (Exception ex) {
                log.warn("[图谱查询] 参与角色名解析失败（降级仅 ref）: project={} : {}", projectId, ex.getMessage());
            }
        }
        List<WorldGraphVO.EventNode> out = new ArrayList<>();
        for (ActorEvent e : events) {
            List<String> charNames = new ArrayList<>();
            if (e.getCharacterId() != null && names.containsKey(e.getCharacterId())) {
                charNames.add(names.get(e.getCharacterId()));
            }
            if (e.getCharacterIds() != null) {
                for (String part : e.getCharacterIds().split(",")) {
                    Long id = NpcRefs.parseLenient(part);
                    String n = id == null ? null : names.get(id);
                    if (n != null && !charNames.contains(n)) {
                        charNames.add(n);
                    }
                }
            }
            out.add(new WorldGraphVO.EventNode(
                    e.getId(), e.getKind(), e.getTitle(), e.getContent(), e.getGameHour(),
                    e.getLocationId(), e.getLocationName(), e.getVisibility(), e.getImportance(),
                    charNames));
        }
        return out;
    }

    /** 组实体 → 势力节点（活跃成员数 + meta_json 组织间关系分）。 */
    private WorldGraphVO.FactionNode toFactionNode(Long projectId, ActorMesoGroup g) {
        long memberCount = 0;
        try {
            memberCount = membershipRepository.countByGroupIdAndStatus(g.getId(), MEMBER_STATUS_ACTIVE);
        } catch (Exception e) {
            log.warn("[图谱查询] 势力成员数查询失败（降级 0）: project={} group={} : {}",
                    projectId, g.getId(), e.getMessage());
        }
        return new WorldGraphVO.FactionNode(
                g.getId(), g.getName(), g.getType(), g.getStatus(), g.getInfluence(),
                g.getLeaderRef(), memberCount, relationsOf(g));
    }

    /** meta_json 组织间关系分解析（{"relations": {"势力名": -100..100}}；失败空 Map）。 */
    private Map<String, Integer> relationsOf(ActorMesoGroup g) {
        if (g.getMetaJson() == null || g.getMetaJson().isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(g.getMetaJson());
            JsonNode rel = root.path("relations");
            if (!rel.isObject()) {
                return Map.of();
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            rel.properties().forEach(en -> {
                if (en.getValue().isInt() || en.getValue().isNumber()) {
                    out.put(en.getKey(), en.getValue().asInt());
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 角色所属组织（membership npc:{id} → 组节点；无 membership 按 affiliation 文本匹配兜底）。 */
    private List<WorldGraphVO.FactionNode> factionsOfCharacter(Long projectId, Long characterId) {
        try {
            List<ActorMesoGroup> groups = new ArrayList<>();
            String ref = NpcRefs.of(characterId);
            if (ref != null) {
                List<ActorMesoMembership> ms = membershipRepository
                        .findByProjectIdAndActorRefOrderByIdAsc(projectId, ref);
                List<Long> gids = ms.stream().map(ActorMesoMembership::getGroupId)
                        .filter(id -> id != null).distinct().toList();
                if (!gids.isEmpty()) {
                    groups.addAll(groupRepository.findAllById(gids));
                }
            }
            if (groups.isEmpty()) {
                // 兜底：按 affiliation 文本匹配势力名（对齐感知「组织处境」口径）
                ActorCharacter ch = characterRepository.findByIdAndProjectId(characterId, projectId).orElse(null);
                if (ch != null && ch.getAffiliation() != null && !ch.getAffiliation().isBlank()) {
                    String aff = ch.getAffiliation().trim();
                    groups = groupRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                            .filter(g -> !GROUP_TYPE_ADHOC.equals(g.getType()))
                            .filter(g -> aff.equals(g.getName())
                                    || (g.getName() != null && aff.contains(g.getName())))
                            .toList();
                }
            }
            List<WorldGraphVO.FactionNode> out = new ArrayList<>();
            for (ActorMesoGroup g : groups) {
                out.add(toFactionNode(projectId, g));
            }
            return out;
        } catch (Exception e) {
            log.warn("[图谱查询] 角色所属组织失败（降级空列表）: project={} npc={} : {}",
                    projectId, characterId, e.getMessage());
            return List.of();
        }
    }

    /** 地点关联实体状态（当前有效 location 实体按名称匹配；未装配/失败空列表）。 */
    private List<WorldGraphVO.EntityNode> entitiesOfLocation(Long projectId, ActorWorldLocation loc) {
        if (entityRepository == null) {
            return List.of();
        }
        try {
            List<ActorWorldEntity> all = entityRepository.findByProjectIdOrderByIdDesc(projectId).stream()
                    .filter(e -> ENTITY_TYPE_LOCATION.equals(e.getEntityType())
                            && e.getValidToGameDay() == null)
                    .toList();
            List<WorldGraphVO.EntityNode> out = new ArrayList<>();
            for (ActorWorldEntity e : all) {
                boolean nameHit = loc.getName() != null
                        && (loc.getName().equals(e.getName())
                        || (e.getEntityKey() != null && e.getEntityKey().contains(loc.getName())));
                if (nameHit) {
                    out.add(new WorldGraphVO.EntityNode(
                            e.getId(), e.getEntityType(), e.getEntityKey(), e.getName(),
                            e.getCurrentState(), e.getRecentChange(), e.getValidFromGameDay()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[图谱查询] 地点关联实体状态失败（降级空列表）: project={} loc={} : {}",
                    projectId, loc.getId(), e.getMessage());
            return List.of();
        }
    }
}