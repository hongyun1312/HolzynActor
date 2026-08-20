package com.holzyn.actor.domain.action.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.action.entity.ActorActionLog;
import com.holzyn.actor.domain.action.entity.ActorActionPlan;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.action.repository.ActorActionLogRepository;
import com.holzyn.actor.domain.action.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterCardRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.domain.character.service.CharacterService;
import com.holzyn.actor.domain.character.service.PromptService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 行动引擎（A-C5 核心，P2 阶段二，P4 世界模拟 scheduled 驱动基础）。
 * <p>职责：承载行动决策的生成与模拟执行——四种触发源（manual / after_dialog / scheduled / event）；
 * 生成路径：AI（action_gen 模板 + action_decision Schema 校验 + 重试≤2）+ 程序化兜底
 * （AI 不可用或校验失败时基于角色卡/规则生成合理决策，保证功能可用）；
 * 模拟执行：更新角色 current_activity/location 状态 + 写 actor_action_log 时间线节点 + SSE 广播。
 * 自动评估（after_dialog / event）受「importance≥3 + 总开关」成本控制。</p>
 * <p>所属模块：service/action（行动子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionEngine {

    /** 行动决策 JSON Schema 允许的 type 枚举 */
    private static final List<String> ACTION_TYPES = List.of(
            "move", "interact", "speak", "trade", "fight", "flee", "help", "schedule", "rest", "custom");

    /** 行动生成最大输出 token */
    private static final int ACTION_MAX_TOKENS = 512;

    /** 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 字段最大长度（角色状态持久化） */
    private static final int FIELD_MAX = 255;

    /** 异步评估线程池（虚拟线程，避免阻塞对话/事件请求线程） */
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterCardRepository cardRepository;
    private final ActorActionPlanRepository planRepository;
    private final ActorActionLogRepository logRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final CharacterService characterService;
    private final AiProviderRouter aiProviderRouter;
    private final PromptService promptService;
    private final UsageLogService usageLogService;
    private final ActionSseHub actionSseHub;
    private final ObjectMapper objectMapper;

    /** 对话/事件后自动评估行动的总开关（成本控制） */
    @Value("${holzyn.actor.action.auto-evaluate:true}")
    private boolean autoEvaluate;

    /**
     * 手动/显式触发行动：生成决策 → 计划 → （立即执行或定时到期执行）。
     *
     * @param characterId    角色 ID
     * @param userId         归属用户 ID
     * @param triggerType    触发源（manual/scheduled/event/after_dialog）
     * @param conversationId 触发会话 ID（可空）
     * @param reason         触发理由（可空，如手动触发说明）
     * @param plannedTime    计划执行时间（未来时间则落 scheduled 计划，可空）
     * @param situation      当前情境描述（可空，缺省由服务组装）
     * @return 创建的行动计划（若立即执行则为 done）
     */
    @Transactional
    public ActorActionPlan trigger(Long characterId, Long userId, String triggerType, Long conversationId,
                                   String reason, LocalDateTime plannedTime, String situation) {
        // 归属校验用显式 userId：本方法会被世界模拟定时线程与 after_dialog/event 异步线程调用
        // （均无 SecurityContext），默认 requireOwned(id) 会回退演示用户导致真实用户角色误判无权访问
        ActorCharacter ch = characterService.requireOwned(characterId, userId);
        String situationText = situation == null || situation.isBlank() ? buildSituation(ch) : situation;
        // 生成决策（AI + 程序化兜底）
        Map<String, Object> decision = generateDecision(ch, userId, situationText);

        // 计划时间：显式传参优先；AI 决策带 plannedTime 字段（scheduled 触发源）纳入
        LocalDateTime planTime = plannedTime;
        if (planTime == null) {
            Object scheduled = decision.get("plannedTime");
            if (scheduled != null) {
                planTime = parseTime(String.valueOf(scheduled));
            }
        }

        ActorActionPlan plan = new ActorActionPlan();
        plan.setCharacterId(characterId);
        plan.setConversationId(conversationId);
        plan.setTriggerType(triggerType == null ? "manual" : triggerType);
        plan.setActionJson(toJson(decision));
        plan.setPlannedTime(planTime);
        planRepository.save(plan);

        // 未来计划时间 → 保持 planned，由定时任务到期执行；否则立即模拟执行
        if (planTime != null && planTime.isAfter(LocalDateTime.now())) {
            return plan;
        }
        execute(plan);
        return plan;
    }

    /**
     * 执行行动计划（scheduled 定时任务与立即触发共用）。
     *
     * @param plan 行动计划（planned/executing）
     */
    @Transactional
    public void execute(ActorActionPlan plan) {
        plan.setStatus("executing");
        plan.setExecutedAt(LocalDateTime.now());
        planRepository.save(plan);
        try {
            JsonNode decision = objectMapper.readTree(plan.getActionJson());
            String action = decision.path("action").asText("");
            String target = decision.path("target").asText("");
            String type = decision.path("type").asText("custom");
            String reason = decision.path("reason").asText("");

            // 更新角色状态：current_activity = 动作描述；location = 目标（优先 params.move.to）
            ActorCharacter ch = characterRepository.findById(plan.getCharacterId()).orElse(null);
            if (ch != null) {
                ch.setCurrentActivity(truncate(action));
                String loc = target;
                JsonNode moveTo = decision.path("params").path("move").path("to");
                if (moveTo != null && !moveTo.isMissingNode() && !moveTo.asText("").isBlank()) {
                    loc = moveTo.asText();
                }
                if (loc != null && !loc.isBlank()) {
                    ch.setLocation(truncate(loc));
                }
                characterRepository.save(ch);
            }

            // 写行动日志（时间线节点）
            ActorActionLog log = new ActorActionLog();
            log.setCharacterId(plan.getCharacterId());
            log.setPlanId(plan.getId());
            log.setSummary(truncate(action));
            log.setDetail(reason + (type == null || type.isBlank() ? "" : "（类型：" + type + "）"));
            log.setLogTime(LocalDateTime.now());
            logRepository.save(log);

            plan.setStatus("done");
            planRepository.save(plan);

            // SSE 广播行动事件（时间线实时刷新）
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "action");
            event.put("planId", plan.getId());
            event.put("characterId", plan.getCharacterId());
            event.put("action", action);
            event.put("target", target);
            event.put("reason", reason);
            event.put("triggerType", plan.getTriggerType());
            event.put("status", "done");
            event.put("time", LocalDateTime.now().toString());
            actionSseHub.broadcast(event);
        } catch (Exception e) {
            log.warn("行动执行失败: plan={} : {}", plan.getId(), e.getMessage());
            plan.setStatus("cancelled");
            planRepository.save(plan);
        }
    }

    /**
     * 对话完成后自动评估（异步）：对高重要度成员（importance≥3）触发 after_dialog 行动。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户 ID
     */
    public void evaluateAfterDialogAsync(Long conversationId, Long userId) {
        if (!autoEvaluate) {
            return;
        }
        asyncExecutor.execute(() -> {
            try {
                evaluateMembers(conversationId, userId, "after_dialog");
            } catch (Exception e) {
                log.warn("after_dialog 行动评估失败: conv={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 世界事件后自动触发（异步）：对在场高重要度角色触发 event 行动。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户 ID
     */
    public void triggerForEventAsync(Long conversationId, Long userId) {
        if (!autoEvaluate) {
            return;
        }
        asyncExecutor.execute(() -> {
            try {
                evaluateMembers(conversationId, userId, "event");
            } catch (Exception e) {
                log.warn("event 行动评估失败: conv={} : {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 对会话在场的高重要度角色评估行动（after_dialog / event 共用）。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户 ID
     * @param triggerType    触发源
     */
    private void evaluateMembers(Long conversationId, Long userId, String triggerType) {
        List<ActorConversationMember> members = memberRepository.findByConversationId(conversationId);
        for (ActorConversationMember m : members) {
            ActorCharacter ch = characterRepository.findById(m.getCharacterId()).orElse(null);
            if (ch == null || Integer.valueOf(0).equals(ch.getDeleted())) {
                continue;
            }
            // 成本控制：仅高重要度角色自动评估
            if (ch.getImportance() == null || ch.getImportance() < 3) {
                continue;
            }
            try {
                trigger(ch.getId(), userId, triggerType, conversationId, null, null, null);
            } catch (Exception e) {
                log.warn("角色行动评估失败: char={} : {}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * 生成行动决策：AI（json_object + Schema 校验 + 重试≤2），失败则程序化兜底。
     *
     * @param ch          角色实体
     * @param userId      归属用户 ID
     * @param situation   当前情境
     * @return 决策 Map（type/action/target/params/reason/urgency/duration）
     */
    private Map<String, Object> generateDecision(ActorCharacter ch, Long userId, String situation) {
        String personaSummary = buildPersonaSummary(ch);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String prompt = promptService.buildActionGenPrompt(userId, ch.getProjectId(), personaSummary, situation);
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.7, ACTION_MAX_TOKENS, true);
                long startMs = System.currentTimeMillis();
                AiChatResult result = aiProviderRouter.chatCompletion(userId, ch.getProjectId(), null, req);
                usageLogService.record(userId, ch.getProjectId(), ch.getId(), result.providerId(), result.model(),
                        "action", result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - startMs));
                String json = JsonUtil.extractJson(result.content());
                if (json == null) {
                    throw new BizException("AI 未返回有效 JSON");
                }
                JsonNode node = objectMapper.readTree(json);
                Map<String, Object> decision = validateDecision(node, objectMapper);
                if (decision == null) {
                    throw new BizException("行动决策不符合 Schema 要求");
                }
                return decision;
            } catch (Exception e) {
                lastError = e;
                log.warn("行动决策生成第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("行动决策 AI 生成失败（{}），使用程序化兜底", lastError == null ? "未知" : lastError.getMessage());
        return programmaticDecision(ch);
    }

    /**
     * 程序化兜底：基于角色卡行为模式 / 重要度 / 当前时间生成合理决策（AI 不可用时保证功能可用）。
     *
     * @param ch 角色实体
     * @return 决策 Map
     */
    private Map<String, Object> programmaticDecision(ActorCharacter ch) {
        return programmaticFallback(ch.getName(), ch.getImportance(), firstBehavior(ch));
    }

    /**
     * 程序化兜底核心（静态可测）：基于角色名/重要度/行为模式生成合理决策。
     *
     * @param name       角色名
     * @param importance 重要度（可空）
     * @param behavior   行为模式（可空）
     * @return 决策 Map
     */
    public static Map<String, Object> programmaticFallback(String name, Integer importance, String behavior) {
        int urgency = Math.max(1, Math.min(5, importance == null ? 3 : importance));
        String action = behavior != null && !behavior.isBlank()
                ? (name + "按日常习惯：" + behavior)
                : (name + "开始了今天的日常活动");
        String target = "驻地附近";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("move", Map.of("to", target));
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "schedule");
        decision.put("action", action);
        decision.put("target", target);
        decision.put("params", params);
        decision.put("reason", "程序化兜底：当前 AI 调用不可用，基于角色人设与规则生成"
                + (importance == null ? "" : "（重要度 " + importance + "）"));
        decision.put("urgency", urgency);
        decision.put("duration", 30);
        return decision;
    }

    /**
     * 从角色卡提取第一条行为模式（程序化兜底依据）。
     *
     * @param ch 角色实体
     * @return 行为模式文本（无则 null）
     */
    private String firstBehavior(ActorCharacter ch) {
        String persona = cardRepository.findTopByCharacterIdOrderByVersionDesc(ch.getId())
                .map(ActorCharacterCard::getPersonaJson).orElse(null);
        if (persona == null || persona.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(persona).path("behaviorPatterns");
            if (node.isArray() && node.size() > 0) {
                return node.get(0).asText("");
            }
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return null;
    }

    /**
     * 校验 action_decision Schema：必填 type/action/target/reason/urgency 且 type 在枚举内。
     *
     * @param node 决策 JSON 节点
     * @return 合法时返回决策 Map，否则 null
     */
    public static Map<String, Object> validateDecision(JsonNode node, ObjectMapper mapper) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = node.path("type").asText("");
        String action = node.path("action").asText("");
        String target = node.path("target").asText("");
        String reason = node.path("reason").asText("");
        if (type.isBlank() || action.isBlank() || target.isBlank() || reason.isBlank()) {
            return null;
        }
        if (!ACTION_TYPES.contains(type)) {
            return null;
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", type);
        decision.put("action", action);
        decision.put("target", target);
        decision.put("reason", reason);
        decision.put("urgency", node.path("urgency").asInt(3));
        decision.put("duration", node.path("duration").asInt(30));
        JsonNode params = node.path("params");
        if (params.isObject()) {
            decision.put("params", mapper.convertValue(params, Map.class));
        } else {
            decision.put("params", Map.of());
        }
        // 可选计划时间字段（scheduled 触发源使用）
        if (node.has("plannedTime") || node.has("scheduledAt")) {
            String raw = node.has("plannedTime") ? node.path("plannedTime").asText("") : node.path("scheduledAt").asText("");
            if (!raw.isBlank()) {
                decision.put("plannedTime", raw);
            }
        }
        return decision;
    }

    /**
     * 组装角色人设摘要（行动生成 Prompt 输入）。
     *
     * @param ch 角色实体
     * @return 人设摘要
     */
    private String buildPersonaSummary(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(ch.getName());
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append("（").append(ch.getTitle()).append("）");
        }
        sb.append("；重要度：").append(ch.getImportance() == null ? 1 : ch.getImportance());
        if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
            sb.append("；当前在做：").append(ch.getCurrentActivity());
        }
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("；位置：").append(ch.getLocation());
        }
        String persona = cardRepository.findTopByCharacterIdOrderByVersionDesc(ch.getId())
                .map(ActorCharacterCard::getPersonaJson).orElse(null);
        if (persona != null && !persona.isBlank()) {
            try {
                JsonNode p = objectMapper.readTree(persona);
                List<String> bits = new ArrayList<>();
                appendBits(bits, p.path("personality").path("traits"), 3);
                appendBits(bits, p.path("background").path("goals"), 3);
                appendBits(bits, p.path("behaviorPatterns"), 3);
                if (!bits.isEmpty()) {
                    sb.append("；人设：").append(String.join("、", bits));
                }
            } catch (Exception ignored) {
                // 忽略角色卡解析失败
            }
        }
        return sb.toString();
    }

    /**
     * 组装当前情境（角色状态 + 时间）。
     *
     * @param ch 角色实体
     * @return 情境文本
     */
    private String buildSituation(ActorCharacter ch) {
        return "当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "；角色当前状态：" + (ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                ? "空闲" : ch.getCurrentActivity())
                + (ch.getLocation() == null || ch.getLocation().isBlank() ? "" : "；位置：" + ch.getLocation());
    }

    /**
     * 追加数组字段到摘要片段。
     *
     * @param bits 片段集合
     * @param node 数组节点
     * @param max  最大追加条数
     */
    private void appendBits(List<String> bits, JsonNode node, int max) {
        if (node.isArray()) {
            node.forEach(n -> {
                String v = n.asText("");
                if (!v.isBlank() && bits.size() < max) {
                    bits.add(v);
                }
            });
        }
    }

    /**
     * 字符串截断到 255 字符（角色状态字段长度约束）。
     *
     * @param s 原始字符串
     * @return 截断结果（空则返回 null）
     */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > FIELD_MAX ? t.substring(0, FIELD_MAX) : t;
    }

    /**
     * 决策 Map 序列化为 JSON 字符串。
     *
     * @param decision 决策 Map
     * @return JSON 文本
     */
    private String toJson(Map<String, Object> decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (Exception e) {
            throw new BizException(500, "行动决策序列化失败");
        }
    }

    /**
     * 解析计划时间字符串（支持 yyyy-MM-dd HH:mm 或 ISO 格式）。
     *
     * @param raw 原始字符串
     * @return 时间；解析失败返回 null
     */
    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}