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

package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.dialogue.dto.DialogueConsolidationApplyDTO;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationResultVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.dialogue.vo.PlayerRelationVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 对谈沉淀服务（M6 沉淀收尾）。
 * <p>职责：把会话最近的对话<b>显式沉淀</b>为——① 玩家-角色关系 delta（好感/信任/阶段/备注/
 * 「关系状态摘要」，写 {@code actor_talk_player_relation}，version 幂等）+ ② 该角色 NPC 记忆
 * 「与玩家的活动」（走既有记忆写入通道 {@link AgentMemoryService#recordObservation}）+
 * ③ 会话 {@code player_relation_version} 留痕。玩家无记忆写入。</p>
 * <p><b>两段式确认（设计 §6.8 确认卡片）</b>：</p>
 * <ul>
 * <li>{@link #preview}：AI 抽取（scene=dialogue_consolidation，SCHEMA_JSON 0.3/512）→ 返回
 * <b>确认卡片</b>（每位角色将写入什么）——<b>只读不落库</b>，前端展示；</li>
 * <li>{@link #apply}：用户确认后把卡片候选<b>原样回传</b>（客户端回显确认）→ 服务端再次校验
 * 归属与边界后落库（关系写回经 {@link PlayerProfileService#applyConsolidatedRelation} 短事务、
 * NPC 记忆经 {@link AgentMemoryService#recordObservation}、会话版本经
 * {@link DialogueSessionService#markConsolidated} 短事务）。</li>
 * </ul>
 * <p><b>架构不变量</b>：{@link #preview} 含 AI 调用，<b>不得带 {@code @Transactional}</b>
 * （由 {@code AiCallOutsideTransactionTest.dialogueConsolidationIsNotTransactional} 反射断言守护）；
 * {@link #apply} 零 AI，为<b>毫秒级短事务</b>（全部本地 DB 写，无网络调用，可安全入事务）。</p>
 * <p><b>fail-open（设计 §6.8）</b>：AI 抽取失败 → preview 报错可重试（不落库）；
 * 单条记忆要点写入失败 → 跳过继续；关系/记忆/会话版本同事务，失败整体回滚（候选可重试）。</p>
 * <p><b>不写</b>：玩家记忆（玩家无记忆）、世界事件/编年史/知识库（零 OOC，对齐对谈边界）。</p>
 */
@Slf4j
@Service
public class DialogueConsolidationService {

    /** 沉淀场景码（trace/路由/模板版本留痕 + 用量记账 scene）。 */
    public static final String SCENE_CONSOLIDATION = "dialogue_consolidation";

    /** 抽取纳入的最近消息条数（控制 prompt 输入 token；含玩家/角色发言） */
    public static final int WINDOW = 40;

    /** 群聊沉淀角色上限（成员超限取前 N） */
    public static final int GROUP_CAP = 8;

    /** 单角色记忆要点条数上限 */
    public static final int MEMORY_POINTS_CAP = 5;

    /** 单条记忆要点最大字符数 */
    public static final int MEMORY_POINT_MAX = 80;

    /** 关系 delta 钳制绝对值（AI 判定/客户端回传二次收敛） */
    public static final int DELTA_CLAMP = 30;

    /** 关系状态摘要最大字符数 */
    public static final int STATE_SUMMARY_MAX = 120;

    /** NPC 记忆默认重要度（3=值得记的日常活动；走既有记忆通道的分级） */
    private static final int MEMORY_IMPORTANCE = 3;

    /** Jackson 3 序列化器（AI 抽取结果 JSON 解析） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话服务（会话读取 + 沉淀版本短事务写回） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（会话消息实体读取） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** 群聊成员服务（群聊沉淀范围=当前成员） */
    @Lazy
    @Autowired(required = false)
    private DialogueGroupService groupService;

    /** 玩家-角色关系服务（关系写回短事务） */
    @Autowired(required = false)
    private PlayerProfileService playerProfileService;

    /** 玩家名解析（NPC 记忆「与{玩家}的对谈」前缀） */
    @Autowired(required = false)
    private DialogueRelationService relationService;

    /** NPC 记忆写入通道（既有记忆写入，去重 + 预算淘汰） */
    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;

    /** 关系行仓库（preview 只读当前值，不惰性创建——保持 preview 零写入） */
    @Autowired(required = false)
    private ActorPlayerRelationRepository relationRepository;

    /** 角色主表仓库（角色名解析） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 用量日志（scene=dialogue_consolidation 记账，仅 preview 发生 AI 调用） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** AI 网关（非流式 JSON 抽取） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    // ==================== 确认卡片（AI 抽取，只读不落库） ====================

    /**
     * 沉淀确认卡片：AI 从会话最近对话中抽取每位对谈角色的关系 delta + 记忆要点。
     * <b>只读</b>（不落库、不惰性创建关系行），供前端确认卡片展示；用户确认后调 {@link #apply}。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     *
     * @return 确认卡片（每位角色将写入什么）
     */
    public DialogueConsolidationVO preview(Long projectId, Long userId, Long sessionId) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        List<ScopeChar> scope = scopeOf(session, projectId, sessionId);
        List<String> dialogLines = recentDialog(projectId, sessionId);
        if (dialogLines.size() < 2) {
            throw new BizException(400, "对话内容太少，暂无可沉淀的内容");
        }
        String playerName = playerNameOf(userId);
        List<ExtractedRelation> extracted = extract(userId, projectId, session, scope, playerName, dialogLines);

        DialogueConsolidationVO vo = new DialogueConsolidationVO();
        vo.setSessionId(sessionId);
        vo.setType(session.getType());
        vo.setPlayerRelationVersion(session.getPlayerRelationVersion() == null ? 0 : session.getPlayerRelationVersion());
        List<DialogueConsolidationVO.CharacterPreview> chars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExtractedRelation e : extracted) {
            if (e.characterRef() == null || !seen.add(e.characterRef())) {
                continue;
            }
            ScopeChar sc = scope.stream().filter(s -> s.characterRef().equals(e.characterRef()))
                    .findFirst().orElse(null);
            if (sc == null) {
                continue;
            }
            ActorPlayerRelation r = currentRelation(projectId, e.characterRef());
            int curAffinity = r == null || r.getAffinity() == null ? 0 : r.getAffinity();
            int curTrust = r == null || r.getTrust() == null ? 0 : r.getTrust();
            DialogueConsolidationVO.CharacterPreview cp = new DialogueConsolidationVO.CharacterPreview();
            cp.setCharacterRef(e.characterRef());
            cp.setCharacterName(sc.characterName());
            cp.setCurrentAffinity(curAffinity);
            cp.setCurrentTrust(curTrust);
            cp.setCurrentStage(r == null || r.getStage() == null ? "陌生" : r.getStage());
            cp.setAffinityDelta(e.affinityDelta());
            cp.setTrustDelta(e.trustDelta());
            cp.setProposedAffinity(clamp(curAffinity + e.affinityDelta()));
            cp.setProposedTrust(clamp(curTrust + e.trustDelta()));
            cp.setStage(e.stage());
            cp.setNote(e.note());
            cp.setStateSummary(e.stateSummary());
            cp.setMemoryPoints(e.memoryPoints());
            chars.add(cp);
        }
        vo.setCharacters(chars);
        return vo;
    }

    // ==================== 应用沉淀（零 AI，短事务） ====================

    /**
     * 应用沉淀（用户确认后）：关系写回 + NPC 记忆 + 会话版本留痕，全本地 DB <b>短事务</b>。
     * 候选由 {@link #preview} 卡片原样回传，服务端再次校验角色归属与 delta 边界后落库。
     *
     * @param projectId  项目 ID
     * @param userId     用户 ID
     * @param sessionId  会话 ID
     * @param candidates 确认卡片回传的候选角色列表
     *
     * @return 应用结果（每位角色新值 + 记忆写入条数 + 会话版本）
     */
    @Transactional
    public DialogueConsolidationResultVO apply(Long projectId, Long userId, Long sessionId,
                                               List<DialogueConsolidationApplyDTO.CharacterCandidate> candidates) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        List<ScopeChar> scope = scopeOf(session, projectId, sessionId);
        if (candidates == null || candidates.isEmpty()) {
            throw new BizException(400, "没有可应用的沉淀内容");
        }
        String playerName = playerNameOf(userId);
        int applied = 0;
        int maxVersion = session.getPlayerRelationVersion() == null ? 0 : session.getPlayerRelationVersion();
        List<DialogueConsolidationResultVO.CharacterResult> results = new ArrayList<>();
        for (DialogueConsolidationApplyDTO.CharacterCandidate c : candidates) {
            if (c == null || c.characterRef() == null || c.characterRef().isBlank()) {
                continue;
            }
            if (scope.stream().noneMatch(s -> s.characterRef().equals(c.characterRef()))) {
                log.warn("[对谈沉淀] 候选角色不在会话范围，跳过: ref={}", c.characterRef());
                continue;
            }
            String ref = c.characterRef();
            // ① 关系写回（delta 叠加钳制 + version+1；短事务内）
            PlayerRelationVO vo = playerProfileService.applyConsolidatedRelation(projectId, userId, ref,
                    clampDelta(c.affinityDelta()), clampDelta(c.trustDelta()), c.stage(), c.note(), c.stateSummary());
            // ② NPC 记忆「与玩家的活动」（既有记忆通道；单条失败跳过继续）
            int written = writeMemories(projectId, ref, playerName, c.memoryPoints());
            // ③ 结果组装
            DialogueConsolidationResultVO.CharacterResult r = new DialogueConsolidationResultVO.CharacterResult();
            r.setCharacterRef(ref);
            r.setCharacterName(nameOf(ref));
            r.setAffinity(vo.getAffinity());
            r.setTrust(vo.getTrust());
            r.setStage(vo.getStage());
            r.setNote(vo.getNote());
            r.setStateSummary(vo.getStateSummary());
            r.setVersion(vo.getVersion());
            r.setMemoriesWritten(written);
            results.add(r);
            if (vo.getVersion() != null && vo.getVersion() > maxVersion) {
                maxVersion = vo.getVersion();
            }
            applied++;
        }
        if (applied == 0) {
            throw new BizException(400, "没有可应用的对谈角色");
        }
        // ④ 会话沉淀版本留痕
        sessionService.markConsolidated(projectId, sessionId, maxVersion);
        DialogueConsolidationResultVO result = new DialogueConsolidationResultVO();
        result.setSessionId(sessionId);
        result.setVersion(maxVersion);
        result.setCharacters(results);
        return result;
    }

    // ==================== AI 抽取 ====================

    /**
     * AI 抽取（事务外）：scene=dialogue_consolidation，SCHEMA_JSON（jsonMode=true）。
     * 失败抛 BizException(500)（fail-open，preview 可重试；本方法不落库）。
     */
    private List<ExtractedRelation> extract(Long userId, Long projectId, ActorDialogueSession session,
                                            List<ScopeChar> scope, String playerName, List<String> dialogLines) {
        if (aiChatGateway == null) {
            throw new BizException(500, "AI 网关未就绪，暂时无法沉淀");
        }
        StringBuilder charactersContext = new StringBuilder();
        for (ScopeChar sc : scope) {
            ActorPlayerRelation r = currentRelation(projectId, sc.characterRef());
            charactersContext.append("- ").append(sc.characterName() == null ? sc.characterRef() : sc.characterName())
                    .append("（").append(sc.characterRef()).append("）：当前关系 好感 ")
                    .append(r == null || r.getAffinity() == null ? 0 : r.getAffinity()).append("/信任 ")
                    .append(r == null || r.getTrust() == null ? 0 : r.getTrust()).append("/阶段「")
                    .append(r == null || r.getStage() == null ? "陌生" : r.getStage()).append("」\n");
        }
        StringBuilder dialog = new StringBuilder();
        for (String line : dialogLines) {
            dialog.append(line).append("\n");
        }
        // 2026-10-13 第 61 轮真实冒烟抓修★：旧 system 只说「只输出 JSON」，契约只在模板里（「见模板契约」），
        // 运行时未注入 → 模型自由发挥成中文键变体 {"赵万金":{"关系_delta":{…}}} → parseRelations 严格读
        // relations 数组全部丢弃 → preview 恒返回空卡片（沉淀功能实际不可用）。修复：契约内联进 system，
        // 键名/结构强制锁定（character_ref 必须取自【对谈角色】列表）。
        String system = "你是对谈沉淀记录员：从玩家与对谈角色的对话中，克制地判定每位角色的关系变化与值得记住的记忆要点。"
                + "严格只输出如下 JSON，键名与结构不得改动、不要输出任何其他文字："
                + "{\"relations\":[{\"character_ref\":\"角色引用\",\"affinity_delta\":整数,\"trust_delta\":整数,"
                + "\"stage\":\"新关系阶段或保持\",\"note\":\"备注或省略\","
                + "\"state_summary\":\"跨会话关系状态摘要(≤80字)\",\"memory_points\":[\"要点1\",\"要点2\"]}]}"
                + "约束：1. affinity_delta/trust_delta 为 -20~20 的整数，无明显变化用 0，不要臆测或大幅波动；"
                + "2. stage 无变化则不填（保持现状）；note/state_summary 无内容可省略；"
                + "3. memory_points 只写明确发生的与玩家的活动/共识/承诺（每条 ≤60 字，1~3 条），宁缺毋滥；"
                + "4. 无实质变化时 relations 可为空数组；"
                + "5. character_ref 必须写纯引用（如 npc:3），不要附带角色名或括号。";
        String user = "【对谈角色】\n" + charactersContext
                + "【玩家】" + playerName + "\n"
                + "【最近对话】\n" + dialog
                + "\n【任务】为每位对谈角色输出关系 delta 与记忆要点（character_ref 必须取自【对谈角色】列表）。";
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_CONSOLIDATION);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", system),
                        new AiChatRequest.ChatMessage("user", user)),
                spec.temperature(), spec.maxOutputTokens(), true);
        AiChatResult result;
        try {
            Long npcId = NpcRefs.idOf(scope.get(0).characterRef());
            result = AiCallContext.run(SCENE_CONSOLIDATION, npcId,
                    (Supplier<AiChatResult>) () -> aiChatGateway.chatCompletion(userId, projectId, null, request));
        } catch (AiCallException e) {
            log.warn("[对谈沉淀] AI 抽取失败（fail-open，可重试）: {}", e.getMessage());
            throw new BizException(500, "沉淀分析失败，请稍后重试");
        } catch (Exception e) {
            log.warn("[对谈沉淀] AI 抽取异常（fail-open，可重试）: {}", e.getMessage());
            throw new BizException(500, "沉淀分析失败，请稍后重试");
        }
        String text = result == null || result.content() == null ? null : result.content().trim();
        if (text == null || text.isBlank()) {
            log.warn("[对谈沉淀] AI 抽取为空（fail-open，可重试）");
            throw new BizException(500, "沉淀分析结果为空，请稍后重试");
        }
        // 用量记账（scene=dialogue_consolidation；供应商 usage 缺失本地估算兜底，失败仅告警）
        recordUsage(userId, projectId, scope, request, text, result);
        Set<String> allowed = new LinkedHashSet<>();
        scope.forEach(s -> allowed.add(s.characterRef()));
        return parseRelations(text, allowed);
    }

    /** 用量记账（仅 preview 的 AI 抽取；失败忽略）。 */
    private void recordUsage(Long userId, Long projectId, List<ScopeChar> scope,
                             AiChatRequest request, String answer, AiChatResult result) {
        if (usageLogService == null) {
            return;
        }
        try {
            Long npcId = scope.isEmpty() ? null : NpcRefs.idOf(scope.get(0).characterRef());
            Integer in = result.promptTokens() != null ? result.promptTokens()
                    : TokenEstimator.estimatePromptTokens(request.messages());
            Integer out = result.completionTokens() != null ? result.completionTokens()
                    : TokenEstimator.estimateTokens(answer);
            usageLogService.record(userId, projectId, npcId, result.providerId(), result.model(),
                    SCENE_CONSOLIDATION, in, out, result.cacheHitTokens(), result.cacheMissTokens(), null);
        } catch (Exception e) {
            log.warn("[对谈沉淀] 用量记账失败（忽略，不影响沉淀）: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /** 沉淀范围角色：单聊=会话角色；群聊=当前成员（present 前 manual 后，取前 {@code GROUP_CAP}）。 */
    private List<ScopeChar> scopeOf(ActorDialogueSession session, Long projectId, Long sessionId) {
        if ("group".equals(session.getType())) {
            List<DialogueGroupMemberVO> members = groupService == null ? List.of()
                    : groupService.members(projectId, sessionId);
            List<ScopeChar> out = new ArrayList<>();
            for (DialogueGroupMemberVO m : members) {
                if (m == null || m.characterRef() == null || m.characterRef().isBlank()) {
                    continue;
                }
                if (out.size() >= GROUP_CAP) {
                    break;
                }
                out.add(new ScopeChar(m.characterRef(), m.characterName()));
            }
            if (out.isEmpty()) {
                throw new BizException(400, "群聊还没有成员，暂无可沉淀的角色");
            }
            return out;
        }
        String ref = session.getCharacterRef();
        if (ref == null || ref.isBlank()) {
            throw new BizException(400, "单聊会话缺少对谈角色");
        }
        return List.of(new ScopeChar(ref, nameOf(ref)));
    }

    /** 最近对话行（最近 {@code WINDOW} 条，跳过 system/event/空行；玩家/角色标注说话人）。 */
    private List<String> recentDialog(Long projectId, Long sessionId) {
        List<ActorDialogueMessage> all = messageService == null ? List.of()
                : messageService.listEntities(projectId, sessionId);
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, all.size() - WINDOW); i < all.size(); i++) {
            ActorDialogueMessage m = all.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            String role = m.getRole();
            if ("user".equals(role)) {
                out.add("玩家：" + m.getContent().trim());
            } else if ("assistant".equals(role) || "group".equals(role)) {
                out.add(nameOf(m.getCharacterRef()) + "：" + m.getContent().trim());
            }
        }
        return out;
    }

    /** 写该角色的 NPC 记忆要点（「与{玩家}的对谈：…」；既有记忆通道去重 + 预算淘汰；单条失败跳过）。 */
    private int writeMemories(Long projectId, String characterRef, String playerName, List<String> memoryPoints) {
        int written = 0;
        if (agentMemoryService == null || memoryPoints == null || memoryPoints.isEmpty()) {
            return 0;
        }
        String who = playerName == null || playerName.isBlank() ? "你" : playerName;
        int count = 0;
        for (String p : memoryPoints) {
            if (p == null || p.isBlank() || count >= MEMORY_POINTS_CAP) {
                continue;
            }
            count++;
            String content = "与" + who + "的对谈：" + truncate(p.trim(), MEMORY_POINT_MAX);
            try {
                agentMemoryService.recordObservation(projectId, characterRef, content, MEMORY_IMPORTANCE);
                written++;
            } catch (Exception e) {
                log.warn("[对谈沉淀] 记忆要点写入失败（跳过继续，fail-open）: {}", e.getMessage());
            }
        }
        return written;
    }

    /** preview 只读当前关系行（不惰性创建——保持 preview 零写入）。 */
    private ActorPlayerRelation currentRelation(Long projectId, String characterRef) {
        if (relationRepository == null) {
            return null;
        }
        try {
            return relationRepository.findByProjectIdAndCharacterRef(projectId, characterRef).orElse(null);
        } catch (Exception e) {
            log.debug("[对谈沉淀] 关系行读取失败（按无关系处理）: {}", e.getMessage());
            return null;
        }
    }

    /** 角色引用 → 角色名（主表直查；失败回退引用）。 */
    private String nameOf(String characterRef) {
        if (characterRef == null || characterRef.isBlank()) {
            return "角色";
        }
        if (characterRepository != null) {
            try {
                Long id = NpcRefs.idOf(characterRef);
                if (id != null) {
                    String n = characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
                    if (n != null && !n.isBlank()) {
                        return n;
                    }
                }
            } catch (Exception e) {
                log.debug("[对谈沉淀] 角色名解析失败（回退引用）: {}", e.getMessage());
            }
        }
        return characterRef;
    }

    /** 玩家名解析（玩家档案 → 账户昵称 → 「你」兜底）。 */
    private String playerNameOf(Long userId) {
        if (relationService == null) {
            return "你";
        }
        try {
            String n = relationService.playerNameOf(userId);
            return n == null || n.isBlank() ? "你" : n;
        } catch (Exception e) {
            return "你";
        }
    }

    /** 解析 AI 抽取 JSON → 关系候选列表（过滤到允许角色 + 钳制边界）。静态可单测。 */
    static List<ExtractedRelation> parseRelations(String json, Set<String> allowedRefs) {
        List<ExtractedRelation> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return out; // 非法 JSON：按空处理（调用方已在 extract 层报错）
        }
        JsonNode relations = root.path("relations");
        if (!relations.isArray()) {
            return out;
        }
        for (JsonNode n : relations) {
            if (n == null || !n.isObject()) {
                continue;
            }
            // 2026-10-13 第 61 轮冒烟抓修★：模型可能把 character_ref 写成带名变体「赵万金（npc:3）」
            // （角色清单展示格式）→ 容错归一为允许集内的纯引用，避免被越界过滤丢弃
            String ref = normalizeRef(textOrNull(n.get("character_ref")), allowedRefs);
            if (ref == null) {
                continue;
            }
            Integer affinity = clampDelta(intValue(n.get("affinity_delta"), 0));
            Integer trust = clampDelta(intValue(n.get("trust_delta"), 0));
            String stage = textOrNull(n.get("stage"));
            String note = textOrNull(n.get("note"));
            String stateSummary = truncate(textOrNull(n.get("state_summary")), STATE_SUMMARY_MAX);
            List<String> points = new ArrayList<>();
            JsonNode arr = n.get("memory_points");
            if (arr != null && arr.isArray()) {
                for (JsonNode p : arr) {
                    String t = p == null ? null : p.asText(null);
                    if (t == null || t.isBlank() || points.size() >= MEMORY_POINTS_CAP) {
                        continue;
                    }
                    points.add(truncate(t.trim(), MEMORY_POINT_MAX));
                }
            }
            out.add(new ExtractedRelation(ref, affinity, trust, stage, note, stateSummary, points));
        }
        return out;
    }

    /**
     * 引用归一（第 61 轮冒烟抓修★）：模型可能把 character_ref 输出为带名变体「赵万金（npc:3）」
     * 或带空格等噪音 → 精确命中失败时按子串双向匹配归一到允许集内的纯引用；无匹配返回 null（越界丢弃）。
     */
    private static String normalizeRef(String ref, Set<String> allowedRefs) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String t = ref.trim();
        if (allowedRefs == null || allowedRefs.isEmpty()) {
            return t;
        }
        if (allowedRefs.contains(t)) {
            return t;
        }
        for (String a : allowedRefs) {
            if (t.contains(a) || a.contains(t)) {
                return a;
            }
        }
        return null;
    }

    /** 节点文本（缺失/非文本/null 返回 null）。 */
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String s = node.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (node.isNumber() || node.isBoolean()) {
            String s = node.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        return null;
    }

    /** 节点整型取值（缺失/非数值返回默认）。 */
    private static int intValue(JsonNode node, int def) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isNumber()) {
            return def;
        }
        return node.asInt(def);
    }

    /** 好感/信任钳制 0~100。 */
    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** delta 钳制到 ±{@code DELTA_CLAMP}（null → 0）。 */
    private static Integer clampDelta(Integer v) {
        if (v == null) {
            return 0;
        }
        return Math.max(-DELTA_CLAMP, Math.min(DELTA_CLAMP, v));
    }

    /** 截断到上限字符（null/空原样）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 沉淀范围角色（引用 + 冗余名）。 */
    record ScopeChar(String characterRef, String characterName) {
    }

    /** AI 抽取结果（关系 delta + 记忆要点；已过滤到允许角色并钳制边界）。 */
    record ExtractedRelation(String characterRef, Integer affinityDelta, Integer trustDelta,
                             String stage, String note, String stateSummary, List<String> memoryPoints) {
    }
}
