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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.service.NpcPositionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话位置同步。
 * <p>会话地点与参与者坐标不一致时，把参与者对齐到会话地点中心。写坐标只走
 * {@link NpcPositionService}（AGENTS §8.1 单一写入口），本服务不直接写仓储。</p>
 * <p><b>失败不阻断对话</b>：地点中心缺失（地图未生成）/写库失败只 WARN，事件照常落。</p>
 */
@Slf4j
@Service
public class DialogPositionSyncService {

    @Autowired(required = false)
    private WorldLocationRepository locationRepository;

    @Autowired(required = false)
    private NpcPositionService positionService;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 把参与者同步到会话地点。
     *
     * @param projectId    项目 ID
     * @param locationId   地点 ID（可空）
     * @param locationName 地点名（可空；id 为空时按名字匹配）
     * @param refs         参与者引用（npc:{id}）
     * @param gameSecond   当前游戏秒
     *
     * @return 实际写入人数（0=无可同步地点/服务未就绪）
     */
    public int sync(Long projectId, Long locationId, String locationName, List<String> refs, long gameSecond) {
        if (projectId == null || refs == null || refs.isEmpty() || positionService == null
                || locationRepository == null) {
            return 0;
        }
        ActorWorldLocation location = resolve(projectId, locationId, locationName);
        if (location == null) {
            log.debug("[对话位置] 地点未解析，跳过同步: project={} locId={} locName={}",
                    projectId, locationId, locationName);
            return 0;
        }
        if (location.getCenterX() == null || location.getCenterZ() == null) {
            // 地点存在但中心坐标还没回填（旧世界/地图未生成）：保留参与者原坐标，绝不写 0,0/0.5,0.5
            log.warn("[对话位置] 地点「{}」缺少中心坐标，保留参与者原坐标: project={}",
                    location.getName(), projectId);
            return 0;
        }
        double x = location.getCenterX();
        double z = location.getCenterZ();
        int count = 0;
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            try {
                ActorNpcPosition old = positionService.find(projectId, ref).orElse(null);
                long mapId = old == null || old.getMapId() == null ? 0L : old.getMapId();
                double heading = old == null || old.getHeading() == null ? 0d : old.getHeading();
                boolean indoor = old != null && Integer.valueOf(1).equals(old.getIndoor());
                String activity = old == null || old.getActivity() == null ? "交谈" : old.getActivity();
                String featureCode = old == null ? null : old.getFeatureCode();
                positionService.upsert(projectId, mapId, ref, nameOf(ref), x, z, heading, indoor,
                        activity, featureCode, "static", gameSecond);
                count++;
            } catch (Exception e) {
                log.warn("[对话位置] 参与者位置同步失败（跳过该人）: project={} ref={} : {}",
                        projectId, ref, e.getMessage());
            }
        }
        if (count > 0) {
            log.info("[对话位置] 已对齐 {} 人到地点「{}」: project={}", count, location.getName(), projectId);
        }
        return count;
    }

    /** 地点解析：id 优先，缺失按 name 精确匹配。 */
    private ActorWorldLocation resolve(Long projectId, Long locationId, String locationName) {
        try {
            if (locationId != null) {
                ActorWorldLocation byId = locationRepository.findById(locationId).orElse(null);
                if (byId != null && projectId.equals(byId.getProjectId())) {
                    return byId;
                }
            }
            if (locationName == null || locationName.isBlank()) {
                return null;
            }
            String want = locationName.trim();
            return locationRepository.findByProjectId(projectId).stream()
                    .filter(l -> l.getName() != null && l.getName().trim().equals(want))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("[对话位置] 地点解析失败（跳过同步）: {}", e.getMessage());
            return null;
        }
    }

    /** 角色名（解析失败返回 null，沿用旧值）。 */
    private String nameOf(String ref) {
        if (characterRepository == null) {
            return null;
        }
        Long id = NpcRefs.idOf(ref);
        if (id == null) {
            return null;
        }
        return characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
    }
}
