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

import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色坐标的<b>唯一写入口</b>（表 {@code actor_npc_position}）。
 *
 * <p><b>为什么必须只有一个写入口</b>：位置一旦有第二个写入方，就会出现「两个模块各自算了一份、
 * 差 3 米」的经典事故——AI 说他在门口、图上他还在街角，而且永远说不清谁错。约定是：
 * 想改位置只能调这里，其他模块（感知、决策、编曲、查询）一律只读。</p>
 *
 * <p><b>为什么在内存里留一份</b>：位置每 5 游戏分钟被 tick 刷一遍、又被渲染/感知高频读，
 * 每次都打 DB 既慢又没必要（一个项目几十行）。内存是权威读视图，DB 是持久化副本；
 * 进程重启时从 DB 读回。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcPositionService {

    private final ActorNpcPositionRepository repository;

    /** 项目 → (角色 → 坐标)。 */
    private final Map<Long, Map<String, ActorNpcPosition>> cache = new ConcurrentHashMap<>();

    /**
     * 列出一张图的全部角色坐标（只读）。
     *
     * @param projectId 项目
     * @return 坐标行（按角色引用排序）
     */
    public List<ActorNpcPosition> list(Long projectId) {
        Map<String, ActorNpcPosition> map = cache.computeIfAbsent(projectId, this::load);
        List<ActorNpcPosition> out = new ArrayList<>(map.values());
        out.sort((a, b) -> String.valueOf(a.getNpcRef()).compareTo(String.valueOf(b.getNpcRef())));
        return out;
    }

    /**
     * 单角色坐标。
     *
     * @param projectId 项目
     * @param npcRef    角色引用
     * @return 坐标；未落位返回 empty
     */
    public Optional<ActorNpcPosition> find(Long projectId, String npcRef) {
        if (npcRef == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.computeIfAbsent(projectId, this::load).get(npcRef));
    }

    /**
     * 写入/更新一个角色坐标（唯一写入口）。
     *
     * @param projectId   项目
     * @param mapId       地图
     * @param npcRef      角色引用
     * @param npcName     角色名（可空，沿用旧值）
     * @param x           X（米）
     * @param z           Z（米）
     * @param heading     朝向（度）
     * @param indoor      是否室内
     * @param activity    活动
     * @param featureCode 所在要素 code
     * @param status      状态（static/walking/working）
     * @param gameSecond  对应游戏秒
     * @return 写入后的行
     */
    public ActorNpcPosition upsert(Long projectId, Long mapId, String npcRef, String npcName,
                                   double x, double z, double heading, boolean indoor,
                                   String activity, String featureCode, String status,
                                   long gameSecond) {
        Map<String, ActorNpcPosition> map = cache.computeIfAbsent(projectId, this::load);
        ActorNpcPosition row = map.get(npcRef);
        if (row == null) {
            row = new ActorNpcPosition();
            row.setProjectId(projectId);
            row.setMapId(mapId);
            row.setNpcRef(npcRef);
            map.put(npcRef, row);
        }
        row.setNpcName(npcName == null || npcName.isBlank() ? row.getNpcName() : npcName);
        row.setMapId(mapId);
        row.setX(x);
        row.setZ(z);
        row.setHeading(heading);
        row.setIndoor(indoor ? 1 : 0);
        row.setActivity(activity);
        row.setFeatureCode(featureCode);
        row.setStatus(status);
        row.setGameSecond(gameSecond);
        row.setUpdatedAt(LocalDateTime.now());
        try {
            return repository.save(row);
        } catch (Exception e) {
            log.warn("[mapnav] 写角色坐标失败（内存视图仍生效）：ref={} : {}", npcRef, e.getMessage());
            return row;
        }
    }

    /**
     * 批量写（tick 一帧的变更一次性落库）。
     *
     * @param rows 行
     */
    public void saveAll(List<ActorNpcPosition> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(rows);
        } catch (Exception e) {
            log.warn("[mapnav] 批量写角色坐标失败（内存视图仍生效）：{}", e.getMessage());
        }
    }

    /**
     * 清掉某张图的坐标与内存缓存（整图重生成时）。
     *
     * @param projectId 项目
     * @param mapId     地图
     */
    public void clear(Long projectId, Long mapId) {
        cache.remove(projectId);
        try {
            repository.deleteByMapId(mapId);
        } catch (Exception e) {
            log.warn("[mapnav] 清理角色坐标失败：{}", e.getMessage());
        }
    }

    /** 失效内存视图（外部改了库，如导入世界后）。 */
    public void invalidate(Long projectId) {
        if (projectId == null) {
            cache.clear();
        } else {
            cache.remove(projectId);
        }
    }

    /** 从库读一份进内存。 */
    private Map<String, ActorNpcPosition> load(Long projectId) {
        Map<String, ActorNpcPosition> map = new ConcurrentHashMap<>();
        try {
            for (ActorNpcPosition row : repository.findByProjectId(projectId)) {
                if (row.getNpcRef() != null) {
                    map.put(row.getNpcRef(), row);
                }
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读角色坐标失败（首启正常）：{}", e.getMessage());
        }
        return map;
    }
}
