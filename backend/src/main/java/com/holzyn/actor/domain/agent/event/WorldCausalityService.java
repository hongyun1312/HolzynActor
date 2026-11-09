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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件因果树只读查询（因果抽屉）。
 *
 * <p>口径：以选中事件为根，向上回溯 {@code cause_event_id} 父链（root-first 返回），
 * 向下返回直接子事件；子事件由前端点击后再懒加载，避免一次展开整棵大树。
 * 只读、零 AI、无事务；跨项目事件一律 404。</p>
 *
 * <p>防护：父链最多 {@value #MAX_UP_DEPTH} 层、子事件最多 50 条；检测到自环/断链时
 * 返回 {@code cycle=true} / {@code truncatedUp=true}，前端明确标注而不是静默截断。</p>
 */
@Service
@RequiredArgsConstructor
public class WorldCausalityService {

    /** 父链回溯硬上限（与设计 depth≤6 同量级，留一倍余量）。 */
    public static final int MAX_UP_DEPTH = 12;

    /** 单次返回的直接子事件上限。 */
    public static final int MAX_CHILDREN = 50;

    private final ActorEventRepository eventRepository;
    private final WorldClockService worldClockService;

    /**
     * 查询一条事件的因果树（父链 + 一层子事件）。
     *
     * @param projectId 项目 ID
     *
     * @param eventId 选中事件 ID
     *
     * @param upDepth 向上回溯层数（0=只看当前；默认 6，钳制 0..12）
     *
     * @param childLimit 子事件条数（默认 20，钳制 1..50）
     *
     * @return {event, ancestors, children, truncatedUp, truncatedDown, cycle}
     */
    public Map<String, Object> causality(Long projectId, Long eventId, int upDepth, int childLimit) {
        if (projectId == null || eventId == null) {
            throw new BizException(404, "事件不存在或无权访问");
        }
        ActorEvent event = eventRepository.findByIdAndProjectId(eventId, projectId)
                .orElseThrow(() -> new BizException(404, "事件不存在或无权访问"));

        int maxUp = Math.max(0, Math.min(MAX_UP_DEPTH, upDepth));
        int maxChildren = Math.max(1, Math.min(MAX_CHILDREN, childLimit));

        List<Map<String, Object>> ancestors = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        seen.add(event.getId());
        boolean truncatedUp = false;
        boolean cycle = false;
        ActorEvent cursor = event;
        for (int i = 0; i < maxUp && cursor.getCauseEventId() != null; i++) {
            Long parentId = cursor.getCauseEventId();
            if (!seen.add(parentId)) {
                cycle = true;
                break;
            }
            ActorEvent parent = eventRepository.findByIdAndProjectId(parentId, projectId).orElse(null);
            if (parent == null) {
                // 父事件被滚动清理或跨项目：保留断链标记，不伪造父节点
                truncatedUp = true;
                break;
            }
            ancestors.add(view(parent));
            cursor = parent;
        }
        if (cursor.getCauseEventId() != null && !cycle && !truncatedUp && ancestors.size() >= maxUp) {
            // 还有更早的父链，但达到请求上限
            truncatedUp = true;
        }
        Collections.reverse(ancestors);

        List<ActorEvent> childRows = eventRepository
                .findByProjectIdAndCauseEventIdOrderByIdAsc(projectId, eventId);
        boolean truncatedDown = childRows.size() > maxChildren;
        List<Map<String, Object>> children = childRows.stream()
                .limit(maxChildren)
                .map(this::view)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", view(event));
        out.put("ancestors", ancestors);
        out.put("children", children);
        out.put("truncatedUp", truncatedUp);
        out.put("truncatedDown", truncatedDown);
        out.put("cycle", cycle);
        return out;
    }

    /**
     * 事件实体 → 前端统一载荷（复用世界事件总线映射，字段口径与 SSE/查询一致）。
     */
    private Map<String, Object> view(ActorEvent event) {
        return WorldEventViews.toMap(event, worldClockService);
    }
}
