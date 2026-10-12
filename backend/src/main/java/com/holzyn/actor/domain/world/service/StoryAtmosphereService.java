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

import com.holzyn.actor.domain.world.entity.ActorStoryPlan;
import com.holzyn.actor.domain.world.repository.ActorStoryPlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界氛围片段读取服务。
 * <p>职责：把 story-plan 工作流产出的<b>世界氛围片段</b>（actor_story_plan.atmosphere_segment——
 * 过滤元叙事概念后的世界气质文本）提供给三处运行注入点：</p>
 * <ul>
 * <li><b>环境描述</b>（EnvironmentContextService 环境润色）；</li>
 * <li><b>宏观演化叙事</b>（MacroScheduler AI 叙事）；</li>
 * <li><b>世界事件润色</b>（WorldEventService.polishEventByAi）。</li>
 * </ul>
 * <p>设计：角色可感知「世界的味道/氛围/张力底色」（提升世界真实性），但<span>绝不感知</span>
 * 「读者承诺/爆点语法/叙事人格/创作边界」等元概念——氛围片段已由 {@code WorldGenStoryPlanService}
 * 过滤，本服务只负责读取。无档案/无氛围 → 返回空串（注入方零阻断降级）。</p>
 * <p>缓存：按项目短 TTL（默认 10 分钟）缓存最新片段，避免注入点频繁查库；失效=项目有新版本。</p>
 */
@Slf4j
@Service
public class StoryAtmosphereService {

    /** 氛围缓存 TTL（毫秒，默认 10 分钟） */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final ActorStoryPlanRepository storyPlanRepository;

    /** 缓存（projectId → [加载时刻, 氛围片段]；TTL 过期重查） */
    private final Map<Long, long[]> cacheTs = new ConcurrentHashMap<>();
    private final Map<Long, String> cacheValue = new ConcurrentHashMap<>();

    public StoryAtmosphereService(ActorStoryPlanRepository storyPlanRepository) {
        this.storyPlanRepository = storyPlanRepository;
    }

    /**
     * 项目最新世界氛围片段（注入点统一入口；无档案/无片段返回空串，零阻断）。
     *
     * @param projectId 项目 ID（null → 空串）
     *
     * @return 氛围片段文本（可空串）
     */
    public String atmosphereOf(Long projectId) {
        if (projectId == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        long[] ts = cacheTs.get(projectId);
        if (ts != null && now - ts[0] < CACHE_TTL_MS) {
            return cacheValue.getOrDefault(projectId, "");
        }
        String value = loadAtmosphere(projectId);
        cacheTs.put(projectId, new long[]{now});
        cacheValue.put(projectId, value);
        return value;
    }

    /**
     * 使项目氛围缓存失效（新版本 story-plan 落库后调用，注入点下次读取即取到新氛围）。
     *
     * @param projectId 项目 ID（null 忽略）
     */
    public void invalidate(Long projectId) {
        if (projectId != null) {
            cacheTs.remove(projectId);
            cacheValue.remove(projectId);
        }
    }

    /**
     * 加载项目最新氛围片段（直查库；无记录/无片段返回空串）。
     */
    private String loadAtmosphere(Long projectId) {
        try {
            ActorStoryPlan latest = storyPlanRepository
                    .findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
            if (latest == null || latest.getAtmosphereSegment() == null) {
                return "";
            }
            return latest.getAtmosphereSegment().trim();
        } catch (Exception e) {
            log.warn("[世界氛围] 读取失败（零阻断）：project={}, error={}", projectId, e.getMessage());
            return "";
        }
    }
}
