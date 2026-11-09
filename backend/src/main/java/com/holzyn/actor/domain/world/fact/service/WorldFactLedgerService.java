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

package com.holzyn.actor.domain.world.fact.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactLogRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 世界事实台账只读查询（事实台账 Tab）。
 *
 * <p>只做浏览：列表按项目/状态/类型/关键词过滤，版本链读 {@code actor_world_fact_log}。
 * 不写库、不触发感知、零 AI；归属校验在 controller（当前用户只能看自己的世界）。</p>
 */
@Service
@RequiredArgsConstructor
public class WorldFactLedgerService {

    /** 台账单次最多返回条数。 */
    public static final int MAX_LIMIT = 500;

    private final ActorWorldFactRepository factRepository;
    private final ActorWorldFactLogRepository logRepository;

    /**
     * 列出项目事实（新→旧）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param status 状态过滤（active/expired/void；空=全部）
     *
     * @param factType 类型过滤（空=全部）
     *
     * @param keyword 关键词（factKey/name/stateText 大小写不敏感子串）
     *
     * @param limit 条数上限（1..500）
     *
     * @return 事实列表
     */
    public List<ActorWorldFact> list(Long projectId, String status, String factType, String keyword, int limit) {
        if (projectId == null) {
            return List.of();
        }
        String statusFilter = blank(status) ? null : status.trim();
        String typeFilter = blank(factType) ? null : factType.trim();
        String kw = blank(keyword) ? null : keyword.trim().toLowerCase(Locale.ROOT);
        int cap = Math.max(1, Math.min(MAX_LIMIT, limit));
        return factRepository.findByProjectIdOrderByIdDesc(projectId).stream()
                .filter(f -> statusFilter == null || statusFilter.equalsIgnoreCase(f.getStatus()))
                .filter(f -> typeFilter == null || typeFilter.equalsIgnoreCase(f.getFactType()))
                .filter(f -> kw == null || matches(f, kw))
                .limit(cap)
                .toList();
    }

    /**
     * 某事实的版本链（新→旧）。
     *
     * @param projectId 项目/世界 ID
     *
     * @param factId 事实 ID
     *
     * @return 版本日志列表（事实不存在/跨项目时 404）
     */
    public List<ActorWorldFactLog> versions(Long projectId, Long factId) {
        if (projectId == null || factId == null) {
            throw new BizException(404, "事实不存在或无权访问");
        }
        ActorWorldFact fact = factRepository.findById(factId)
                .filter(f -> projectId.equals(f.getProjectId()))
                .orElseThrow(() -> new BizException(404, "事实不存在或无权访问"));
        return logRepository.findByFactIdOrderByVersionDescIdDesc(fact.getId());
    }

    /**
     * 关键词命中 factKey/name/stateText 任一。
     */
    private static boolean matches(ActorWorldFact f, String kw) {
        return contains(f.getFactKey(), kw) || contains(f.getName(), kw) || contains(f.getStateText(), kw);
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
