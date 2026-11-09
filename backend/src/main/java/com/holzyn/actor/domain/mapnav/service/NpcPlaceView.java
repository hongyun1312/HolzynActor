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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPositionRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 角色所在「地点」的唯一读取口：权威坐标 → 地图要素 → 归属住户。
 *
 * <p><b>为什么单独抽一个读口</b>：修复「AI 说去哪、人不动」之后，暴露出下一层问题——
 * 决定「能做什么」的可发生空间（{@code actor_growth_space}）是一次生成、长期复用的，而角色会走动。
 * 角色走到别人家门口时，空间里仍是铁匠铺的「补墙、取工具」，于是候选动作跟身体脱节。
 * 要让空间跟着身体走，必须先有一个「他现在到底在谁的什么地方」的统一读口，而不是各处各查一遍。</p>
 *
 * <p><b>只读</b>：不写任何表。写坐标仍只走 {@code NpcPositionService}（单一写入口）。</p>
 */
@Service
public class NpcPlaceView {

    private static final Logger log = LoggerFactory.getLogger(NpcPlaceView.class);

    @Autowired(required = false)
    private ActorNpcPositionRepository npcPositionRepository;

    @Autowired(required = false)
    private ActorNpcPathRepository npcPathRepository;

    @Autowired(required = false)
    private ActorWorldMapRepository worldMapRepository;

    @Autowired(required = false)
    private ActorMapFeatureRepository mapFeatureRepository;

    @Autowired(required = false)
    private ActorHomeMemberRepository homeMemberRepository;

    /**
     * 地点快照。
     *
     * @param featureCode 要素编码（无则 null）
     *
     * @param name        地点名（「阿珍家」「镇中心广场」；未知回退「这里」）
     *
     * @param category    要素分类（民居/商铺/工坊/公共/自然/生产/水工…；可空）
     *
     * @param indoor      权威行是否标为室内
     *
     * @param hasHome     是否登记了归属住户
     *
     * @param mine        归属住户里是否包含该角色
     */
    public record Place(String featureCode, String name, String category, boolean indoor,
                        boolean hasHome, boolean mine) {

        /** 地点是否可辨认（有名字才算）。 */
        public boolean known() {
            return name != null && !name.isBlank();
        }

        /** 自己的住处。 */
        public boolean atOwnHome() {
            return hasHome && mine;
        }

        /** 别人的住处。 */
        public boolean atOthersHome() {
            return hasHome && !mine;
        }

        /** 未知地点（坐标缺失或要素查不到）。 */
        public static Place unknown() {
            return new Place(null, null, "", false, false, false);
        }
    }

    /**
     * 读角色当前地点（权威坐标）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef    角色引用（npc:{id}）
     *
     * @return 地点快照；坐标或地图缺失返回 {@link Place#unknown()}（不返回 null，调用方不必判空）
     */
    public Place at(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || npcPositionRepository == null) {
            return Place.unknown();
        }
        try {
            ActorNpcPosition pos = npcPositionRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            if (pos == null) {
                return Place.unknown();
            }
            ActorMapFeature f = featureOf(projectId, pos.getFeatureCode());
            String name = f == null ? null : f.getName();
            if (name != null) {
                name = name.trim();
            }
            if (name == null || name.isBlank()) {
                // 要素查不到：名字留空（known()=false），由消费侧退回「未知地点」口径，不编造地名
                name = null;
            }
            String category = f == null || f.getCategory() == null ? "" : f.getCategory().trim();
            boolean hasHome = f != null && f.getOwnerHomeRef() != null && !f.getOwnerHomeRef().isBlank();
            boolean mine = hasHome && isMember(npcRef, f);
            return new Place(pos.getFeatureCode(), name.trim(), category,
                    pos.getIndoor() != null && pos.getIndoor() == 1, hasHome, mine);
        } catch (Exception e) {
            log.debug("[地点视图] 读取失败（按未知地点处理）: ref={} 原因={}", npcRef, e.getMessage());
            return Place.unknown();
        }
    }

    /**
     * 角色是否在「空间生成之后」到过（或正在去）别处——可发生空间是否已经过时。
     *
     * <p><b>判据</b>：取该角色最近一条 {@code status=arrived} 的路径行，若其 {@code arrive_game_second}
     * 晚于空间生成时刻，说明空间是「上一个地点」里生成的，候选动作属于旧地点。</p>
     *
     * <p>只看 arrived：walking 的行表示还在路上，此时他既不在旧地点也不在新地点，
     * 按旧空间决策不算错得离谱，等到达后再重算，避免每走一步就重算一次。</p>
     *
     * @param projectId   项目 ID
     *
     * @param npcRef      角色引用
     *
     * @param spaceSecond 空间生成时刻（游戏秒）
     *
     * @return true=空间已过时（位置变化）
     */
    public boolean arrivedAfter(Long projectId, String npcRef, Long spaceSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank() || npcPathRepository == null) {
            return false;
        }
        if (spaceSecond == null || spaceSecond <= 0) {
            return false;
        }
        try {
            for (ActorNpcPath p : npcPathRepository
                    .findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, npcRef, "arrived")) {
                if (p == null || p.getArriveGameSecond() == null) {
                    continue;
                }
                // 只看最近一次已完成的到达：它晚于空间生成时刻 = 空间属于旧地点
                return p.getArriveGameSecond() > spaceSecond;
            }
        } catch (Exception e) {
            log.debug("[地点视图] 路径读取失败（按未移动处理）: ref={} 原因={}", npcRef, e.getMessage());
        }
        return false;
    }

    /** 按要素编码取要素（最新地图）。 */
    private ActorMapFeature featureOf(Long projectId, String featureCode) {
        if (mapFeatureRepository == null || worldMapRepository == null
                || featureCode == null || featureCode.isBlank()) {
            return null;
        }
        ActorWorldMap map = worldMapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        if (map == null) {
            return null;
        }
        String want = featureCode.trim();
        for (ActorMapFeature f : mapFeatureRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
            if (f.getCode() != null && want.equalsIgnoreCase(f.getCode().trim())) {
                return f;
            }
        }
        return null;
    }

    /** 住户表判定：该要素的归属住户里有没有这个角色。 */
    private boolean isMember(String npcRef, ActorMapFeature f) {
        if (homeMemberRepository == null || f == null || f.getOwnerHomeRef() == null) {
            return false;
        }
        try {
            Long homeId = Long.valueOf(f.getOwnerHomeRef().trim());
            for (ActorHomeMember m : homeMemberRepository.findByHomeIdOrderByIdAsc(homeId)) {
                if (npcRef.equals(m.getCharacterRef())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[地点视图] 住户判定失败（按非自家处理）: 原因={}", e.getMessage());
        }
        return false;
    }

    /** 测试/调试用：当前是否已装配（缺依赖时不抛空指针，直接降级）。 */
    public boolean wired() {
        return npcPositionRepository != null && mapFeatureRepository != null && worldMapRepository != null;
    }

    /** 供调试接口读取「最近一次到达」而不必重复查库（只读）。 */
    public Optional<ActorNpcPath> latestArrival(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcPathRepository == null) {
            return Optional.empty();
        }
        try {
            var rows = npcPathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(projectId, npcRef, "arrived");
            return rows == null || rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
