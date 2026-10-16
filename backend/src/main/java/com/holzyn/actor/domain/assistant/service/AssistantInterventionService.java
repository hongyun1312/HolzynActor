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

package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.roleplay.RoleplayService;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantIntervention;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantInterventionRepository;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.TimelineService;
import com.holzyn.actor.domain.world.service.WeatherSwitchService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 助手世界干预服务（能力域 F，M4）——「解析计划 → 确认拦截 → 复用沙盘既有服务执行」。
 * <p>职责：<ul>
 * <li>{@link #plan}：把模型声明的干预操作（talk 插话 / action 触发行动 / event 注入事件 /
 * clock 控制时间 / weather 切换天气）校验归一为执行计划，按会话确认粒度
 * （strict 严格 / standard 标准 / trust 信任）决定<b>待确认</b>（落 actor_assistant_intervention
 * 表）或<b>立即执行</b>；</li>
 * <li>{@link #confirm}/{@link #cancel}/{@link #revise}：确认/取消/修改后执行待确认计划；确认与
 * 修改后执行经既有沙盘服务执行（{@link RoleplayService}/{@link AgentRuntime}/{@link TimelineService}/
 * {@link WorldClockService}/{@link WeatherSwitchService}），与沙盘手动操作同一批代码路径；</li>
 * <li>标准模式：低风险操作（插话/行动/天气）首次确认后加入会话放行集，后续同类自动执行；
 * 高风险操作（注入事件/控制时间）每次确认。</li>
 * </ul></p>
 * <p><b>架构纪律</b>：本服务方法<b>不带 {@code @Transactional}</b>——执行路径会触发
 * AI（插话回复/行动决策），跨 AI 持锁违反既有不变量；落库由仓库自身短事务与
 * {@link AssistantSessionService} 承担。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantInterventionService {

    /** Jackson 3 序列化器（参数/结果 JSON 存取）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 干预计划仓库。 */
    private final ActorAssistantInterventionRepository interventionRepository;

    /** 会话服务（确认粒度/放行集）。 */
    private final AssistantSessionService sessionService;

    /** 项目校验。 */
    private final ActorProjectRepository projectRepository;

    /** 角色档案（存在性/名称）。 */
    private final ActorCharacterRepository characterRepository;

    /** NPC 类型解析（触发行动仅特殊角色）。 */
    private final NpcTypeResolver npcTypeResolver;

    /** 插话：会话 find-or-create + 消息发送。 */
    private final ConversationService conversationService;
    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository conversationMemberRepository;
    private final RoleplayService roleplayService;

    /** 触发行动（特殊角色）。 */
    private final AgentRuntime agentRuntime;

    /** 注入事件。 */
    private final TimelineService timelineService;

    /** 时间控制。 */
    private final WorldClockService worldClockService;
    private final WorldEngine worldEngine;

    /** 天气切换。 */
    private final WeatherSwitchService weatherSwitchService;

    /** 风险等级。 */
    public static final String RISK_LOW = "low";
    public static final String RISK_HIGH = "high";

    /**
     * 计划结果。
     *
     * @param status     pending 待确认 / executed 已执行 / failed 失败
     * @param planId     待确认时的计划 ID
     * @param operation  操作类型
     * @param detail     操作详情（人类可读）
     * @param impact     影响范围
     * @param risk       风险等级（low/high）
     * @param resultText 已执行时的结果文本
     * @param result     已执行时的结构化结果（可空）
     */
    public record PlanResult(String status, Long planId, String operation, String detail, String impact,
                             String risk, String resultText, Map<String, Object> result) {
        /** 是否需要用户确认。 */
        public boolean needsConfirm() {
            return "pending".equals(status);
        }
    }

    /**
     * 确认/取消结果。
     *
     * @param status     executed / cancelled / failed
     * @param operation  操作类型
     * @param detail     操作详情
     * @param resultText 结果文本
     * @param result     结构化结果（可空）
     */
    public record ConfirmResult(String status, String operation, String detail, String resultText,
                                Map<String, Object> result) {
    }

    /**
     * 归一化后的操作计划（校验 + 参数归一 + 详情/影响/风险）。
     *
     * @param params 归一后的参数
     * @param detail 操作详情（人类可读）
     * @param impact 影响范围
     * @param risk   风险等级（low/high）
     */
    private record Norm(Map<String, Object> params, String detail, String impact, String risk) {
    }

    /**
     * 生成干预计划（按确认粒度决定待确认或立即执行）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID（确认粒度来源）
     * @param userId    用户 ID
     * @param operation 操作类型（talk/action/event/clock/weather）
     * @param params    操作参数
     *
     * @return 计划结果
     */
    public PlanResult plan(Long projectId, Long sessionId, Long userId, String operation,
                           Map<String, Object> params) {
        requireProject(projectId);
        String op = normalizeOp(operation);
        if (op == null) {
            return fail("unknown", "不支持的操作类型：" + nullTo(operation)
                    + "（可选：talk 插话 / action 触发行动 / event 注入事件 / clock 控制时间 / weather 切换天气）");
        }
        // 校验 + 归一参数 + 详情/影响/风险
        Norm norm;
        try {
            norm = normalize(op, params, projectId);
        } catch (BizException e) {
            return fail(op, e.getMessage());
        }
        // 确认粒度判定
        String confirmMode = sessionService.confirmModeOf(projectId, sessionId);
        boolean needsConfirm = "strict".equals(confirmMode)
                || ("standard".equals(confirmMode) && (RISK_HIGH.equals(norm.risk())
                || !sessionService.isApprovedOp(projectId, sessionId, op)));
        if (needsConfirm) {
            ActorAssistantIntervention p = new ActorAssistantIntervention();
            p.setProjectId(projectId);
            p.setSessionId(sessionId);
            p.setUserId(userId);
            p.setOperation(op);
            p.setParamsJson(json(norm.params()));
            p.setDetail(norm.detail());
            p.setImpact(norm.impact());
            p.setRisk(norm.risk());
            p.setStatus("pending");
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            ActorAssistantIntervention saved = interventionRepository.save(p);
            log.info("[助手·干预] 计划待确认 project={} session={} plan={} op={} risk={}",
                    projectId, sessionId, saved.getId(), op, norm.risk());
            return new PlanResult("pending", saved.getId(), op, norm.detail(), norm.impact(), norm.risk(), null, null);
        }
        // 立即执行（trust 模式 / 标准模式已放行的低风险）——同样全量留痕（executed/failed）
        PlanResult r = execute(projectId, sessionId, userId, op, norm.params(), norm.detail(), norm.impact(), norm.risk());
        persistExecuted(projectId, sessionId, userId, op, norm.params(), norm.detail(), norm.impact(), norm.risk(), r);
        return r;
    }

    /**
     * 确认并执行待确认计划。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param planId    计划 ID
     *
     * @return 执行结果
     */
    public ConfirmResult confirm(Long projectId, Long sessionId, Long planId) {
        ActorAssistantIntervention p = requirePending(projectId, sessionId, planId);
        PlanResult r = execute(projectId, sessionId, p.getUserId(), p.getOperation(),
                parseParams(p.getParamsJson()), p.getDetail(), p.getImpact(), p.getRisk());
        p.setStatus("executed".equals(r.status()) ? "executed" : "failed");
        p.setResultJson(json(r.result() == null ? Map.of("text", nullTo(r.resultText())) : r.result()));
        p.setUpdatedAt(LocalDateTime.now());
        interventionRepository.save(p);
        // 标准模式：低风险首次确认后放行
        if ("executed".equals(r.status()) && RISK_LOW.equals(p.getRisk())
                && "standard".equals(sessionService.confirmModeOf(projectId, sessionId))) {
            sessionService.markApprovedOp(projectId, sessionId, p.getOperation());
        }
        return new ConfirmResult(r.status(), p.getOperation(), p.getDetail(), r.resultText(), r.result());
    }

    /**
     * 修改后执行（M5，设计 §6.2「执行按钮：确认执行 / 取消 / 修改后执行」）。
     * <p>用户修改待确认计划的参数（同一操作类型），重新校验归一后直接执行——修改即确认：
     * <ul>
     * <li>参数非法 → 返回 failed（计划<b>保持 pending</b>，用户可继续修改重试）；</li>
     * <li>参数合法 → 更新计划 params/detail/impact/risk → 执行 → 标记 executed/failed 全量留痕；
     * 标准模式低风险同样加入放行集（用户已显式确认）。</li>
     * </ul></p>
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param planId    待确认计划 ID
     * @param params    修改后的操作参数（完整覆盖）
     *
     * @return 执行结果
     */
    public ConfirmResult revise(Long projectId, Long sessionId, Long planId, Map<String, Object> params) {
        ActorAssistantIntervention p = requirePending(projectId, sessionId, planId);
        String op = p.getOperation();
        Norm norm;
        try {
            norm = normalize(op, params, projectId);
        } catch (BizException e) {
            // 修改不合法：计划保持 pending（前端提示后用户可继续修改重试）
            log.info("[助手·干预] 修改后执行参数不合法 project={} session={} plan={} op={} : {}",
                    projectId, sessionId, planId, op, e.getMessage());
            return new ConfirmResult("failed", op, p.getDetail(), e.getMessage(), Map.of("error", e.getMessage()));
        }
        p.setParamsJson(json(norm.params()));
        p.setDetail(norm.detail());
        p.setImpact(norm.impact());
        p.setRisk(norm.risk());
        p.setUpdatedAt(LocalDateTime.now());
        interventionRepository.save(p);
        PlanResult r = execute(projectId, sessionId, p.getUserId(), op, norm.params(),
                norm.detail(), norm.impact(), norm.risk());
        p.setStatus("executed".equals(r.status()) ? "executed" : "failed");
        p.setResultJson(json(r.result() == null ? Map.of("text", nullTo(r.resultText())) : r.result()));
        p.setUpdatedAt(LocalDateTime.now());
        interventionRepository.save(p);
        log.info("[助手·干预] 修改后执行 project={} session={} plan={} op={} → {}",
                projectId, sessionId, planId, op, r.status());
        // 标准模式：低风险修改后执行（用户已显式确认）同样放行
        if ("executed".equals(r.status()) && RISK_LOW.equals(norm.risk())
                && "standard".equals(sessionService.confirmModeOf(projectId, sessionId))) {
            sessionService.markApprovedOp(projectId, sessionId, op);
        }
        return new ConfirmResult(r.status(), op, norm.detail(), r.resultText(), r.result());
    }

    /**
     * 取消待确认计划。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param planId    计划 ID
     *
     * @return 取消结果
     */
    public ConfirmResult cancel(Long projectId, Long sessionId, Long planId) {
        ActorAssistantIntervention p = requirePending(projectId, sessionId, planId);
        p.setStatus("cancelled");
        p.setUpdatedAt(LocalDateTime.now());
        interventionRepository.save(p);
        log.info("[助手·干预] 计划已取消 project={} session={} plan={}", projectId, sessionId, planId);
        return new ConfirmResult("cancelled", p.getOperation(), p.getDetail(), "已取消：" + p.getDetail(), null);
    }

    /**
     * 某会话最近一条待确认计划（ChatService 组装 confirm SSE 载荷）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 最近待确认计划（可空）
     */
    public Optional<ActorAssistantIntervention> latestPending(Long projectId, Long sessionId) {
        return interventionRepository.findTopBySessionIdAndStatusOrderByIdDesc(sessionId, "pending")
                .filter(p -> projectId.equals(p.getProjectId()));
    }

    // ==================== 内部：执行 ====================

    /** 立即执行路径同样全量留痕（executed/failed；与确认路径同一张表，防执行无痕）。 */
    private void persistExecuted(Long projectId, Long sessionId, Long userId, String op,
                                 Map<String, Object> params, String detail, String impact, String risk,
                                 PlanResult r) {
        try {
            ActorAssistantIntervention p = new ActorAssistantIntervention();
            p.setProjectId(projectId);
            p.setSessionId(sessionId);
            p.setUserId(userId);
            p.setOperation(op);
            p.setParamsJson(json(params));
            p.setDetail(detail);
            p.setImpact(impact);
            p.setRisk(risk);
            p.setStatus("executed".equals(r.status()) ? "executed" : "failed");
            p.setResultJson(json(r.result() == null ? Map.of("text", nullTo(r.resultText())) : r.result()));
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            interventionRepository.save(p);
        } catch (Exception e) {
            log.warn("[助手·干预] 立即执行留痕失败（不影响已执行结果）: {}", e.getMessage());
        }
    }

    /** 立即执行（或确认后执行）。 */
    private PlanResult execute(Long projectId, Long sessionId, Long userId, String op,
                               Map<String, Object> params, String detail, String impact, String risk) {
        try {
            Map<String, Object> result = executeOp(projectId, userId, op, params);
            String text = resultText(op, params, result);
            log.info("[助手·干预] 执行成功 project={} session={} op={} risk={}",
                    projectId, sessionId, op, risk);
            return new PlanResult("executed", null, op, detail, impact, risk, text, result);
        } catch (Exception e) {
            log.warn("[助手·干预] 执行失败 project={} session={} op={} : {}", projectId, sessionId, op,
                    e.getMessage());
            String msg = e instanceof BizException be && be.getMessage() != null
                    ? be.getMessage() : (e.getMessage() == null ? "执行失败" : e.getMessage());
            return new PlanResult("failed", null, op, detail, impact, risk, msg, Map.of("error", msg));
        }
    }

    /** 实际执行（复用沙盘既有服务，与 SandboxController 同一批代码路径）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeOp(Long projectId, Long userId, String op, Map<String, Object> params) {
        return switch (op) {
            case "talk" -> {
                String npcRef = str(params, "npcRef");
                String content = str(params, "content");
                ConversationVO conv = ensureSingleConversation(projectId, npcRef);
                Map<String, Object> sent = roleplayService.sendMessage(conv.id(), content.trim(),
                        NpcRefs.idOf(npcRef));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("conversationId", conv.id());
                out.put("mode", conv.mode());
                out.put("message", "插话已写入，回复经沙盘流式推送（npc:talk）");
                if (sent != null) {
                    out.putAll(sent);
                }
                yield out;
            }
            case "action" -> {
                String npcRef = str(params, "npcRef");
                String reason = str(params, "reason");
                Long characterId = NpcRefs.idOf(npcRef);
                requireNpc(projectId, npcRef);
                ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
                ActorActionPlan plan = agentRuntime.triggerAction(characterId, userId, "manual",
                        null, reason, null, null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("planId", plan.getId());
                out.put("characterId", plan.getCharacterId());
                out.put("characterName", ch == null ? npcRef : ch.getName());
                out.put("status", plan.getStatus());
                out.put("triggerType", plan.getTriggerType());
                out.put("reason", reason);
                out.put("message", "行动已触发（思考决策 + 计划持久化，执行结果进入世界事件流）");
                yield out;
            }
            case "event" -> timelineService.createEvent(projectId, params);
            case "clock" -> {
                boolean advance = Boolean.TRUE.equals(params.get("advance"));
                if (advance) {
                    worldClockService.getClock(projectId, userId);
                    yield worldEngine.advanceProject(projectId);
                }
                WorldClockDTO dto = new WorldClockDTO(
                        params.get("rate") == null ? null : Integer.parseInt(String.valueOf(params.get("rate"))),
                        params.get("paused") == null ? null : Boolean.parseBoolean(String.valueOf(params.get("paused"))),
                        params.get("worldRunning") == null ? null : Integer.parseInt(String.valueOf(params.get("worldRunning"))),
                        null, null);
                yield Map.of("clock", worldClockService.updateClock(projectId, userId, dto));
            }
            case "weather" -> {
                String condition = str(params, "condition");
                var vo = weatherSwitchService.switchWeather(projectId, condition);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("condition", vo.getCondition());
                out.put("region", vo.getRegion());
                out.put("temperature", vo.getTemperature());
                out.put("message", "天气已切换为「" + vo.getCondition() + "」");
                yield out;
            }
            default -> throw new BizException(400, "不支持的操作类型：" + op);
        };
    }

    /** 执行结果 → 人类可读文本。 */
    @SuppressWarnings("unchecked")
    private String resultText(String op, Map<String, Object> params, Map<String, Object> result) {
        return switch (op) {
            case "talk" -> "已向「" + npcNameSafe(params.get("npcRef")) + "」插话：「"
                    + nullTo(params.get("content")) + "」（回复流式推送）";
            case "action" -> "已触发「" + npcNameSafe(params.get("npcRef")) + "」行动决策（计划 #"
                    + nullTo(result.get("planId")) + "，" + nullTo(result.get("status")) + "）";
            case "event" -> "世界事件已注入（事件 #" + nullTo(result.get("id")) + "）";
            case "clock" -> {
                if (Boolean.TRUE.equals(params.get("advance"))) {
                    yield "世界已手动推进一轮";
                }
                yield "世界时间已更新（" + clockDesc(
                        params.get("rate") == null ? null : Integer.parseInt(String.valueOf(params.get("rate"))),
                        params.get("paused") == null ? null : Boolean.parseBoolean(String.valueOf(params.get("paused"))),
                        params.get("worldRunning") == null ? null : Integer.parseInt(String.valueOf(params.get("worldRunning"))))
                        + "）";
            }
            case "weather" -> "天气已切换为「" + nullTo(params.get("condition")) + "」";
            default -> "已执行";
        };
    }

    // ==================== 内部：校验 / 解析 ====================

    /** 操作类型归一（非法返回 null）。 */
    private String normalizeOp(String op) {
        if (op == null) {
            return null;
        }
        String o = op.trim().toLowerCase();
        return switch (o) {
            case "talk", "action", "event", "clock", "weather" -> o;
            default -> null;
        };
    }

    /**
     * 操作校验 + 参数归一 + 详情/影响/风险（plan 与 revise 共用；参数非法抛 {@link BizException}）。
     */
    private Norm normalize(String op, Map<String, Object> params, Long projectId) {
        Map<String, Object> normParams = new LinkedHashMap<>();
        String detail;
        String impact;
        String risk;
        switch (op) {
            case "talk" -> {
                String npcRef = requireNpc(projectId, str(params, "npcRef"));
                String content = requireText(params, "content", "插话内容");
                normParams.put("npcRef", npcRef);
                normParams.put("content", content);
                detail = "插话给「" + npcName(projectId, npcRef) + "」：" + content;
                impact = "角色 " + npcName(projectId, npcRef) + "（" + npcRef + "）";
                risk = RISK_LOW;
            }
            case "action" -> {
                String npcRef = str(params, "npcRef");
                if (npcRef == null || !npcTypeResolver.isSpecial(projectId, npcRef)) {
                    throw new BizException(400, "触发行动仅支持特殊角色（npcRef 形如 npc:{id}）");
                }
                requireNpc(projectId, npcRef);
                String reason = str(params, "reason");
                normParams.put("npcRef", npcRef);
                normParams.put("reason", reason == null ? "助手干预触发" : reason);
                detail = "触发「" + npcName(projectId, npcRef) + "」一轮行动决策（理由：" + normParams.get("reason") + "）";
                impact = "角色 " + npcName(projectId, npcRef) + "（" + npcRef + "）";
                risk = RISK_LOW;
            }
            case "event" -> {
                String content = requireText(params, "content", "事件内容");
                normParams.put("content", content);
                if (params.get("title") != null && !String.valueOf(params.get("title")).isBlank()) {
                    normParams.put("title", String.valueOf(params.get("title")));
                }
                if (params.get("kind") != null && !String.valueOf(params.get("kind")).isBlank()) {
                    normParams.put("kind", String.valueOf(params.get("kind")));
                }
                detail = "向世界注入事件：" + content;
                impact = "项目 " + projectId + " 世界（事件流/编年史）";
                risk = RISK_HIGH;
            }
            case "clock" -> {
                boolean advance = bool(params, "advance");
                Integer rate = intv(params, "rate");
                Boolean paused = boolv(params, "paused");
                Integer worldRunning = intv(params, "worldRunning");
                if (!advance && rate == null && paused == null && worldRunning == null) {
                    throw new BizException(400, "时间控制参数不能全空（advance 推进 / rate 倍速 / paused 暂停 / worldRunning 常驻）");
                }
                normParams.put("advance", advance);
                if (rate != null) {
                    normParams.put("rate", rate);
                }
                if (paused != null) {
                    normParams.put("paused", paused);
                }
                if (worldRunning != null) {
                    normParams.put("worldRunning", worldRunning);
                }
                detail = advance ? "手动推进世界一轮"
                        : "调整世界时间（" + clockDesc(rate, paused, worldRunning) + "）";
                impact = "项目 " + projectId + " 世界（时间推进）";
                risk = RISK_HIGH;
            }
            case "weather" -> {
                String condition = requireText(params, "condition", "天气条件");
                if (!WeatherSwitchService.ALLOWED_CONDITIONS.contains(condition)) {
                    throw new BizException(400, "不支持的天气条件：" + condition
                            + "（可选：" + String.join(" / ", WeatherSwitchService.ALLOWED_CONDITIONS) + "）");
                }
                normParams.put("condition", condition);
                detail = "切换天气为「" + condition + "」";
                impact = "项目 " + projectId + " 世界（全境天气）";
                risk = RISK_LOW;
            }
            default -> throw new BizException(400, "不支持的操作类型：" + op);
        }
        return new Norm(normParams, detail, impact, risk);
    }

    /** 项目校验（不存在/已删除 → 404）。 */
    private void requireProject(Long projectId) {
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }

    /** NPC 校验：npc:{id} → 档案行存在且属于项目；返回归一 npcRef。 */
    private String requireNpc(Long projectId, String npcRef) {
        if (npcRef == null || !NpcRefs.isNpcRef(npcRef.trim())) {
            throw new BizException(400, "npcRef 不能为空（npc:{id}）");
        }
        Long id = NpcRefs.idOf(npcRef.trim());
        ActorCharacter ch = characterRepository.findById(id)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "NPC 不存在或不属于该项目"));
        return NpcRefs.of(id);
    }

    /** 角色名（档案缺失回退 npcRef）。 */
    private String npcName(Long projectId, String npcRef) {
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        return ch != null && ch.getName() != null ? ch.getName() : npcRef;
    }

    /** npcRef → 角色名（解析失败回退原串）。 */
    private String npcNameSafe(Object npcRef) {
        if (npcRef == null) {
            return "角色";
        }
        String ref = String.valueOf(npcRef);
        try {
            Long id = NpcRefs.idOf(ref);
            if (id != null) {
                ActorCharacter ch = characterRepository.findById(id).orElse(null);
                if (ch != null && ch.getName() != null) {
                    return ch.getName();
                }
            }
        } catch (Exception ignored) {
            // 回退
        }
        return ref;
    }

    /** 查找或创建该 NPC 的单聊会话（插话复用；与沙盘同一口径）。 */
    private ConversationVO ensureSingleConversation(Long projectId, String npcRef) {
        String memberType = npcTypeResolver.typeOf(projectId, npcRef);
        Long npcId = NpcRefs.idOf(npcRef);
        List<ActorConversation> existing = conversationMemberRepository.findByCharacterId(npcId).stream()
                .filter(m -> memberType.equals(NpcRefs.normalizeType(m.getMemberType())))
                .map(com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember::getConversationId)
                .distinct()
                .map(conversationRepository::findById)
                .flatMap(Optional::stream)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> "single".equals(c.getMode()))
                .filter(c -> "active".equals(c.getStatus()))
                .sorted(Comparator.comparing(ActorConversation::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        if (!existing.isEmpty()) {
            return conversationService.detail(existing.get(0).getId());
        }
        return conversationService.create(projectId,
                new ConversationDTO("single", null, null, List.of(npcRef), null, null));
    }

    /** 待确认计划（隔离校验；非 pending 抛 400）。 */
    private ActorAssistantIntervention requirePending(Long projectId, Long sessionId, Long planId) {
        ActorAssistantIntervention p = interventionRepository
                .findByIdAndProjectIdAndSessionId(planId, projectId, sessionId)
                .orElseThrow(() -> new BizException(404, "干预计划不存在或不属于该会话"));
        if (!"pending".equals(p.getStatus())) {
            throw new BizException(400, "干预计划已处理（当前状态：" + p.getStatus() + "）");
        }
        return p;
    }

    /** 失败结果。 */
    private PlanResult fail(String op, String message) {
        return new PlanResult("failed", null, op, message, "—", "low", message, Map.of("error", message));
    }

    /** 时间控制描述。 */
    private String clockDesc(Integer rate, Boolean paused, Integer worldRunning) {
        List<String> parts = new ArrayList<>();
        if (Boolean.TRUE.equals(paused)) {
            parts.add("暂停推进");
        } else if (Boolean.FALSE.equals(paused)) {
            parts.add("恢复推进");
        }
        if (rate != null) {
            parts.add("倍速 " + rate);
        }
        if (worldRunning != null) {
            parts.add(worldRunning == 1 ? "开启常驻运行" : "关闭常驻运行");
        }
        return parts.isEmpty() ? "保持原值" : String.join("，", parts);
    }

    // ==================== 参数工具 ====================

    /** 必填字符串（缺失抛 400）。 */
    private String requireText(Map<String, Object> params, String key, String label) {
        String v = str(params, key);
        if (v == null) {
            throw new BizException(400, label + "不能为空");
        }
        return v;
    }

    /** Map 取字符串（null 安全）。 */
    private String str(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return null;
        }
        String s = String.valueOf(params.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    /** Map 取布尔（null 安全；缺省 false）。 */
    private boolean bool(Map<String, Object> params, String key) {
        Boolean b = boolv(params, key);
        return Boolean.TRUE.equals(b);
    }

    /** Map 取布尔对象（null 安全）。 */
    private Boolean boolv(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** Map 取整数（null/非法 → null）。 */
    private Integer intv(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 对象 → JSON 字符串（失败回退 null）。 */
    private String json(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 字符串 → Map（失败回退空 Map；供 ChatService confirm 载荷复用，M5）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object o = MAPPER.readValue(json, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** null 安全。 */
    private String nullTo(Object o) {
        return o == null ? "—" : String.valueOf(o);
    }
}
