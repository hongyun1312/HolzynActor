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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.DebugAgentOverviewService;
import com.holzyn.actor.domain.debug.service.DebugConfigService;
import com.holzyn.actor.domain.debug.service.DebugDbService;
import com.holzyn.actor.domain.debug.service.DebugDriftMetricsService;
import com.holzyn.actor.domain.debug.service.DebugEventHub;
import com.holzyn.actor.domain.debug.service.DebugLogService;
import com.holzyn.actor.domain.debug.service.DebugOverviewService;
import com.holzyn.actor.domain.debug.service.DebugTraceService;
import com.holzyn.actor.domain.debug.service.DebugUsageStatsService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 调试可视化 API 控制器（调试前端，全部只读）。
 * <p>职责：暴露调试前端七个模块所需的只读 API——总览/日志（缓冲+SSE 流）/AI Trace（列表+详情）/
 * 用量聚合/DB 只读浏览（白名单）/事件流（SSE）/配置脱敏查看。</p>
 * <p>安全约定：本地单用户、无鉴权（纯本地调试工具）；DB 浏览仅白名单表 + 敏感列剔除；
 * 配置页 API Key 一律打码；无任何写端点。</p>
 * <p>SSE 端点：/api/debug/logs/stream（日志流，补发 500+实时）、/api/debug/events/stream（事件流）。</p>
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugController {

    private final DebugOverviewService overviewService;
    private final DebugAgentOverviewService agentOverviewService;
    private final DebugTraceService traceService;
    private final DebugDriftMetricsService driftMetricsService;
    private final DebugLogService logService;
    private final ActorAiCallLogRepository aiCallLogRepository;
    private final DebugUsageStatsService usageStatsService;
    private final DebugDbService dbService;
    private final DebugEventHub eventHub;
    private final DebugConfigService configService;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;

    // ==================== ① 总览 ====================

    /**
     * 调试总览：后端状态 + 项目摘要 + 最近 AI 调用。
     *
     * @return 总览数据
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return overviewService.overview();
    }

    /**
     * 世界引擎快照（W-2 最小回滚手段前置，B3）：按项目 dump 世界关键表为 JSON——
     * 时钟（world_running/暂停/推进位置/回补基准）+ 引擎配置（预算/步长）+ 角色/居民状态 +
     * 近期事件 + 近期 AI 调用。调试前端「世界快照」入口消费；纯只读。
     *
     * @param projectId 项目 ID
     *
     * @return 快照 Map
     */
    @GetMapping("/engine/snapshot")
    public Map<String, Object> engineSnapshot(@RequestParam("projectId") Long projectId) {
        return overviewService.engineSnapshot(projectId);
    }

    /**
     * AgentRuntime 运行概览（调试前端 AgentRuntime 概览视图，阶段 C 贯穿项）：
     * 各角色 brainProfile（脑力档/模型档/决策频率/组件开关/记忆与 AI 预算）+ 决策频率与预算使用
     * （近 30 天 AI 调用/决策调用/决策链路/Token 用量）+ 六组件级状态（thinking/action/roleplay/
     * memory/runtime/learning + perception/brain 扩展，按 trace 场景与环节聚合）+ 引擎运行汇总。
     * 纯只读，按项目过滤可选。
     *
     * @param projectId 项目 ID（可空=全部项目）
     *
     * @return AgentRuntime 概览 Map
     */
    @GetMapping("/agent/runtime-overview")
    public Map<String, Object> agentRuntimeOverview(@RequestParam(required = false) Long projectId) {
        return agentOverviewService.runtimeOverview(projectId);
    }

    // ==================== ② 实时日志 ====================

    /**
     * 日志缓冲分页回看。
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 100，上限 500）
     *
     * @param level 级别过滤（可空）
     *
     * @param keyword 关键词过滤（可空）
     */
    @GetMapping("/logs/buffer")
    public Map<String, Object> logsBuffer(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "100") int size,
                                          @RequestParam(required = false) String level,
                                          @RequestParam(required = false) String keyword) {
        return logService.page(page, size, level, keyword);
    }

    /**
     * 实时日志流（SSE）：新连接补发最近 500 条，再实时推送。
     *
     * @param level 级别粗过滤（可空）
     *
     * @param keyword 关键词粗过滤（可空）
     *
     * @return SSE 流（connected + log 事件）
     */
    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logsStream(@RequestParam(required = false) String level,
                                 @RequestParam(required = false) String keyword) {
        return logService.subscribe(level, keyword);
    }

    //

    /**
     * AI Trace 分页列表：筛选 projectId/scene/status/keyword/时间范围 + 排序（时间/耗时/token）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param scene 场景（可空）
     *
     * @param status 状态（success/failed/degraded，可空）
     *
     * @param keyword 关键词（搜 systemPrompt/userPrompt/response/errorMessage，可空）
     *
     * @param startDate 起始日期（yyyy-MM-dd，可空）
     *
     * @param endDate 结束日期（yyyy-MM-dd，可空）
     *
     * @param sort 排序：time(默认)/duration/tokens
     *
     * @param failedFirst 失败优先（true 时 failed/degraded 排前，默认 false）
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 20，上限 100）
     */
    @GetMapping("/ai-calls")
    public Map<String, Object> aiCalls(@RequestParam(required = false) Long projectId,
                                       @RequestParam(required = false) String scene,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate,
                                       @RequestParam(defaultValue = "time") String sort,
                                       @RequestParam(defaultValue = "false") boolean failedFirst,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Specification<ActorAiCallLog> spec = buildAiCallSpec(projectId, scene, status, keyword, startDate, endDate);
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);

        List<Sort.Order> orders = new ArrayList<>();
        switch (sort == null ? "time" : sort) {
            case "duration" -> orders.add(new Sort.Order(Sort.Direction.DESC, "durationMs"));
            case "tokens" -> orders.add(new Sort.Order(Sort.Direction.DESC, "tokenIn"));
            default -> orders.add(new Sort.Order(Sort.Direction.DESC, "id"));
        }
        Page<ActorAiCallLog> result = aiCallLogRepository.findAll(spec, PageRequest.of(pageNo - 1, pageSize, Sort.by(orders)));

        List<ActorAiCallLog> rows = new ArrayList<>(result.getContent());
        if (failedFirst) {
            // 失败优先：页内稳定重排——failed/degraded 排前（调试场景下优先看失败/降级）
            rows.sort((a, b) -> {
                int pa = statusPriority(a.getStatus());
                int pb = statusPriority(b.getStatus());
                if (pa != pb) {
                    return Integer.compare(pa, pb);
                }
                return Long.compare(b.getId(), a.getId());
            });
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ActorAiCallLog l : rows) {
            items.add(aiCallRow(l));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", result.getTotalElements());
        out.put("items", items);
        return out;
    }

    /**
     * 状态优先级（失败优先排序用：失败/降级在前）。
     *
     * @param status 状态
     *
     * @return 优先级（越小越前）
     */
    private static int statusPriority(String status) {
        if ("failed".equals(status) || "degraded".equals(status)) {
            return 0;
        }
        return 1;
    }

    /**
     * 单条 AI Trace 详情（完整 prompt/response/错误/降级）。
     *
     * @param id 记录主键
     *
     * @return 详情 Map
     */
    @GetMapping("/ai-calls/{id}")
    public Map<String, Object> aiCallDetail(@PathVariable("id") Long id) {
        ActorAiCallLog log = aiCallLogRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "AI Trace 记录不存在：" + id));
        Map<String, Object> m = aiCallRow(log);
        // （detail_json）：详情接口附带整条 AI 调用详情 JSON（列表接口不返回，避免拉重）
        m.put("detailJson", log.getDetailJson());
        return m;
    }

    // ==================== ③.1 决策链路可视化（trace 关联查询，） ====================

    /**
     * 决策链路列表：按 trace_id 聚合——一次决策（感知→思考→行动→学习）一条链路。
     * <p>端点路径为字面量，与 {@link #aiCallDetail(Long)} 的 /ai-calls/{id} 无冲突（Spring 字面量优先）。</p>
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
    @GetMapping("/ai-calls/traces")
    public Map<String, Object> aiCallTraces(@RequestParam(required = false) Long projectId,
                                            @RequestParam(required = false) Long characterId,
                                            @RequestParam(required = false) String scene,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return traceService.traceList(projectId, characterId, scene, status, keyword,
                startDate, endDate, page, size);
    }

    /**
     * 单链路详情：trace_id 下按发生顺序的全部环节（AI 调用 + 非 AI 环节 + 重试 attempt），
     * AI 环节附带 detail_json（AI 调用详情 JSON 渲染页数据源）。
     *
     * @param traceId 决策链路 trace_id
     */
    @GetMapping("/ai-calls/trace/{traceId}")
    public Map<String, Object> aiCallTraceDetail(@PathVariable("traceId") String traceId) {
        return traceService.traceDetail(traceId);
    }

    /**
     * 每角色最近 N 次决策轨迹：按链路最新时间倒序取该角色最近 N 条链路。
     *
     * @param characterId 角色 ID
     *
     * @param limit 最近 N 条（默认 10，上限 50）
     */
    @GetMapping("/ai-calls/character/{characterId}/traces")
    public Map<String, Object> characterTraces(@PathVariable("characterId") Long characterId,
                                               @RequestParam(defaultValue = "10") int limit) {
        return traceService.characterTraces(characterId, limit);
    }

    // ==================== ③.2 漂移度量视图（R4，） ====================

    /**
     * 漂移度量聚合（R4：目标一致率/矛盾率/Token 成本 + 按日趋势 + 按角色排行）。
     * <p>数据源：actor_character_goal（目标状态占比）+ actor_memory kind=reflection（矛盾关键词
     * 命中占比）+ actor_ai_call_log（窗口内 Token 成本，按日/按角色聚合）。</p>
     *
     * @param projectId 项目 ID（可空=全部项目）
     *
     * @param characterId 角色 ID（可空=全部角色）
     *
     * @param days 统计窗口（天，默认 30，上限 365）
     *
     * @return 漂移度量 Map
     */
    @GetMapping("/drift-metrics")
    public Map<String, Object> driftMetrics(@RequestParam(required = false) Long projectId,
                                            @RequestParam(required = false) Long characterId,
                                            @RequestParam(defaultValue = "30") int days) {
        return driftMetricsService.driftMetrics(projectId, characterId, days);
    }

    /**
     * AI Trace 行 → 前端 Map（附项目名/角色名/场景中文名）。
     *
     * @param l 记录实体
     *
     * @return 行 Map
     */
    private Map<String, Object> aiCallRow(ActorAiCallLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("projectId", l.getProjectId());
        m.put("projectName", projectName(l.getProjectId()));
        m.put("characterId", l.getCharacterId());
        m.put("characterName", characterName(l.getCharacterId()));
        // B5（决策链路）：trace_id（业务动作级贯穿）/ attempt（重试尝试序号）/ step_type（环节类型）/ content（非 AI 环节摘要）
        m.put("traceId", l.getTraceId());
        m.put("attempt", l.getAttempt());
        m.put("stepType", l.getStepType());
        m.put("content", l.getContent());
        m.put("scene", l.getScene());
        m.put("sceneName", UsageService.sceneName(l.getScene()));
        m.put("providerId", l.getProviderId());
        m.put("model", l.getModel());
        m.put("systemPrompt", l.getSystemPrompt());
        m.put("userPrompt", l.getUserPrompt());
        m.put("response", l.getResponse());
        m.put("status", l.getStatus());
        m.put("errorMessage", l.getErrorMessage());
        m.put("retryCount", l.getRetryCount());
        m.put("tokenIn", l.getTokenIn());
        m.put("tokenOut", l.getTokenOut());
        m.put("cacheHit", l.getCacheHit());
        m.put("cacheMiss", l.getCacheMiss());
        m.put("durationMs", l.getDurationMs());
        m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
        return m;
    }

    /**
     * 项目名解析。
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 项目名（无则 null）
     */
    private String projectName(Long projectId) {
        if (projectId == null) {
            return null;
        }
        Optional<ActorProject> p = projectRepository.findById(projectId);
        return p.map(ActorProject::getName).orElse(null);
    }

    /**
     * 角色名解析。
     *
     * @param characterId 角色 ID（可空）
     *
     * @return 角色名（无则 null）
     */
    private String characterName(Long characterId) {
        if (characterId == null) {
            return null;
        }
        Optional<ActorCharacter> c = characterRepository.findById(characterId);
        return c.map(ActorCharacter::getName).orElse(null);
    }

    /**
     * AI Trace 筛选 Specification 构建。
     *
     * @param projectId 项目 ID
     *
     * @param scene 场景
     *
     * @param status 状态
     *
     * @param keyword 关键词
     *
     * @param startDate 起始日期
     *
     * @param endDate 结束日期
     */
    private Specification<ActorAiCallLog> buildAiCallSpec(Long projectId, String scene, String status,
                                                          String keyword, String startDate, String endDate) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (projectId != null) {
                ps.add(cb.equal(root.get("projectId"), projectId));
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

    // ==================== ④ 用量聚合 ====================

    /**
     * 用量聚合统计（按 天/场景/项目）。
     *
     * @param days 统计范围（天，默认 30）
     */
    @GetMapping("/usage/stats")
    public Map<String, Object> usageStats(@RequestParam(defaultValue = "30") int days) {
        return usageStatsService.stats(days);
    }

    // ==================== ⑤ DB 只读浏览 ====================

    /**
     * 白名单表清单（含行数/列数）。
     *
     * @return 表信息列表
     */
    @GetMapping("/db/tables")
    public List<Map<String, Object>> dbTables() {
        return dbService.tables();
    }

    /**
     * 白名单表只读分页浏览。
     *
     * @param table 表名（白名单）
     *
     * @param page 页码
     *
     * @param size 每页条数
     *
     * @param keyword 关键字（可空）
     */
    @GetMapping("/db/{table}")
    public Map<String, Object> dbBrowse(@PathVariable("table") String table,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        @RequestParam(required = false) String keyword) {
        return dbService.browse(table, page, size, keyword);
    }

    // ==================== ⑥ 事件流 ====================

    /**
     * 调试事件流（SSE）：行动事件 + 世界事件消息（实时推送，新连接补发最近世界事件）。
     *
     * @return SSE 流（connected/action/world 事件）
     */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventsStream() {
        return eventHub.subscribe();
    }

    // ==================== ⑦ 配置查看 ====================

    /**
     * 配置脱敏查看（API Key 打码 + Prompt 模板列表）。
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return configService.get();
    }
}
