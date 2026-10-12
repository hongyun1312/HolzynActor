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

package com.holzyn.actor.domain.agent.event.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventQuery;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSseHub;
import com.holzyn.actor.domain.agent.event.WorldEventViews;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 世界事件总线 API 控制器（B1，domain/agent/event）。
 * <p>职责：暴露项目级世界事件流的两种只读能力——<ul>
 * <li><b>SSE</b>{@code GET /api/projects/{id}/world-events/stream}：实时世界事件流
 * （事件名 {@code world-event}，载荷含 eventId/kind/title/locationName/characterIds/gameHour/gameTimeText）；</li>
 * <li><b>查询</b>{@code GET /api/projects/{id}/world-events}：按类别/来源/地点/角色 ref（可见性过滤）/
 * 游戏时间窗查询最近事件（时间线/前端/感知共用，修）。</li></ul></p>
 * <p>依赖方向（R1）：agent 域单向依赖既有域（项目仓库做归属校验、世界时钟解析时间文本）。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-events")
@RequiredArgsConstructor
public class WorldEventController {

    private final WorldEventBus worldEventBus;
    private final WorldEventSseHub sseHub;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;
    private final WorldClockService worldClockService;

    /**
     * 项目世界事件流（SSE，实时推送）。
     *
     * @param projectId 项目 ID
     *
     * @return SSE 发射器（connected + world-event 事件）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        return sseHub.subscribe(projectId);
    }

    /**
     * 项目世界事件查询（最近事件，可见性过滤）。
     *
     * @param projectId 项目 ID
     *
     * @param kind 类别过滤（action/crowd/world/evolution/event/manual/system，可空）
     *
     * @param source 来源过滤（可空）
     *
     * @param locationId 地点 ID 过滤（可空）
     *
     * @param characterRef 角色 ref 过滤（special:{id}/crowd:{id}，带可见性过滤，可空）
     *
     * @param fromGameHour 起始游戏小时（可空）
     *
     * @param toGameHour 结束游戏小时（可空）
     *
     * @param limit 条数上限（默认 100，上限 500）
     *
     * @return 事件列表（id 倒序）
     */
    @GetMapping
    public R<List<Map<String, Object>>> query(@PathVariable("projectId") Long projectId,
                                              @RequestParam(name = "kind", required = false) String kind,
                                              @RequestParam(name = "source", required = false) String source,
                                              @RequestParam(name = "locationId", required = false) Long locationId,
                                              @RequestParam(name = "characterRef", required = false) String characterRef,
                                              @RequestParam(name = "fromGameHour", required = false) Long fromGameHour,
                                              @RequestParam(name = "toGameHour", required = false) Long toGameHour,
                                              @RequestParam(name = "limit", defaultValue = "100") int limit) {
        requireProject(projectId);
        EventQuery query = new EventQuery(kind, source, locationId, characterRef, fromGameHour, toGameHour, limit);
        List<Map<String, Object>> rows = worldEventBus.query(projectId, query).stream()
                .map(e -> WorldEventViews.toMap(e, worldClockService))
                .toList();
        return R.ok(rows);
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, currentUserProvider.currentUserId(), 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }
}
