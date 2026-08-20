package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.action.entity.ActorActionLog;
import com.holzyn.actor.domain.action.entity.ActorActionPlan;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.action.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 时间线聚合服务（V2.1 时间线聚合接口）。
 * <p>职责：统一项目「编年史」视图——聚合 actor_event（手动/AI/演化/系统）+ 角色行动
 * （行动决策 plan / 执行日志 log）+ 世界事件消息（type=event）+ 长期记忆摘要（memory summary）
 * 为统一时间线节点；并提供手动新增事件、AI 从世界观识别生成事件。</p>
 * <p>数据来源：actor_event、actor_action_plan/log、actor_message（event）、actor_memory（summary）。</p>
 * <p>所属模块：service/world（时间线/事件子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService {

    /** AI 生成事件输出最大 token */
    private static final int EVENT_MAX_TOKENS = 1024;

    private final ActorEventRepository eventRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorMessageRepository messageRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorActionLogRepository logRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorWorldClockRepository clockRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final PromptService promptService;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    /**
     * 聚合项目时间线（统一节点，时间倒序）。
     *
     * @param projectId   项目 ID
     * @param types       类型过滤（event/action/memory，空=全部）
     * @param characterId 角色过滤（可空）
     * @param startDate   起始日期（可空）
     * @param endDate     结束日期（可空）
     * @return 统一时间线节点列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> aggregate(Long projectId, Set<String> types, Long characterId,
                                               String startDate, String endDate) {
        requireProject(projectId);
        LocalDateTime start = parseDate(startDate, true);
        LocalDateTime end = parseDate(endDate, false);
        boolean withEvent = types == null || types.isEmpty() || types.contains("event");
        boolean withAction = types == null || types.isEmpty() || types.contains("action");
        boolean withMemory = types == null || types.isEmpty() || types.contains("memory");

        List<Map<String, Object>> nodes = new ArrayList<>();

        // ① 事件节点：actor_event + 世界事件消息
        if (withEvent) {
            List<ActorEvent> events = eventRepository.findByProjectIdOrderByIdDesc(projectId);
            events.forEach(e -> {
                if (inRange(e.getCreatedAt(), start, end) && (characterId == null || characterId.equals(e.getCharacterId()))) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("key", "event-" + e.getId());
                    n.put("kind", "event");
                    n.put("time", e.getCreatedAt());
                    n.put("title", e.getTitle() == null || e.getTitle().isBlank() ? "项目事件" : e.getTitle());
                    n.put("text", e.getContent());
                    n.put("source", e.getSource());
                    n.put("characterId", e.getCharacterId());
                    n.put("characterName", e.getCharacterId() == null ? null : charName(e.getCharacterId()));
                    n.put("sceneId", e.getSceneId());
                    n.put("evolutionId", e.getEvolutionId());
                    nodes.add(n);
                }
            });
            // 世界事件消息（type=event 的会话消息）
            List<Long> convIds = conversationRepository
                    .findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, currentUserProvider.currentUserId())
                    .stream().map(ActorConversation::getId).toList();
            if (!convIds.isEmpty()) {
                messageRepository.findByTypeAndConversationIdInOrderByIdAsc("event", convIds).forEach(msg -> {
                    if (inRange(msg.getCreatedAt(), start, end)) {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("key", "event-msg-" + msg.getId());
                        n.put("kind", "event");
                        n.put("time", msg.getCreatedAt());
                        n.put("title", "世界事件");
                        n.put("text", msg.getContent());
                        n.put("source", "dialog");
                        n.put("characterId", null);
                        n.put("characterName", null);
                        nodes.add(n);
                    }
                });
            }
        }

        // ② 行动节点：行动决策 + 执行日志（按项目角色）
        if (withAction) {
            List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            List<Long> ids = chars.stream().map(ActorCharacter::getId).toList();
            if (characterId != null) {
                ids = ids.stream().filter(id -> id.equals(characterId)).toList();
            }
            if (!ids.isEmpty()) {
                planRepository.findByCharacterIdInOrderByIdDesc(ids).forEach(p -> {
                    if (inRange(p.getCreatedAt(), start, end)) {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("key", "plan-" + p.getId());
                        n.put("kind", "action");
                        n.put("time", p.getPlannedTime() != null ? p.getPlannedTime() : p.getCreatedAt());
                        n.put("title", charName(p.getCharacterId()) + "：" + actionText(p.getActionJson(), "action"));
                        n.put("text", actionText(p.getActionJson(), "reason"));
                        n.put("source", p.getTriggerType());
                        n.put("characterId", p.getCharacterId());
                        n.put("characterName", charName(p.getCharacterId()));
                        nodes.add(n);
                    }
                });
                logRepository.findByCharacterIdInOrderByLogTimeDesc(ids).forEach(l -> {
                    if (inRange(l.getLogTime(), start, end)) {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("key", "log-" + l.getId());
                        n.put("kind", "action");
                        n.put("time", l.getLogTime());
                        n.put("title", charName(l.getCharacterId()) + "：行动执行");
                        n.put("text", l.getSummary() == null ? l.getDetail() : l.getSummary());
                        n.put("source", "execution");
                        n.put("characterId", l.getCharacterId());
                        n.put("characterName", charName(l.getCharacterId()));
                        nodes.add(n);
                    }
                });
            }
        }

        // ③ 记忆里程碑：长期记忆（summary 摘要，全角色可见 + 项目级）
        if (withMemory) {
            List<ActorMemory> memories = memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0);
            memories.stream()
                    .filter(m -> "summary".equals(m.getKind()))
                    .filter(m -> characterId == null || characterId.equals(m.getCharacterId()))
                    .filter(m -> inRange(m.getCreatedAt(), start, end))
                    .forEach(m -> {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("key", "memory-" + m.getId());
                        n.put("kind", "memory");
                        n.put("time", m.getCreatedAt());
                        n.put("title", m.getCharacterId() == null ? "记忆里程碑（项目）" : "记忆里程碑（" + charName(m.getCharacterId()) + "）");
                        n.put("text", m.getContent());
                        n.put("source", "dialog");
                        n.put("characterId", m.getCharacterId());
                        n.put("characterName", m.getCharacterId() == null ? null : charName(m.getCharacterId()));
                        nodes.add(n);
                    });
        }

        // 时间倒序
        nodes.sort((a, b) -> {
            LocalDateTime ta = (LocalDateTime) a.get("time");
            LocalDateTime tb = (LocalDateTime) b.get("time");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        // vP5-7.11：统一为每个节点附加「项目世界时间」（到分，含历法前缀）——事件/行动/记忆都带现实+世界双时间
        String calendar = worldCalendarName(projectId);
        for (Map<String, Object> n : nodes) {
            LocalDateTime t = (LocalDateTime) n.get("time");
            n.put("calendarName", calendar);
            n.put("gameTime", worldTimeTextOf(projectId, t, calendar));
        }
        return nodes;
    }

    /**
     * 把真实时刻换算为项目世界时间文本（到分）。
     * <p>按项目时钟（锚点/速率/起始游戏时刻）把节点真实时间映射为游戏秒数，再格式化为
     * 「XX历 YYYY年MM月DD日 HH时MM分」；无时钟/无时间返回 null。</p>
     *
     * @param projectId 项目 ID
     * @param realTime  节点真实时间
     * @param calendar  历法名
     * @return 世界时间文本（到分）；不可换算返回 null
     */
    private String worldTimeTextOf(Long projectId, LocalDateTime realTime, String calendar) {
        if (realTime == null) {
            return null;
        }
        ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
        if (clock == null) {
            return null;
        }
        long startGameHour = clock.getWorldStartGameHour() == null ? 0L : clock.getWorldStartGameHour();
        int rate = clock.getRate() == null ? 24 : clock.getRate();
        long gameSecond = WorldClockService.gameSecondOf(realTime, clock.getWorldStartAt(), startGameHour, rate);
        return WorldClockService.formatWorldTimeMinute(gameSecond, calendar);
    }

    /**
     * 项目历法名（世界观名+历，缺省「世界历」）。
     *
     * @param projectId 项目 ID
     * @return 历法名
     */
    private String worldCalendarName(Long projectId) {
        return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getName() == null || s.getName().isBlank() ? "世界历" : s.getName().trim() + "历")
                .orElse("世界历");
    }

    /**
     * 手动新增事件（持久化到 actor_event，source=manual）。
     *
     * @param projectId 项目 ID
     * @param body      入参：{title?, content 必填, kind?, characterId?}
     * @return 新增的事件视图
     */
    @Transactional
    public Map<String, Object> createEvent(Long projectId, Map<String, Object> body) {
        requireProject(projectId);
        String content = body == null ? null : str(body.get("content"));
        if (content == null || content.isBlank()) {
            throw new BizException(400, "事件内容不能为空");
        }
        ActorEvent event = new ActorEvent();
        event.setProjectId(projectId);
        event.setKind(str(body.get("kind")) == null ? "manual" : str(body.get("kind")));
        event.setTitle(str(body.get("title")));
        event.setContent(content.trim());
        event.setCharacterId(asLong(body.get("characterId")));
        event.setSource("manual");
        event = eventRepository.save(event);
        log.info("[时间线] 手动新增事件：项目={} 事件={}", projectId, event.getId());
        return eventVO(event);
    }

    /**
     * AI 从世界观/项目概况识别生成事件（source=ai）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @return 生成的事件视图
     */
    @Transactional
    public Map<String, Object> aiGenerateEvent(Long userId, Long projectId) {
        requireProject(projectId);
        String worldSetting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getFreeText()).orElse("");
        String context = buildProjectContext(projectId);
        String prompt = promptService.buildWorldEventPrompt(userId, projectId, worldSetting, context);
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), 0.8, EVENT_MAX_TOKENS, true);
        long startMs = System.currentTimeMillis();
        AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "dialog",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - startMs));

        String json = JsonUtil.extractJson(result.content());
        if (json == null) {
            throw new BizException(400, "AI 事件生成失败：输出无法解析，请重试或手动新增");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("世界事件");
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                throw new BizException(400, "AI 生成的事件内容为空");
            }
            ActorEvent event = new ActorEvent();
            event.setProjectId(projectId);
            event.setKind("event");
            event.setTitle(title);
            event.setContent(content.trim());
            event.setSource("ai");
            event = eventRepository.save(event);
            log.info("[时间线] AI 生成事件：项目={} 事件={}", projectId, event.getId());
            return eventVO(event);
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "AI 事件生成失败：输出无法解析，请重试或手动新增");
        }
    }

    /**
     * 事件实体 → 视图 Map。
     *
     * @param e 事件实体
     * @return 视图 Map
     */
    private Map<String, Object> eventVO(ActorEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("projectId", e.getProjectId());
        m.put("kind", e.getKind());
        m.put("title", e.getTitle());
        m.put("content", e.getContent());
        m.put("characterId", e.getCharacterId());
        m.put("characterName", e.getCharacterId() == null ? null : charName(e.getCharacterId()));
        m.put("sceneId", e.getSceneId());
        m.put("evolutionId", e.getEvolutionId());
        m.put("source", e.getSource());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    /**
     * 组装项目概况（AI 事件识别输入：角色/最近事件/会话数）。
     *
     * @param projectId 项目 ID
     * @return 概况文本
     */
    private String buildProjectContext(Long projectId) {
        StringBuilder sb = new StringBuilder();
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        sb.append("角色（").append(chars.size()).append("）：\n");
        chars.forEach(c -> sb.append("- ").append(c.getName())
                .append(c.getTitle() == null ? "" : "（" + c.getTitle() + "）")
                .append("\n"));
        List<ActorEvent> events = eventRepository.findByProjectIdOrderByIdDesc(projectId);
        if (!events.isEmpty()) {
            sb.append("最近事件（").append(Math.min(events.size(), 5)).append(" 条）：\n");
            events.stream().limit(5).forEach(e ->
                    sb.append("- ").append(e.getTitle() == null ? "事件" : e.getTitle()).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 角色名解析。
     *
     * @param characterId 角色 ID
     * @return 角色名
     */
    private String charName(Long characterId) {
        if (characterId == null) return null;
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * 从 action_json 提取字段文本。
     *
     * @param actionJson action_json 文本
     * @param field      字段名（action/reason）
     * @return 字段文本（解析失败返回空串）
     */
    private String actionText(String actionJson, String field) {
        if (actionJson == null || actionJson.isBlank()) return "";
        try {
            return objectMapper.readTree(actionJson).path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 时间范围判断。
     *
     * @param t     时间
     * @param start 起始（可空）
     * @param end   结束（可空）
     * @return 是否在范围内
     */
    private boolean inRange(LocalDateTime t, LocalDateTime start, LocalDateTime end) {
        if (t == null) return true;
        return (start == null || !t.isBefore(start)) && (end == null || !t.isAfter(end));
    }

    /**
     * 解析日期范围。
     *
     * @param raw     原始日期（yyyy-MM-dd）
     * @param isStart 是否为起始
     * @return 时间（可空）
     */
    private LocalDateTime parseDate(String raw, boolean isStart) {
        if (raw == null || raw.isBlank()) return null;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(raw.trim());
            return isStart ? d.atStartOfDay() : d.atStartOfDay().plusDays(1).minusNanos(1);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验项目归属当前用户。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 取值辅助。
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Long 取值辅助。
     */
    private Long asLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
