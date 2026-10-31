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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 时间线控制器。
 * <p>职责：提供项目「编年史」统一时间线查询（事件/行动/记忆聚合）、手动新增事件、
 * AI 从世界观识别生成事件。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TimelineController {

    /** 时间线服务 */
    private final TimelineService timelineService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 项目统一时间线（事件/行动/记忆聚合，新→旧，分页）。
     *
     * <p><b>分页契约</b>：返回 {@code {nodes, total, limit, offset, hasMore}}——{@code total}
     * 是过滤后的总条数；{@code hasMore=false} 表示到底了。不传 {@code limit} 时后端按 100 条返回，
     * 传 0/负数同样按 100；上限 500（前端续加载按批取，不要一次要全量）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param types 类型过滤（event/action/memory，逗号分隔，空=全部）
     *
     * @param characterId 角色过滤（可空）
     *
     * @param startDate 起始日期（可空，yyyy-MM-dd）
     *
     * @param endDate 结束日期（可空）
     *
     * @param limit 本批条数（可空=100，上限 500）
     *
     * @param offset 起始偏移（可空=0）
     *
     * @return 分页结果（nodes/total/limit/offset/hasMore）
     */
    @GetMapping("/worlds/{projectId}/timeline")
    public R<Map<String, Object>> timeline(
            @PathVariable("projectId") Long projectId,
            @RequestParam(name = "types", required = false) String types,
            @RequestParam(name = "characterId", required = false) Long characterId,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        Set<String> typeSet = types == null || types.isBlank() ? null
                : java.util.Arrays.stream(types.split(",")).map(String::trim).collect(java.util.stream.Collectors.toSet());
        return R.ok(timelineService.aggregatePage(projectId, typeSet, characterId, startDate, endDate, limit, offset));
    }

    /**
     * 手动新增事件（持久化到 actor_event，source=manual）。
     *
     * @param projectId 项目 ID
     *
     * @param body 入参：{title?, content 必填, kind?, characterId?}
     *
     * @return 新增的事件视图
     */
    @PostMapping("/worlds/{projectId}/events")
    public R<Map<String, Object>> createEvent(@PathVariable("projectId") Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(timelineService.createEvent(projectId, body));
    }

    /**
     * AI 从世界观/项目概况识别生成事件（source=ai）。
     *
     * @param projectId 项目 ID
     *
     * @return 生成的事件视图
     */
    @PostMapping("/worlds/{projectId}/events/ai-generate")
    public R<Map<String, Object>> aiGenerate(@PathVariable("projectId") Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(timelineService.aiGenerateEvent(userId, projectId));
    }
}
