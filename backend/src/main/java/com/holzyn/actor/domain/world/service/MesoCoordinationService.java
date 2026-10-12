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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.config.MesoProperties;
import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorMesoResponse;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorMesoDirectiveRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 中观协调服务。
 * <p>职责：中观协调编排——宏观事件 → 指令生成（档位联动）→ 成员路由 → 响应回收；对外提供
 * 「该角色当前指令上下文」：</p>
 * <ul>
 * <li><b>onMacroEvent</b>：重大宏观事件（severity≥minEventSeverity）→ 按组织×角色生成
 * 差异化指令（三通道倾向/约束/资源变化，不硬编码具体行为——个体如何响应由个体决策层
 * 消化）；<b>档位联动</b>：programmatic 全规则模板 / light·full 规则为主
 * + 重要事件（severity≥4）AI 差异化（scene=meso_directive，预算门控，失败回退规则模板）
 * / max 全部指令 AI 生成（最个性化，预算不设上限）；</li>
 * <li><b>issueRoutineDirectives</b>：每旬例行指令（维持组织存在感——组织对成员的日常期待）；</li>
 * <li><b>recordActionResponse</b>：个体决策完成后回写 actor_meso_response（谁遵从/谁违抗——
 * 按行动文本与指令三通道关键词的确定性匹配；decision_trace_id 贯通 AiCallContext）；</li>
 * <li><b>手动建组/合并/解散</b>（调试/定向冒烟用，可回滚）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesoCoordinationService {

    private final MesoProperties props;
    private final MesoGroupEngine mesoGroupEngine;
    private final MesoDirectiveRouter mesoDirectiveRouter;
    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorMesoDirectiveRepository directiveRepository;
    private final ActorMesoResponseRepository responseRepository;
    private final MacroStateService macroStateService;
    private final MacroAiService macroAiService;
    private final AiIntensityGate aiIntensityGate;
    private final WorldClockService worldClockService;
    private final ObjectMapper objectMapper;

    /** 模板服务 */
    @Autowired(required = false)
    private MacroTemplateService macroTemplateService;

    // ==================== 宏观事件 → 指令（回灌个体化源头） ====================

    /**
     * 宏观事件驱动指令下发（MacroEventService.emitDetailed 发布后挂钩；severity 低于阈值跳过；
     * 失败隔离不阻断宏观主流程）。
     *
     * @param projectId 项目 ID
     *
     * @param ev 已落库宏观事件
     *
     * @param gameSecond 当前游戏秒
     */
    public void onMacroEvent(Long projectId, ActorWorldMacroEvent ev, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || ev == null) {
            return;
        }
        try {
            int severity = ev.getSeverity() == null ? 0 : ev.getSeverity();
            if (severity < props.getMinEventSeverity()) {
                return;
            }
            issueDirectivesForEvent(projectId, ev, gameSecond);
        } catch (Exception e) {
            log.warn("[中观] 宏观事件指令下发失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 事件 → 全组织差异化指令（组数护栏 maxDirectivesPerEvent）。 */
    void issueDirectivesForEvent(Long projectId, ActorWorldMacroEvent ev, Long gameSecond) {
        mesoGroupEngine.seedInitialGroups(projectId, templateOf(projectId));
        List<ActorMesoGroup> groups = groupRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, "active");
        if (groups.isEmpty()) {
            return;
        }
        MacroTemplate tpl = templateOf(projectId);
        String intensity = aiIntensityGate == null ? AiIntensityGate.FULL : aiIntensityGate.intensityOf(projectId);
        boolean allAi = AiIntensityGate.MAX.equals(intensity);
        boolean importantAi = !AiIntensityGate.PROGRAMMATIC.equals(intensity)
                && (ev.getSeverity() != null && ev.getSeverity() >= 4);
        int count = 0;
        for (ActorMesoGroup g : groups) {
            if (count >= props.getMaxDirectivesPerEvent()) {
                break;
            }
            if ("adhoc".equals(g.getType())) {
                continue; // 自发群体不接事件指令（保持临时性）
            }
            String payload = rulePayload(ev.getEventType(), g, tpl);
            String source = "rule";
            // 档位联动：max 全 AI / light·full 重要事件 AI（失败回退规则 payload）
            if ((allAi || importantAi) && macroAiService != null) {
                MacroAiService.DirectiveGen ai = macroAiService.generateDirective(
                        projectId, ev.getGameDay(), ev.getEventType(), ev.getSeverity(),
                        g.getName(), g.getType(), parseTags(g.getTagsJson()),
                        tpl.communityName(), tpl.coreResource());
                if (ai != null) {
                    payload = toJson(aiPayload(ai, ev.getEventType(), tpl.coreResource()));
                    source = "ai";
                }
            }
            if (payload == null || payload.isBlank()) {
                continue;
            }
            ActorMesoDirective d = new ActorMesoDirective();
            d.setProjectId(projectId);
            d.setGroupId(g.getId());
            d.setSourceMacroEventId(ev.getId());
            d.setDirectiveType(ev.getSeverity() != null && ev.getSeverity() >= 5 ? "crisis" : "react");
            d.setPayloadJson(payload);
            d.setIssuedGameSecond(gameSecond == null ? 0L : gameSecond);
            d.setTtl(props.getTtlSeconds());
            d.setStatus("active");
            d.setSource(source);
            d.setTraceId(AiCallContext.currentTraceId());
            directiveRepository.save(d);
            count++;
        }
        if (count > 0) {
            log.info("[中观] 宏观事件指令下发: project={} day={} event={} severity={} 指令数={} 来源={}",
                    projectId, ev.getGameDay(), ev.getEventType(), ev.getSeverity(), count,
                    allAi ? "ai(max)" : (importantAi ? "ai+rule" : "rule"));
        }
    }

    /**
     * 每旬例行指令（tickFaction 挂钩；维持组织存在感——组织对成员的日常期待，强度弱）。
     */
    public void issueRoutineDirectives(Long projectId, Long gameSecond) {
        if (!props.isEnabled() || !props.isRoutineEnabled() || projectId == null) {
            return;
        }
        try {
            mesoGroupEngine.seedInitialGroups(projectId, templateOf(projectId));
            List<ActorMesoGroup> groups = groupRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, "active");
            for (ActorMesoGroup g : groups) {
                if ("adhoc".equals(g.getType())) {
                    continue;
                }
                ActorMesoDirective d = new ActorMesoDirective();
                d.setProjectId(projectId);
                d.setGroupId(g.getId());
                d.setSourceMacroEventId(null);
                d.setDirectiveType("routine");
                d.setPayloadJson(toJson(Map.of(
                        "tendency", Map.of("bias", "守好本分，顾全" + g.getName() + "的名声与生计",
                                "strength", 0.3, "reason", "每旬例行的组织叮咛"),
                        "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                        "resourceDelta", Map.of("hint", "组织照常运转"),
                        "note", "组织对你的期待：稳稳当当地过日子")));
                d.setIssuedGameSecond(gameSecond == null ? 0L : gameSecond);
                d.setTtl(props.getTtlSeconds());
                d.setStatus("active");
                d.setSource("rule");
                d.setTraceId(AiCallContext.currentTraceId());
                directiveRepository.save(d);
            }
        } catch (Exception e) {
            log.warn("[中观] 例行指令下发失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 规则指令载荷（确定性可单测） ====================

    /**
     * 规则指令载荷（事件类型 × 组织类型/标签 → 三通道；无映射返回 null=该组不接该指令）。
     * 全部按模板核心资源语义渲染（不硬编码「粮」）。
     */
    String rulePayload(String eventType, ActorMesoGroup g, MacroTemplate tpl) {
        if (eventType == null || g == null) {
            return null; // 空事件码/空组 → 不接指令（模板缺失兜底：不抛不崩）
        }
        String core = tpl == null ? "物资" : tpl.coreResource();
        boolean trade = hasTag(g, "商贸") || hasTag(g, "贸易");
        boolean medical = hasTag(g, "医馆") || hasTag(g, "诊治") || hasTag(g, "医疗");
        boolean ritual = hasTag(g, "香火") || hasTag(g, "信仰");
        String type = g.getType() == null ? "" : g.getType();

        switch (eventType) {
            case "drought", "flood", "blizzard", "price_surge", "harvest_bad" -> {
                if (trade) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "囤积" + core + "，谨慎出货", "strength", 0.6,
                                    "reason", eventType + "，" + core + "行情看涨"),
                            "constraint", Map.of("forbidden", List.of("贱卖" + core), "soft", true,
                                    "reason", "行会不得低于成本抛售"),
                            "resourceDelta", Map.of("hint", "行会统筹调配"),
                            "note", "组织对你的期待：保住货源与信誉，同时压低收购价"));
                }
                if (medical) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "备药义诊，防备时疫", "strength", 0.6,
                                    "reason", eventType + "，伤病与疫病风险上升"),
                            "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                            "resourceDelta", Map.of("hint", "药铺存药优先"),
                            "note", "组织对你的期待：医者仁心，先顾着村里人的身子"));
                }
                if (ritual) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "主持祈禳，安抚人心", "strength", 0.5,
                                    "reason", eventType + "，村中人心浮动"),
                            "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                            "resourceDelta", Map.of("hint", "庙中香火自足"),
                            "note", "组织对你的期待：稳住信众的心"));
                }
                if ("family".equals(type)) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "省着用度，护族存" + core, "strength", 0.5,
                                    "reason", eventType + "，家底要紧"),
                            "constraint", Map.of("forbidden", List.of("铺张", "开仓贱卖"), "soft", true,
                                    "reason", "族长叮嘱家中子弟省俭"),
                            "resourceDelta", Map.of("hint", "族中公账统筹"),
                            "note", "组织对你的期待：撑住这个家，莫让族里人心散"));
                }
                return toJson(Map.of(
                        "tendency", Map.of("bias", "少买多存，邻里帮衬", "strength", 0.4,
                                "reason", eventType + "，日子要精打细算"),
                        "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                        "resourceDelta", Map.of("hint", "互相周济"),
                        "note", "组织对你的期待：咬咬牙挺过去，别让谁家断了顿"));
            }
            case "revolt_risk" -> {
                if ("family".equals(type) || "sect".equals(type)) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "约束族人，平复怨气", "strength", 0.7,
                                    "reason", "民怨沸腾，聚众闹事会引火烧身"),
                            "constraint", Map.of("forbidden", List.of("聚众", "煽动"), "soft", false,
                                    "reason", "族规门规严禁生乱"),
                            "resourceDelta", Map.of("hint", "组织出面安抚"),
                            "note", "组织对你的期待：稳住自家的人，别卷进乱子"));
                }
                return null;
            }
            case "plague" -> {
                if (medical || trade) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "备药防疫，悬壶济世", "strength", 0.6,
                                    "reason", "时疫流行，药与粮都金贵"),
                            "constraint", Map.of("forbidden", List.of("囤积居奇"), "soft", true,
                                    "reason", "非常时期不得发国难财"),
                            "resourceDelta", Map.of("hint", "组织拨药"),
                            "note", "组织对你的期待：能救一个是一个"));
                }
                return null;
            }
            case "harvest_good" -> {
                if (trade) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "趁丰收多进多销", "strength", 0.5,
                                    "reason", "丰年货源足，行情活"),
                            "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                            "resourceDelta", Map.of("hint", "行会加大周转"),
                            "note", "组织对你的期待：把买卖做活，别放过这波行情"));
                }
                return null;
            }
            case "relief" -> {
                if ("family".equals(type) || trade) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "带头开仓，收拢人心", "strength", 0.5,
                                    "reason", "赈济能安人心，也是立威"),
                            "constraint", Map.of("forbidden", List.of("囤积居奇"), "soft", true,
                                    "reason", "非常时期名声比利钱要紧"),
                            "resourceDelta", Map.of("hint", "组织承担半数损耗"),
                            "note", "组织对你的期待：这口气要撑住，乡邻都看着"));
                }
                return null;
            }
            case "tech_breakthrough" -> {
                if ("sect".equals(type) || "guild".equals(type)) {
                    return toJson(Map.of(
                            "tendency", Map.of("bias", "精研技艺，再上层楼", "strength", 0.4,
                                    "reason", "技艺突破是立身之本"),
                            "constraint", Map.of("forbidden", List.of(), "soft", true, "reason", ""),
                            "resourceDelta", Map.of("hint", "组织拨付研习用度"),
                            "note", "组织对你的期待：把本事练到顶尖"));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 组标签命中判定。 */
    private static boolean hasTag(ActorMesoGroup g, String tag) {
        if (g.getTagsJson() == null || g.getTagsJson().isBlank()) {
            return false;
        }
        return g.getTagsJson().contains(tag);
    }

    /** AI 判定结果 → payload（与规则载荷同构，AI 提供个性化三通道）。 */
    Map<String, Object> aiPayload(MacroAiService.DirectiveGen ai, String eventType, String core) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tendency", Map.of(
                "bias", ai.tendencyBias() == null || ai.tendencyBias().isBlank()
                        ? "随机应变" : ai.tendencyBias(),
                "strength", clamp01(ai.tendencyStrength() == null ? 0.5 : ai.tendencyStrength()),
                "reason", eventType));
        payload.put("constraint", Map.of(
                "forbidden", ai.forbidden() == null ? List.of() : ai.forbidden(),
                "soft", !Boolean.FALSE.equals(ai.soft()),
                "reason", ai.constraintReason() == null ? "" : ai.constraintReason()));
        payload.put("resourceDelta", Map.of(
                "hint", ai.resourceHint() == null || ai.resourceHint().isBlank()
                        ? "组织统筹" : ai.resourceHint()));
        payload.put("note", ai.note() == null || ai.note().isBlank()
                ? "组织对你有所期待" : ai.note());
        return payload;
    }

    // ==================== 响应回收 ====================

    /**
     * 行动执行后的响应回收（AgentActionService.execute 挂钩；仅特殊 NPC——人群程序化决策
     * 不回收避免失真噪声；无有效指令零成本返回）。
     *
     * @param projectId 项目 ID
     *
     * @param actorRef 统一角色引用（special:{id}）
     *
     * @param actorId 角色表主键
     *
     * @param actionText 行动文本
     *
     * @param actionType 行动类型
     *
     * @param gameSecond 游戏秒（可空=引擎当前位置）
     */
    public void recordActionResponse(Long projectId, String actorRef, Long actorId, String actionText,
                                     String actionType, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || actorRef == null) {
            return;
        }
        try {
            Long sec = gameSecond == null ? currentGameSecond(projectId) : gameSecond;
            // 该成员所属组 + 全局有效指令
            List<ActorMesoDirective> directives = new ArrayList<>();
            List<ActorMesoMembership> memberships = membershipRepository
                    .findByProjectIdAndActorRefAndStatus(projectId, actorRef, "active");
            for (ActorMesoMembership m : memberships) {
                directives.addAll(mesoDirectiveRouter.activeDirectivesFor(projectId, m.getGroupId(), sec));
            }
            directives.addAll(mesoDirectiveRouter.activeDirectivesFor(projectId, null, sec));
            if (directives.isEmpty()) {
                return;
            }
            String traceId = AiCallContext.currentTraceId();
            int n = 0;
            for (ActorMesoDirective d : directives) {
                if (n >= 3) {
                    break;
                }
                String type = classifyResponse(d.getPayloadJson(), actionText);
                ActorMesoResponse r = new ActorMesoResponse();
                r.setProjectId(projectId);
                r.setDirectiveId(d.getId());
                r.setActorId(actorId == null ? 0L : actorId);
                r.setActorRef(actorRef);
                r.setResponseType(type);
                r.setDecisionTraceId(traceId);
                r.setGameSecond(sec);
                responseRepository.save(r);
                n++;
            }
            log.debug("[中观] 指令响应回收: project={} ref={} 指令数={} 行动={}",
                    projectId, actorRef, n, abbrev(actionText, 40));
        } catch (Exception e) {
            log.warn("[中观] 响应回收失败（隔离继续）: project={} ref={} : {}", projectId, actorRef, e.getMessage());
        }
    }

    /**
     * 响应分类（确定性）：约束命中 → defy 违抗；倾向关键词命中 → comply 遵从；
     * 倾向部分命中 → partial 部分遵从；无命中 → ignore 忽略。
     * 倾向 bias 按标点切 token，行动文本含任一 token（≥2 字）即命中。
     */
    static String classifyResponse(String payloadJson, String actionText) {
        if (payloadJson == null || actionText == null || actionText.isBlank()) {
            return "ignore";
        }
        try {
            JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(payloadJson);
            JsonNode constraint = root.path("constraint");
            if (constraint.isObject() && constraint.path("forbidden").isArray()) {
                for (JsonNode f : constraint.path("forbidden")) {
                    if (!f.isTextual()) {
                        continue;
                    }
                    String fb = f.asText();
                    if (fb.length() >= 2 && actionText.contains(fb)) {
                        return "defy";
                    }
                }
            }
            JsonNode tendency = root.path("tendency");
            if (!tendency.isObject() || !tendency.path("bias").isTextual()) {
                return "ignore";
            }
            String bias = tendency.path("bias").asText();
            List<String> tokens = new ArrayList<>();
            for (String part : bias.split("[，,。；;、\\s]+")) {
                String t = part.trim();
                if (t.length() >= 2) {
                    tokens.add(t);
                }
            }
            int hit = 0;
            for (String t : tokens) {
                if (actionText.contains(t)) {
                    hit++;
                }
            }
            if (hit >= 2) {
                return "comply";
            }
            if (hit == 1) {
                return "partial";
            }
            return "ignore";
        } catch (Exception e) {
            return "ignore";
        }
    }

    // ==================== 手动建组/合并/解散（调试/定向冒烟） ====================

    /** 手动建组（POST /macro/groups；调试/定向冒烟用，可回滚——DELETE 解散）。 */
    public ActorMesoGroup createGroup(Long projectId, String name, String type, String leaderRef,
                                      List<String> tags, Long gameSecond) {
        if (!props.isManualGroupEnabled()) {
            throw new com.holzyn.actor.common.BizException(400, "手动建组已关闭（holzyn.actor.meso.manual-group-enabled=false）");
        }
        if (name == null || name.isBlank()) {
            throw new com.holzyn.actor.common.BizException(400, "组名不能为空");
        }
        String normType = type == null || type.isBlank() ? "community" : type;
        if (groupRepository.findByProjectIdAndName(projectId, name).isPresent()) {
            throw new com.holzyn.actor.common.BizException(400, "同名组织已存在（uk project+name）");
        }
        ActorMesoGroup g = new ActorMesoGroup();
        g.setProjectId(projectId);
        g.setName(name);
        g.setType(normType);
        g.setStatus("active");
        g.setInfluence(20.0);
        g.setLeaderRef(leaderRef);
        g.setOrigin("manual");
        g.setTagsJson(toJson(tags == null ? List.of() : tags));
        g.setMetaJson(toJson(Map.of("relations", new LinkedHashMap<String, Integer>())));
        g.setCreatedGameSecond(gameSecond == null ? currentGameSecond(projectId) : gameSecond);
        groupRepository.save(g);
        if (leaderRef != null) {
            mesoGroupEngine.addMembership(projectId, g.getId(), leaderRef, "leader",
                    gameSecond == null ? currentGameSecond(projectId) : gameSecond);
        }
        return g;
    }

    /** 解散组织（成员关系一并软删）。 */
    public void dissolveGroup(Long projectId, Long groupId) {
        ActorMesoGroup g = groupRepository.findById(groupId).orElse(null);
        if (g == null || !projectId.equals(g.getProjectId())) {
            throw new com.holzyn.actor.common.BizException(404, "组织不存在");
        }
        g.setStatus("dissolved");
        groupRepository.save(g);
        for (ActorMesoMembership m : membershipRepository.findByGroupIdOrderByIdAsc(groupId)) {
            m.setStatus("left");
            membershipRepository.save(m);
        }
        for (ActorMesoDirective d : directiveRepository.findByProjectIdAndGroupIdOrderByIdDesc(projectId, groupId)) {
            d.setStatus("expired");
            directiveRepository.save(d);
        }
    }

    // ==================== 辅助 ====================

    /** 模板安全取（未装配回退默认仙侠模板）。 */
    MacroTemplate templateOf(Long projectId) {
        try {
            if (macroTemplateService != null) {
                MacroTemplate t = macroStateService.templateOf(projectId);
                return t == null ? MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID) : t;
            }
        } catch (Exception ignored) {
            // 回退默认
        }
        return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
    }

    /** 组标签 JSON 解析。 */
    static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = new tools.jackson.databind.ObjectMapper().readTree(tagsJson);
            List<String> out = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> out.add(n.asText()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 当前引擎推进游戏秒。 */
    Long currentGameSecond(Long projectId) {
        try {
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            return sec == null ? 0L : sec;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 对象 → JSON。 */
    String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本截断。 */
    static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** 钳制 0-1。 */
    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
