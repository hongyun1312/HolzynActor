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

package com.holzyn.actor.domain.world.fact.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import com.holzyn.actor.domain.world.fact.service.WorldFactLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界事实台账只读 API（事实台账 Tab）。
 *
 * <p>只读，不新增写入口：事实写仍只走 {@code WorldFactService}。字段含 authority（brain/engine）、
 * 版本、有效期与来源事件，供前端做列表 + 版本 diff + 权威标记。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}/world-facts")
@RequiredArgsConstructor
public class WorldFactController {

    private final WorldFactLedgerService ledgerService;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 项目事实台账（新→旧）。
     *
     * @param projectId 项目 ID
     *
     * @param status 状态（默认 active；传空=全部）
     *
     * @param factType 类型（可空）
     *
     * @param keyword 关键词（可空）
     *
     * @param limit 条数（默认 100，上限 500）
     *
     * @return 事实列表
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(@PathVariable("projectId") Long projectId,
                                             @RequestParam(name = "status", defaultValue = "active") String status,
                                             @RequestParam(name = "factType", required = false) String factType,
                                             @RequestParam(name = "keyword", required = false) String keyword,
                                             @RequestParam(name = "limit", defaultValue = "100") int limit) {
        requireProject(projectId);
        List<Map<String, Object>> rows = ledgerService.list(projectId, status, factType, keyword, limit)
                .stream()
                .map(WorldFactController::factView)
                .toList();
        return R.ok(rows);
    }

    /**
     * 某事实版本链（新→旧），供台账版本 diff 抽屉。
     *
     * @param projectId 项目 ID
     *
     * @param factId 事实 ID
     *
     * @return 版本日志列表
     */
    @GetMapping("/{factId}/versions")
    public R<List<Map<String, Object>>> versions(@PathVariable("projectId") Long projectId,
                                                 @PathVariable("factId") Long factId) {
        requireProject(projectId);
        return R.ok(ledgerService.versions(projectId, factId).stream()
                .map(WorldFactController::logView)
                .toList());
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     */
    private void requireProject(Long projectId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, currentUserProvider.currentUserId(), 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    private static Map<String, Object> factView(ActorWorldFact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("factKey", f.getFactKey());
        m.put("factType", f.getFactType());
        m.put("name", f.getName());
        m.put("stateText", f.getStateText());
        m.put("attributesJson", f.getAttributesJson());
        m.put("locationId", f.getLocationId());
        m.put("locationName", f.getLocationName());
        m.put("visibility", f.getVisibility());
        m.put("authority", f.getAuthority());
        m.put("version", f.getVersion());
        m.put("validFromGameSecond", f.getValidFromGameSecond());
        m.put("validToGameSecond", f.getValidToGameSecond());
        m.put("status", f.getStatus());
        m.put("sourceEventId", f.getSourceEventId());
        m.put("updatedAt", f.getUpdatedAt() == null ? null : f.getUpdatedAt().toString());
        return m;
    }

    private static Map<String, Object> logView(ActorWorldFactLog row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("factId", row.getFactId());
        m.put("factKey", row.getFactKey());
        m.put("version", row.getVersion());
        m.put("op", row.getOp());
        m.put("beforeJson", row.getBeforeJson());
        m.put("afterJson", row.getAfterJson());
        m.put("deltaJson", row.getDeltaJson());
        m.put("gameSecond", row.getGameSecond());
        m.put("actorRef", row.getActorRef());
        m.put("writer", row.getWriter());
        m.put("eventId", row.getEventId());
        m.put("idempotencyKey", row.getIdempotencyKey());
        m.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return m;
    }
}
