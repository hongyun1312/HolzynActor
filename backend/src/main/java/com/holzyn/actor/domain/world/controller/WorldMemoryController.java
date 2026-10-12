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

import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import com.holzyn.actor.domain.world.vo.WorldChronicleVO;
import com.holzyn.actor.domain.world.vo.WorldEntityVO;
import com.holzyn.actor.domain.world.vo.WorldMemoryOverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目记忆控制器。
 * <p>职责：项目记忆四层（编年史 L2 / 实体状态 L3 / 概览 / 手动补充）的查询与管理接口
 * </p>
 * <ul>
 * <li>GET world-memory/chronicle —— 编年史查询（分页/按日区间/按实体/按重要度）；</li>
 * <li>GET world-memory/chronicle/{id} —— 编年史条目详情；</li>
 * <li>GET world-memory/entities —— 实体当前状态（type 过滤 + asOfGameDay 按时间点查询）；</li>
 * <li>GET world-memory/entities/{entityKey}/history —— 单实体状态版本链（失效历史）；</li>
 * <li>POST world-memory/chronicle —— 手动补充编年史条目；</li>
 * <li>POST world-memory/entities —— 手动更新实体状态（触发失效更新）；</li>
 * <li>GET world-memory/overview —— 项目记忆概览（编年史条数/实体数/最新条目/近期变化）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-memory")
@RequiredArgsConstructor
public class WorldMemoryController {

    private final WorldMemoryService worldMemoryService;

    /**
     * 编年史查询（分页；fromDay/toDay 按游戏日区间、entityKey 按关联实体、importance 按重要度下限）。
     */
    @GetMapping("/chronicle")
    public R<PageResult<WorldChronicleVO>> chronicle(@PathVariable("projectId") Long projectId,
                                                     @RequestParam(value = "fromDay", required = false) Long fromDay,
                                                     @RequestParam(value = "toDay", required = false) Long toDay,
                                                     @RequestParam(value = "entityKey", required = false) String entityKey,
                                                     @RequestParam(value = "importance", required = false) Integer importance,
                                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                                     @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(worldMemoryService.chroniclePage(projectId, fromDay, toDay, entityKey, importance, page, size));
    }

    /**
     * 编年史条目详情（含关联事件/实体）。
     */
    @GetMapping("/chronicle/{chronicleId}")
    public R<WorldChronicleVO> chronicleDetail(@PathVariable("projectId") Long projectId,
                                               @PathVariable("chronicleId") Long chronicleId) {
        return R.ok(worldMemoryService.chronicleDetail(projectId, chronicleId));
    }

    /**
     * 实体当前状态（type=location/person/faction 过滤；asOfGameDay 按时间点查询历史态）。
     */
    @GetMapping("/entities")
    public R<PageResult<WorldEntityVO>> entities(@PathVariable("projectId") Long projectId,
                                                 @RequestParam(value = "type", required = false) String type,
                                                 @RequestParam(value = "asOfGameDay", required = false) Long asOfGameDay,
                                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(worldMemoryService.entityPage(projectId, type, asOfGameDay, page, size));
    }

    /**
     * 单实体状态版本链（失效历史，新→旧）。
     */
    @GetMapping("/entities/{entityKey}/history")
    public R<List<WorldEntityVO>> entityHistory(@PathVariable("projectId") Long projectId,
                                                @PathVariable("entityKey") String entityKey) {
        return R.ok(worldMemoryService.entityHistory(projectId, entityKey));
    }

    /**
     * 手动补充编年史条目（用户/管理员）。
     */
    @PostMapping("/chronicle")
    public R<WorldChronicleVO> addChronicle(@PathVariable("projectId") Long projectId,
                                            @RequestBody Map<String, Object> body) {
        return R.ok(worldMemoryService.manualAddChronicle(projectId,
                asLong(body.get("gameDay")),
                asString(body.get("title")),
                asString(body.get("content")),
                asInt(body.get("importance")),
                asString(body.get("involvedEntities")),
                asString(body.get("gameHour"))));
    }

    /**
     * 手动更新实体状态（触发 Zep 式失效更新）。
     * <p>Body：{entityKey(必填, 带类型前缀), currentState(必填), recentChange, entityType, name, gameDay}。</p>
     */
    @PostMapping("/entities")
    public R<WorldEntityVO> updateEntity(@PathVariable("projectId") Long projectId,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(worldMemoryService.manualUpdateEntity(projectId,
                asString(body.get("entityKey")),
                asString(body.get("entityType")),
                asString(body.get("name")),
                asString(body.get("currentState")),
                asString(body.get("recentChange")),
                asLong(body.get("gameDay"))));
    }

    /**
     * 项目记忆概览（编年史条数/实体数/归档数/最新条目/近期实体变化）。
     */
    @GetMapping("/overview")
    public R<WorldMemoryOverviewVO> overview(@PathVariable("projectId") Long projectId) {
        return R.ok(worldMemoryService.overview(projectId));
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
