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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactKnowledge;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactKnowledgeRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事实派生读模型实现（零写入）。
 *
 * <p>提示词摘要的格式是<b>契约</b>：同一 {@code gameSecond}、同一组事实，输出必须逐字节一致
 * （缓存命中率的前提）。因此这里不拼时间戳、不拼随机 ID，只用 {@code gameSecond} 与版本号。</p>
 */
@Service
public class WorldFactQueryImpl implements WorldFactQuery {

    private final ActorWorldFactRepository factRepository;

    private final ActorWorldFactKnowledgeRepository knowledgeRepository;

    public WorldFactQueryImpl(ActorWorldFactRepository factRepository,
                              ActorWorldFactKnowledgeRepository knowledgeRepository) {
        this.factRepository = factRepository;
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public List<ActorWorldFact> knownBy(Long projectId, String ref, long gameSecond, int limit) {
        if (projectId == null || ref == null || ref.isBlank() || limit <= 0) {
            return List.of();
        }
        List<ActorWorldFactKnowledge> rows = knowledgeRepository
                .findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                        projectId, ref, PageRequest.of(0, limit));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ActorWorldFact> byId = factsById(rows);
        List<ActorWorldFact> out = new ArrayList<>();
        for (ActorWorldFactKnowledge row : rows) {
            ActorWorldFact fact = byId.get(row.getFactId());
            if (validAt(fact, gameSecond)) {
                out.add(fact);
            }
        }
        return out;
    }

    @Override
    public List<ActorWorldFact> atLocation(Long projectId, String locationName, long gameSecond) {
        if (projectId == null || locationName == null || locationName.isBlank()) {
            return List.of();
        }
        return factRepository.findByProjectIdAndLocationNameAndStatusOrderByIdAsc(
                        projectId, locationName, "active").stream()
                .filter(f -> validAt(f, gameSecond))
                .toList();
    }

    @Override
    public List<ActorWorldFact> byType(Long projectId, String factType, String status, int limit) {
        if (projectId == null || factType == null || factType.isBlank() || limit <= 0) {
            return List.of();
        }
        String st = status == null || status.isBlank() ? "active" : status.trim().toLowerCase();
        return factRepository.findByProjectIdAndFactTypeAndStatusOrderByIdAsc(
                projectId, factType.trim().toLowerCase(), st, PageRequest.of(0, limit));
    }

    @Override
    public Optional<ActorWorldFact> byKey(Long projectId, String factKey) {
        if (projectId == null || factKey == null || factKey.isBlank()) {
            return Optional.empty();
        }
        return factRepository.findByProjectIdAndFactKey(projectId, factKey.trim());
    }

    @Override
    public boolean knows(Long projectId, String factKey, String ref) {
        if (projectId == null || factKey == null || factKey.isBlank() || ref == null || ref.isBlank()) {
            return false;
        }
        return byKey(projectId, factKey)
                .flatMap(f -> knowledgeRepository.findByProjectIdAndFactIdAndKnowerRef(
                        projectId, f.getId(), ref))
                .isPresent();
    }

    @Override
    public String digestForPrompt(Long projectId, String ref, long gameSecond, int maxLines) {
        if (projectId == null || ref == null || ref.isBlank() || maxLines <= 0) {
            return "";
        }
        List<ActorWorldFactKnowledge> rows = knowledgeRepository
                .findByProjectIdAndKnowerRefOrderByLearnedGameSecondDescIdDesc(
                        projectId, ref, PageRequest.of(0, maxLines));
        if (rows.isEmpty()) {
            return "";
        }
        Map<Long, ActorWorldFact> byId = factsById(rows);
        List<String> lines = new ArrayList<>();
        for (ActorWorldFactKnowledge row : rows) {
            ActorWorldFact fact = byId.get(row.getFactId());
            if (!validAt(fact, gameSecond)) {
                continue;
            }
            lines.add("- " + displayName(fact) + "：" + fact.getStateText()
                    + "（" + sourceNote(row) + "）");
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【我所知道的事】");
        for (String line : lines) {
            sb.append('\n').append(line);
        }
        long total = knowledgeRepository.countByProjectIdAndKnowerRef(projectId, ref);
        if (total > lines.size()) {
            sb.append("\n- 还有 ").append(total - lines.size()).append(" 件事你记着");
        }
        sb.append("\n[世界状态版本 ").append(stateVersion(projectId, gameSecond)).append(']');
        return sb.toString();
    }

    @Override
    public boolean hasFactDueWithin(Long projectId, String ref, long gameSecond, long windowSeconds) {
        return nearestDueWithin(projectId, ref, gameSecond, windowSeconds) != null;
    }

    @Override
    public ActorWorldFact nearestDueWithin(Long projectId, String ref, long gameSecond, long windowSeconds) {
        if (projectId == null || ref == null || ref.isBlank() || gameSecond <= 0 || windowSeconds <= 0) {
            return null;
        }
        // 只查该角色知道的最近事实；上限 50 覆盖日常认知量，同时避免全项目扫描。
        ActorWorldFact nearest = null;
        long nearestRemaining = Long.MAX_VALUE;
        for (ActorWorldFact fact : knownBy(projectId, ref, gameSecond, 50)) {
            Long to = fact.getValidToGameSecond();
            if (to == null || to < gameSecond) {
                continue;
            }
            long remaining = to - gameSecond;
            if (remaining >= 0 && remaining <= windowSeconds && remaining < nearestRemaining) {
                nearest = fact;
                nearestRemaining = remaining;
            }
        }
        return nearest;
    }

    @Override
    public long maxVersion(Long projectId) {
        return projectId == null ? 0L : factRepository.maxVersion(projectId);
    }

    @Override
    public String stateVersion(Long projectId, long gameSecond) {
        return "gs" + gameSecond + "/v" + maxVersion(projectId);
    }

    /**
     * 批量取事实并按 id 建索引（避免 N+1）。
     *
     * @param rows 认知行
     *
     * @return factId → fact
     */
    private Map<Long, ActorWorldFact> factsById(List<ActorWorldFactKnowledge> rows) {
        List<Long> ids = rows.stream().map(ActorWorldFactKnowledge::getFactId).distinct().toList();
        Map<Long, ActorWorldFact> byId = new LinkedHashMap<>();
        for (ActorWorldFact fact : factRepository.findAllById(ids)) {
            byId.put(fact.getId(), fact);
        }
        return byId;
    }

    /**
     * 事实在指定游戏秒是否有效（status=active 且落在有效期窗口内）。
     *
     * @param fact       事实（可空）
     *
     * @param gameSecond 当前游戏秒
     *
     * @return true=有效
     */
    private boolean validAt(ActorWorldFact fact, long gameSecond) {
        if (fact == null || !"active".equalsIgnoreCase(fact.getStatus())) {
            return false;
        }
        Long from = fact.getValidFromGameSecond();
        if (from != null && from > gameSecond) {
            return false;
        }
        Long to = fact.getValidToGameSecond();
        return to == null || to >= gameSecond;
    }

    /**
     * 展示名（name 空则退回 factKey）。
     *
     * @param fact 事实
     *
     * @return 展示名
     */
    private String displayName(ActorWorldFact fact) {
        return fact.getName() == null || fact.getName().isBlank() ? fact.getFactKey() : fact.getName();
    }

    /**
     * 来源注脚（固定映射，逐字节稳定）。
     *
     * @param row 认知行
     *
     * @return 注脚
     */
    private String sourceNote(ActorWorldFactKnowledge row) {
        String type = row.getSourceType() == null ? "" : row.getSourceType().toLowerCase();
        return switch (type) {
            case "own" -> "我自己的事";
            case "perceive" -> "我今天亲眼看见";
            case "told" -> row.getSourceRef() == null || row.getSourceRef().isBlank()
                    ? "听人说的" : "听" + row.getSourceRef() + "说的";
            case "rumor" -> "听说，未必准";
            case "public" -> "大家都知道";
            case "infer" -> "我猜的";
            default -> "我知道的";
        };
    }
}
