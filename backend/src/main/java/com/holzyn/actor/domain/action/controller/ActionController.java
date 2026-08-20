package com.holzyn.actor.domain.action.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.action.entity.ActorActionLog;
import com.holzyn.actor.domain.action.entity.ActorActionPlan;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.action.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.action.service.ActionEngine;
import com.holzyn.actor.domain.action.service.ActionSseHub;
import com.holzyn.actor.domain.character.service.CharacterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行动引擎控制器（A-C5，P2 阶段二真实实现）。
 * <p>职责：提供行动决策列表、手动触发（支持计划时间定时）、行动时间线
 * （决策 + 世界事件 + 执行日志三类节点聚合）与行动日志；并提供项目级时间线聚合
 * 与 SSE 实时行动事件流（GET /api/actions/stream）。</p>
 * <p>所属模块：controller/action（行动子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ActionController {

    private final ActionEngine actionEngine;
    private final ActionSseHub actionSseHub;
    private final ActorActionPlanRepository planRepository;
    private final ActorActionLogRepository logRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorProjectRepository projectRepository;
    private final CharacterService characterService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    /**
     * 行动决策列表（单角色，新→旧）。
     *
     * @param characterId 角色 ID
     * @return 行动决策列表
     */
    @GetMapping("/characters/{characterId}/actions")
    public R<List<Map<String, Object>>> list(@PathVariable("characterId") Long characterId) {
        characterService.requireOwned(characterId);
        return R.ok(planRepository.findByCharacterIdOrderByIdDesc(characterId).stream().map(this::planVO).toList());
    }

    /**
     * 手动触发一次行动决策（manual 触发源；plannedTime 为未来时间则定时到期执行）。
     *
     * @param characterId 角色 ID
     * @param body        触发请求体：{reason?, plannedTime?, situation?}
     * @return 创建的行动计划（status=planned 表示待定时执行 / done 表示已执行）
     */
    @PostMapping("/characters/{characterId}/actions/trigger")
    public R<Map<String, Object>> trigger(@PathVariable("characterId") Long characterId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Long userId = currentUserProvider.currentUserId();
        String reason = body == null ? null : str(body.get("reason"));
        String situation = body == null ? null : str(body.get("situation"));
        LocalDateTime plannedTime = parseTime(body == null ? null : body.get("plannedTime"));
        ActorActionPlan plan = actionEngine.trigger(characterId, userId, "manual", null, reason, plannedTime, situation);
        return R.ok(planVO(plan));
    }

    /**
     * 行动时间线（单角色：决策 + 日志聚合，时间倒序）。
     *
     * @param characterId 角色 ID
     * @return 时间线节点列表
     */
    @GetMapping("/characters/{characterId}/actions/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable("characterId") Long characterId) {
        characterService.requireOwned(characterId);
        List<ActorActionPlan> plans = planRepository.findByCharacterIdOrderByIdDesc(characterId);
        List<ActorActionLog> logs = logRepository.findByCharacterIdOrderByLogTimeDesc(characterId);
        return R.ok(mergeTimeline(plans, logs, List.of()));
    }

    /**
     * 行动日志（单角色，时间倒序）。
     *
     * @param characterId 角色 ID
     * @return 行动日志列表
     */
    @GetMapping("/characters/{characterId}/action-logs")
    public R<List<Map<String, Object>>> logs(@PathVariable("characterId") Long characterId) {
        characterService.requireOwned(characterId);
        return R.ok(logRepository.findByCharacterIdOrderByLogTimeDesc(characterId).stream().map(this::logVO).toList());
    }

    /**
     * 项目级行动时间线（聚合项目全部角色的决策/事件/日志，支持角色/状态/触发源/时间范围筛选）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色筛选（可选）
     * @param status      状态筛选（可选：planned/done/cancelled）
     * @param triggerType 触发源筛选（可选：manual/after_dialog/scheduled/event）
     * @param startDate   起始日期（可选，yyyy-MM-dd）
     * @param endDate     结束日期（可选，yyyy-MM-dd）
     * @return {plans, logs, events, timeline, characters}
     */
    @GetMapping("/projects/{projectId}/actions/timeline")
    public R<Map<String, Object>> projectTimeline(
            @PathVariable("projectId") Long projectId,
            @RequestParam(name = "characterId", required = false) Long characterId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "triggerType", required = false) String triggerType,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate) {
        requireProject(projectId);
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        List<Long> ids = chars.stream().map(ActorCharacter::getId).toList();
        if (characterId != null) {
            ids = ids.stream().filter(id -> id.equals(characterId)).toList();
        }

        List<ActorActionPlan> plans = List.of();
        List<ActorActionLog> logs = List.of();
        List<ActorMessage> events = List.of();
        if (!ids.isEmpty()) {
            plans = planRepository.findByCharacterIdInOrderByIdDesc(ids);
            logs = logRepository.findByCharacterIdInOrderByLogTimeDesc(ids);
            if (status != null && !status.isBlank()) {
                plans = plans.stream().filter(p -> status.equals(p.getStatus())).toList();
            }
            if (triggerType != null && !triggerType.isBlank()) {
                plans = plans.stream().filter(p -> triggerType.equals(p.getTriggerType())).toList();
            }
            // 世界事件：该项目全部会话的 event 消息
            List<Long> convIds = conversationRepository
                    .findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, currentUserProvider.currentUserId())
                    .stream().map(ActorConversation::getId).toList();
            if (!convIds.isEmpty()) {
                events = messageRepository.findByTypeAndConversationIdInOrderByIdAsc("event", convIds);
            }
        }

        LocalDateTime start = parseDate(startDate, true);
        LocalDateTime end = parseDate(endDate, false);
        List<ActorActionPlan> filteredPlans = filterPlansByTime(plans, start, end);
        List<ActorActionLog> filteredLogs = filterLogsByTime(logs, start, end);
        List<ActorMessage> filteredEvents = filterEventsByTime(events, start, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plans", filteredPlans.stream().map(this::planVO).toList());
        result.put("logs", filteredLogs.stream().map(this::logVO).toList());
        result.put("events", filteredEvents.stream().map(this::eventVO).toList());
        result.put("timeline", mergeTimeline(filteredPlans, filteredLogs, filteredEvents));
        result.put("characters", chars.stream()
                .map(ch -> Map.of("id", ch.getId(), "name", ch.getName())).toList());
        return R.ok(result);
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 标记行动计划状态（详情面板「标记状态」用；仅允许 done/cancelled/planned）。
     *
     * @param planId 行动计划 ID
     * @param body   请求体：{status}
     * @return 更新后的计划视图
     */
    @PutMapping("/actions/{planId}/status")
    public R<Map<String, Object>> updateStatus(@PathVariable("planId") Long planId, @RequestBody Map<String, Object> body) {
        ActorActionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException(404, "行动计划不存在"));
        characterService.requireOwned(plan.getCharacterId());
        String status = body == null ? null : str(body.get("status"));
        if (!List.of("planned", "done", "cancelled").contains(status)) {
            throw new BizException(400, "状态仅支持 planned/done/cancelled");
        }
        plan.setStatus(status);
        if ("done".equals(status) && plan.getExecutedAt() == null) {
            plan.setExecutedAt(LocalDateTime.now());
        }
        return R.ok(planVO(planRepository.save(plan)));
    }

    /**
     * SSE 行动事件流（时间线实时刷新订阅）。
     *
     * @return SseEmitter（ActionSseHub 广播 action 事件）
     */
    @GetMapping(value = "/actions/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        return actionSseHub.subscribe();
    }

    /**
     * 行动决策 → 视图 Map（扁平化 action_json 关键字段）。
     *
     * @param p 决策实体
     * @return 视图 Map
     */
    private Map<String, Object> planVO(ActorActionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("characterId", p.getCharacterId());
        m.put("conversationId", p.getConversationId());
        m.put("triggerType", p.getTriggerType());
        m.put("status", p.getStatus());
        m.put("plannedTime", p.getPlannedTime());
        m.put("executedAt", p.getExecutedAt());
        m.put("createdAt", p.getCreatedAt());
        try {
            var d = objectMapper.readTree(p.getActionJson());
            m.put("type", d.path("type").asText(""));
            m.put("action", d.path("action").asText(""));
            m.put("target", d.path("target").asText(""));
            m.put("params", d.path("params").isObject() ? objectMapper.convertValue(d.path("params"), Map.class) : Map.of());
            m.put("reason", d.path("reason").asText(""));
            m.put("urgency", d.path("urgency").asInt(0));
            m.put("duration", d.path("duration").asInt(0));
        } catch (Exception e) {
            // action_json 解析失败时保留基础字段
        }
        return m;
    }

    /**
     * 行动日志 → 视图 Map。
     *
     * @param l 日志实体
     * @return 视图 Map
     */
    private Map<String, Object> logVO(ActorActionLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("characterId", l.getCharacterId());
        m.put("planId", l.getPlanId());
        m.put("summary", l.getSummary());
        m.put("detail", l.getDetail());
        m.put("logTime", l.getLogTime());
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    /**
     * 世界事件消息 → 视图 Map。
     *
     * @param e 事件消息实体
     * @return 视图 Map
     */
    private Map<String, Object> eventVO(ActorMessage e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("conversationId", e.getConversationId());
        m.put("content", e.getContent());
        m.put("time", e.getCreatedAt());
        return m;
    }

    /**
     * 聚合时间线节点：决策(kind=plan) / 世界事件(kind=event) / 执行日志(kind=log)，时间倒序。
     *
     * @param plans  行动决策
     * @param logs   执行日志
     * @param events 世界事件消息
     * @return 时间线节点列表
     */
    private List<Map<String, Object>> mergeTimeline(List<ActorActionPlan> plans,
                                                    List<ActorActionLog> logs,
                                                    List<ActorMessage> events) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ActorActionPlan p : plans) {
            Map<String, Object> n = planVO(p);
            n.put("kind", "plan");
            n.put("time", p.getPlannedTime() != null ? p.getPlannedTime() : p.getCreatedAt());
            nodes.add(n);
        }
        for (ActorMessage e : events) {
            Map<String, Object> n = eventVO(e);
            n.put("kind", "event");
            n.put("time", e.getCreatedAt());
            nodes.add(n);
        }
        for (ActorActionLog l : logs) {
            Map<String, Object> n = logVO(l);
            n.put("kind", "log");
            n.put("time", l.getLogTime());
            nodes.add(n);
        }
        // 时间倒序（time 为空视为最早）
        nodes.sort((a, b) -> {
            LocalDateTime ta = (LocalDateTime) a.get("time");
            LocalDateTime tb = (LocalDateTime) b.get("time");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        return nodes;
    }

    /**
     * 按时间范围过滤行动决策（依据 created_at）。
     *
     * @param plans 决策列表
     * @param start 起始时间（可空）
     * @param end   结束时间（可空）
     * @return 过滤后列表
     */
    private List<ActorActionPlan> filterPlansByTime(List<ActorActionPlan> plans, LocalDateTime start, LocalDateTime end) {
        return plans.stream()
                .filter(p -> (start == null || !p.getCreatedAt().isBefore(start))
                        && (end == null || !p.getCreatedAt().isAfter(end)))
                .toList();
    }

    /**
     * 按时间范围过滤行动日志（依据 log_time）。
     */
    private List<ActorActionLog> filterLogsByTime(List<ActorActionLog> logs, LocalDateTime start, LocalDateTime end) {
        return logs.stream()
                .filter(l -> (start == null || !l.getLogTime().isBefore(start))
                        && (end == null || !l.getLogTime().isAfter(end)))
                .toList();
    }

    /**
     * 按时间范围过滤世界事件（依据 created_at）。
     */
    private List<ActorMessage> filterEventsByTime(List<ActorMessage> events, LocalDateTime start, LocalDateTime end) {
        return events.stream()
                .filter(e -> (start == null || !e.getCreatedAt().isBefore(start))
                        && (end == null || !e.getCreatedAt().isAfter(end)))
                .toList();
    }

    /**
     * 取值辅助：Object 转字符串。
     *
     * @param v 原始对象
     * @return 字符串
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 解析计划时间（支持 ISO 或 yyyy-MM-dd HH:mm）。
     *
     * @param v 原始对象
     * @return 时间（解析失败返回 null）
     */
    private LocalDateTime parseTime(Object v) {
        if (v == null) {
            return null;
        }
        String raw = String.valueOf(v).trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * 解析日期范围（isStart=true 取当天 00:00；false 取当天 23:59:59）。
     *
     * @param v      原始日期（yyyy-MM-dd，可空）
     * @param isStart 是否为起始
     * @return 时间（可空）
     */
    private LocalDateTime parseDate(Object v, boolean isStart) {
        if (v == null) {
            return null;
        }
        String raw = String.valueOf(v).trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            LocalDateTime d = LocalDate.parse(raw).atStartOfDay();
            return isStart ? d : d.plusDays(1).minusNanos(1);
        } catch (Exception e) {
            return null;
        }
    }
}