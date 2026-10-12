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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.usage.service.UsageService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 决策链路聚合服务（阶段 D 决策链路可视化，domain/debug/service）。
 * <p>职责：基于 {@code actor_ai_call_log} 的 trace_id 关联查询，把「一次决策 = 一条链路」从
 * 原始行数据聚合成链路 VO——感知→思考→行动→学习 的完整环节序列（AI 调用 + 感知/行动/事件等
 * 非 AI 环节 + 重试 attempt 分次），供调试前端「AI Trace 页决策链路可视化」与「每角色最近 N 次
 * 决策轨迹」消费。纯只读，不引入任何写操作。</p>
 * <p>链路聚合口径：<ul>
 * <li>同 trace_id 的全部行折叠为一条链路（trace_id 为空的行各自成独立单条链路）；</li>
 * <li>链路状态 = 组内最差（failed &gt; degraded &gt; success）；token/耗时 = 组内汇总；</li>
 * <li>环节 steps 按 id 升序（= 发生顺序），含 stepType/attempt/scene/model/status/token/耗时/content；</li>
 * <li>单链路详情额外带 AI 环节的 detail_json（AI 调用详情 JSON 渲染页数据源）。</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class DebugTraceService {

    /** 链路列表行级查询上限（本地 H2 规模足够，防全量拉爆内存） */
    private static final int MAX_ROW_CAP = 2000;

    /** 每角色最近 N 次决策轨迹的行级查询上限（N×此系数足够覆盖同一链路的全部环节） */
    private static final int CHARACTER_ROW_CAP = 500;

    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;

    // ==================== ① 链路列表（分页 + 筛选） ====================

    /**
     * 按 trace_id 聚合的链路列表（：感知→思考→行动→学习 一次决策一条链路）。
     * <p>筛选（projectId/characterId/scene/status/keyword/时间范围）作用于行级，行级查询
     * 按 id 倒序取最近上限内数据，再内存聚合分组；链路级分页（页大小可能因同链路多行略小于
     * 请求值，本地规模可接受，前端按返回展示）。</p>
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景（可空）
     *
     * @param status 状态（可空）
     *
     * @param keyword 关键词（可空）
     *
     * @param startDate 起始日期 yyyy-MM-dd（可空）
     *
     * @param endDate 结束日期 yyyy-MM-dd（可空）
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 20，上限 100）
     *
     * @return {page,size,total,items: 链路 VO 列表}
     */
    public Map<String, Object> traceList(Long projectId, Long characterId, String scene, String status,
                                         String keyword, String startDate, String endDate,
                                         int page, int size) {
        Specification<ActorAiCallLog> spec = buildSpec(projectId, characterId, scene, status, keyword,
                startDate, endDate);
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        // 行级查询：按 id 倒序取最近 MAX_ROW_CAP 条（时间倒序 ≈ 最近优先），再内存分组
        List<ActorAiCallLog> rows = aiCallLogRepository.findAll(spec,
                PageRequest.of(0, MAX_ROW_CAP, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        List<Map<String, Object>> groups = groupByTrace(rows);

        int total = groups.size();
        int from = Math.min((pageNo - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> items = new ArrayList<>(groups.subList(from, to));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    // ==================== ② 单链路详情 ====================

    /**
     * 单链路详情：trace_id 下按 id 升序的全部环节（AI 调用 + 非 AI 环节 + 重试 attempt），
     * AI 环节附带 detail_json（AI 调用详情 JSON 渲染页数据源）。
     *
     * @param traceId 决策链路 trace_id
     *
     * @return {traceId, steps: 环节详情列表}；traceId 为空或不存在时 steps 为空列表
     */
    public Map<String, Object> traceDetail(String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        List<Map<String, Object>> steps = new ArrayList<>();
        if (traceId != null && !traceId.isBlank()) {
            List<ActorAiCallLog> rows = aiCallLogRepository.findByTraceIdOrderByIdAsc(traceId.trim());
            for (ActorAiCallLog r : rows) {
                steps.add(stepVO(r, true));
            }
        }
        out.put("steps", steps);
        return out;
    }

    // ==================== ③ 每角色最近 N 次决策轨迹 ====================

    /**
     * 每角色最近 N 次决策轨迹：按链路最新时间倒序取该角色最近 N 条链路（默认 10，
     * 可调 5/10/20），复用链路 VO 聚合口径。
     *
     * @param characterId 角色 ID
     *
     * @param limit 最近 N 条（默认 10，上限 50）
     *
     * @return {characterId, characterName, limit, items: 链路 VO 列表}
     */
    public Map<String, Object> characterTraces(Long characterId, int limit) {
        int n = Math.min(Math.max(1, limit), 50);
        List<ActorAiCallLog> rows = aiCallLogRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("characterId"), characterId),
                PageRequest.of(0, CHARACTER_ROW_CAP, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        List<Map<String, Object>> groups = groupByTrace(rows);
        List<Map<String, Object>> items = new ArrayList<>(groups.subList(0, Math.min(n, groups.size())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("characterId", characterId);
        out.put("characterName", characterName(characterId));
        out.put("limit", n);
        out.put("items", items);
        return out;
    }

    // ==================== 链路聚合工具 ====================

    /**
     * 行列表 → 按 trace_id 分组的链路 VO 列表（trace_id 空行各自成组）。
     * <p>行列表已按 id 倒序（最近优先）：分组 Map 按首次出现顺序保持「最近链路在前」，
     * 最终按链路内最新 created_at 倒序稳定排序。</p>
     *
     * @param rows 行列表（按 id 倒序）
     *
     * @return 链路 VO 列表（新在前）
     */
    private List<Map<String, Object>> groupByTrace(List<ActorAiCallLog> rows) {
        LinkedHashMap<String, List<ActorAiCallLog>> map = new LinkedHashMap<>();
        for (ActorAiCallLog r : rows) {
            String key = (r.getTraceId() == null || r.getTraceId().isBlank())
                    ? "no-trace:" + r.getId()
                    : r.getTraceId();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (List<ActorAiCallLog> items : map.values()) {
            // 组内按 id 升序 = 环节发生顺序（感知→思考→行动→学习）
            items.sort(Comparator.comparingLong(ActorAiCallLog::getId));
            groups.add(buildTraceVO(items));
        }
        // 链路按组内最新时间倒序（时间字符串 ISO 排序等价于时间序）
        groups.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return groups;
    }

    /**
     * 组内环节（id 升序）→ 链路 VO。
     *
     * @param chrono 环节列表（id 升序）
     *
     * @return 链路 VO
     */
    private Map<String, Object> buildTraceVO(List<ActorAiCallLog> chrono) {
        ActorAiCallLog first = chrono.get(0);
        ActorAiCallLog last = chrono.get(chrono.size() - 1);
        int tokenIn = 0, tokenOut = 0, duration = 0;
        String worst = "success";
        for (ActorAiCallLog r : chrono) {
            tokenIn += nz(r.getTokenIn());
            tokenOut += nz(r.getTokenOut());
            duration += nz(r.getDurationMs());
            worst = worstStatus(worst, r.getStatus());
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("traceId", first.getTraceId());
        vo.put("time", last.getCreatedAt() == null ? null : last.getCreatedAt().toString());
        vo.put("stepCount", chrono.size());
        vo.put("status", worst);
        vo.put("tokenIn", tokenIn);
        vo.put("tokenOut", tokenOut);
        vo.put("durationMs", duration);
        vo.put("projectId", first.getProjectId());
        vo.put("projectName", projectName(first.getProjectId()));
        vo.put("characterId", first.getCharacterId());
        vo.put("characterName", characterName(first.getCharacterId()));
        vo.put("scene", first.getScene());
        vo.put("sceneName", sceneName(first.getScene()));
        vo.put("model", first.getModel());
        vo.put("steps", steps(chrono, false));
        return vo;
    }

    /**
     * 环节行 → 环节 VO（列表/详情共用；withDetailJson=true 时附带 detail_json 供详情渲染）。
     *
     * @param r 环节行
     *
     * @param withDetailJson 是否附带 detail_json
     *
     * @return 环节 VO
     */
    private Map<String, Object> stepVO(ActorAiCallLog r, boolean withDetailJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("traceId", r.getTraceId());
        m.put("stepType", r.getStepType());
        m.put("attempt", r.getAttempt());
        m.put("scene", r.getScene());
        m.put("sceneName", sceneName(r.getScene()));
        m.put("model", r.getModel());
        m.put("status", r.getStatus());
        m.put("tokenIn", nz(r.getTokenIn()));
        m.put("tokenOut", nz(r.getTokenOut()));
        m.put("durationMs", nz(r.getDurationMs()));
        m.put("content", r.getContent());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        if (withDetailJson) {
            m.put("detailJson", r.getDetailJson());
        }
        return m;
    }

    /**
     * 链路 VO 内嵌环节列表。
     *
     * @param chrono 环节列表（id 升序）
     *
     * @param withDetailJson 是否附带 detail_json
     *
     * @return 环节 VO 列表
     */
    private List<Map<String, Object>> steps(List<ActorAiCallLog> chrono, boolean withDetailJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorAiCallLog r : chrono) {
            out.add(stepVO(r, withDetailJson));
        }
        return out;
    }

    // ==================== 筛选 Specification（对齐 DebugController.aiCalls 口径） ====================

    /**
     * 构建 ai_call_log 行级筛选 Specification（projectId/characterId/scene/status/keyword/时间范围）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景（可空）
     *
     * @param status 状态（可空）
     *
     * @param keyword 关键词（可空）
     *
     * @param startDate 起始日期（可空）
     *
     * @param endDate 结束日期（可空）
     */
    private Specification<ActorAiCallLog> buildSpec(Long projectId, Long characterId, String scene,
                                                    String status, String keyword, String startDate,
                                                    String endDate) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (projectId != null) {
                ps.add(cb.equal(root.get("projectId"), projectId));
            }
            if (characterId != null) {
                ps.add(cb.equal(root.get("characterId"), characterId));
            }
            if (scene != null && !scene.isBlank()) {
                ps.add(cb.equal(root.get("scene"), scene));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("systemPrompt")), like),
                        cb.like(cb.lower(root.get("userPrompt")), like),
                        cb.like(cb.lower(root.get("response")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("errorMessage"), "")), like)
                ));
            }
            if (startDate != null && !startDate.isBlank()) {
                LocalDateTime start = parseDateStart(startDate);
                if (start != null) {
                    ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
                }
            }
            if (endDate != null && !endDate.isBlank()) {
                LocalDateTime end = parseDateEnd(endDate);
                if (end != null) {
                    ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
                }
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 日期 → 当天起始（可空，解析失败返回 null） */
    private LocalDateTime parseDateStart(String raw) {
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** 日期 → 当天结束（可空） */
    private LocalDateTime parseDateEnd(String raw) {
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay().plusDays(1).minusNanos(1);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 名称/状态工具 ====================

    /** 项目名解析（无则 null） */
    private String projectName(Long projectId) {
        if (projectId == null) {
            return null;
        }
        Optional<ActorProject> p = projectRepository.findById(projectId);
        return p.map(ActorProject::getName).orElse(null);
    }

    /** 角色名解析（无则 null） */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return null;
        }
        Optional<ActorCharacter> c = characterRepository.findById(characterId);
        return c.map(ActorCharacter::getName).orElse(null);
    }

    /** 场景中文名（与用量统计同源） */
    private String sceneName(String scene) {
        return UsageService.sceneName(scene);
    }

    /** 状态最差合并：failed &gt; degraded &gt; success */
    private String worstStatus(String a, String b) {
        if ("failed".equals(a) || "failed".equals(b)) {
            return "failed";
        }
        if ("degraded".equals(a) || "degraded".equals(b)) {
            return "degraded";
        }
        return "success";
    }

    /** 空值安全取整 */
    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
