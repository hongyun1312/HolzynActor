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

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 位置归一服务（统一位置模型，domain/world/service）。
 * <p>职责：把「自由文本位置」归一为 {@code actor_world_location} 地点表引用——修 位置/场景四处不一致
 * （角色 location / 居民 location / 对话 location / 地点表互不校验）：
 * ① 角色/居民/对话写入位置时按名称匹配地点表，命中则落 {@code location_id} + 规范名称快照；
 * ② 未命中保留自由文本兜底（不自动补入地点表，避免脏数据）；
 * ③ 名称匹配粒度：精确（trim 全等）优先 → 包含（名称含于串或串含于名称，取最长名更具体）→ 未命中。
 * 对齐系统框架 §5.5「位置 = actor_world_location（单一事实来源）；自由文本位置经感知组件归一」。</p>
 * <p>归一后的 location_id 是 事件按地点路由 / 感知「在场可见事件」的匹配基准
 * （城西事件只路由/感知到城西在场角色）。</p>
 * <p>纯逻辑（名称匹配）抽为静态方法便于单元测试。</p>
 * <p>；被 character/crowd/conversation/agent 域单向引用）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationNormalizationService {

    private final WorldLocationRepository locationRepository;

    /** 解析结果：命中的地点引用（未命中 locationId=null，名称=自由文本原样）。 */
    public record LocationRef(Long locationId, String name) {
        /** 是否命中地点表 */
        public boolean matched() {
            return locationId != null;
        }
    }

    /**
     * 按项目地点表归一自由文本位置（精确→包含→未命中兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param freeText 自由文本位置（可空/空=返回 null，无地点可归一）
     *
     * @return 归一结果：命中地点表返回 {locationId, 规范名}；未命中返回 {locationId=null, name=freeText}；
     * 入参空返回 null
     */
    public LocationRef normalize(Long projectId, String freeText) {
        if (projectId == null || freeText == null || freeText.isBlank()) {
            return null;
        }
        String text = freeText.trim();
        try {
            List<ActorWorldLocation> locations =
                    locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
            ActorWorldLocation hit = matchLocation(text, locations);
            if (hit != null) {
                return new LocationRef(hit.getId(), hit.getName());
            }
            return new LocationRef(null, text);
        } catch (Exception e) {
            // 归一失败不阻断写入（保留自由文本兜底）
            log.warn("[位置归一] 失败（保留自由文本）: project={} : {}", projectId, e.getMessage());
            return new LocationRef(null, text);
        }
    }

    /**
     * 归一后应持久化的位置名称（命中=地点表规范名；未命中=自由文本原样；入参空=null）。
     *
     * @param projectId 项目 ID
     *
     * @param freeText 自由文本位置（可空）
     *
     * @return 持久化名称
     */
    public String normalizedName(Long projectId, String freeText) {
        LocationRef ref = normalize(projectId, freeText);
        return ref == null ? null : ref.name();
    }

    /**
     * 归一后应持久化的地点 ID（命中=地点表 id；未命中=null；入参空=null）。
     *
     * @param projectId 项目 ID
     *
     * @param freeText 自由文本位置（可空）
     *
     * @return 地点 ID 或 null
     */
    public Long normalizedId(Long projectId, String freeText) {
        LocationRef ref = normalize(projectId, freeText);
        return ref == null ? null : ref.locationId();
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 名称匹配核心（静态）：精确（trim 全等）优先 → 包含（名称含于串或串含于名称）→ 未命中。
     * <p>包含匹配取「最长名称」命中（更具体的子地点优先，如「城东市场」优于「城东」）。</p>
     *
     * @param freeText 自由文本位置（非空）
     *
     * @param locations 项目地点表（可空/空）
     *
     * @return 命中的地点实体；未命中返回 null
     */
    static ActorWorldLocation matchLocation(String freeText, List<ActorWorldLocation> locations) {
        if (freeText == null || freeText.isBlank() || locations == null || locations.isEmpty()) {
            return null;
        }
        String text = freeText.trim();
        // ① 精确匹配（trim 全等）
        for (ActorWorldLocation loc : locations) {
            if (loc != null && loc.getName() != null && loc.getName().trim().equals(text)) {
                return loc;
            }
        }
        // ② 包含匹配：名称含于串 或 串含于名称（取最长名称更具体）
        ActorWorldLocation best = null;
        for (ActorWorldLocation loc : locations) {
            if (loc == null || loc.getName() == null || loc.getName().isBlank()) {
                continue;
            }
            String name = loc.getName().trim();
            if (name.contains(text) || text.contains(name)) {
                if (best == null || name.length() > best.getName().length()) {
                    best = loc;
                }
            }
        }
        return best;
    }
}
