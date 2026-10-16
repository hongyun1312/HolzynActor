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
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对谈 AI 动态开场白（AI 调用，<b>不得带 {@code @Transactional}</b>）。
 * <p>职责：新会话创建后生成角色第一句话（设计 §6.7）——输入=角色卡（L0 稳定 system）
 * + 冻结状态 + 对玩家态度（关系段）+ 场景；输出=角色第一人称开场白（≤80 字，不代答）。
 * 生成成功落库为 assistant 消息（meta_json={"kind":"greeting"}）。</p>
 * <p><b>fail-open</b>：AI 失败/解析失败 → 不落库、返回 null（前端可提示「角色沉默了片刻…」），
 * 不阻断聊天。若会话已有实质消息（玩家已先发言）→ 跳过不重复生成。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用，不得带 {@code @Transactional}
 * （由 {@code AiCallOutsideTransactionTest.dialogueGreetingIsNotTransactional} 反射断言守护）。</p>
 */
@Slf4j
@Service
public class DialogueGreetingService {

    /** 开场白场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE_GREETING = "dialogue_greeting";

    /** 会话服务（会话隔离校验） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（短事务落库） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** 上下文装配器（L0 稳定 system + 开场白输入） */
    @Lazy
    @Autowired(required = false)
    private DialogueContextAssembler assembler;

    /** 玩家-角色关系服务（玩家名） */
    @Lazy
    @Autowired(required = false)
    private DialogueRelationService relationService;

    /** 角色主表仓库（角色名） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** AI 网关（非流式） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量日志（M4：scene=dialogue_greeting 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** 群聊成员服务（M5 群聊开场：清醒成员按序生成） */
    @Lazy
    @Autowired(required = false)
    private DialogueGroupService groupService;

    /** 群聊开场人数上限（在场角色过多时只前 N 位开口，避免开场长墙） */
    public static final int GROUP_GREETING_CAP = 3;

    /**
     * 生成 AI 动态开场白并落库。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     *
     * @return 开场白文本（AI 失败/已存在开场白返回 null）
     */
    public String generate(Long projectId, Long userId, Long sessionId) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        if (session.getCharacterRef() == null) {
            return null; // 群聊开场走 generateGroup
        }
        // 已有实质消息（玩家先发言/已生成开场白）→ 跳过
        if (hasContent(projectId, sessionId)) {
            return null;
        }
        String characterName = characterNameOf(session.getCharacterRef());
        String playerName = relationService == null ? "你" : relationService.playerNameOf(userId);
        // 上下文：L0 稳定 system（角色卡+关系段）+ 开场白输入（冻结时刻+场景）
        String system;
        String user;
        try {
            system = assembler.stableSystem(projectId, session.getCharacterRef(), characterName, playerName);
            // M4 作者注释：按位置并入 system（depth 2 不并入，开场白仅 system+user 两段）
            system = assembler.withSystemAuthorNote(system, session, 0);
            user = assembler.greetingPrompt(projectId, session, characterName, playerName);
        } catch (Exception e) {
            log.warn("[对谈] 开场白上下文装配失败，降级最小输入: {}", e.getMessage());
            system = "你是" + (characterName == null ? "角色" : characterName) + "，绝不能跳出角色。";
            user = "【场景】" + playerName + "来到了你身边，想与你聊聊天。\n请以"
                    + (characterName == null ? "你" : characterName) + "的口吻说一句开场白（第一人称，不超过 80 字）。";
        }
        String text = callGreeting(projectId, userId, session.getCharacterRef(), system, user);
        if (text == null) {
            return null;
        }
        messageService.appendAssistant(projectId, sessionId, session.getCharacterRef(), text,
                "{\"kind\":\"greeting\"}");
        return text;
    }

    /**
     * 群聊开场（M5，设计 §6.7）：由在场清醒角色按序生成开场白（上限
     * {@value #GROUP_GREETING_CAP} 位），逐条落库为 role=group 消息（meta kind=greeting）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID（须为群聊）
     *
     * @return 已生成开场白列表（每项 characterRef/characterName/content）；
     *         非群聊返回 null；已有实质消息返回空表
     */
    public List<Map<String, Object>> generateGroup(Long projectId, Long userId, Long sessionId) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        if (!"group".equals(session.getType())) {
            return null;
        }
        if (hasContent(projectId, sessionId)) {
            return List.of();
        }
        List<DialogueGroupMemberVO> members = groupService == null
                ? List.of() : groupService.awakeMembers(projectId, sessionId);
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> allNames = members.stream().map(m -> m.characterName() == null
                ? characterNameOf(m.characterRef()) : m.characterName()).toList();
        String playerName = relationService == null ? "你" : relationService.playerNameOf(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < members.size() && i < GROUP_GREETING_CAP; i++) {
            DialogueGroupMemberVO member = members.get(i);
            String name = member.characterName() == null ? characterNameOf(member.characterRef()) : member.characterName();
            String system;
            String user;
            try {
                system = assembler.stableSystem(projectId, member.characterRef(), name, playerName);
                system = assembler.withSystemAuthorNote(system, session, 0);
                user = assembler.groupGreetingPrompt(session, name, playerName, allNames);
            } catch (Exception e) {
                log.warn("[群聊] 开场白上下文装配失败，降级最小输入: {}", e.getMessage());
                system = "你是" + (name == null ? "角色" : name) + "，绝不能跳出角色。";
                user = "【场景】" + playerName + "来到了大家面前。\n请以"
                        + (name == null ? "你" : name) + "的口吻说一句开场白（第一人称，不超过 80 字）。";
            }
            String text = callGreeting(projectId, userId, member.characterRef(), system, user);
            if (text == null) {
                continue; // 单成员失败跳过（F2：失败不断轮）
            }
            messageService.appendGroup(projectId, sessionId, member.characterRef(), text, "{\"kind\":\"greeting\"}");
            out.add(Map.of("characterRef", member.characterRef(), "characterName", name, "content", text));
        }
        return out;
    }

    /** 会话是否已有实质消息（任一非空正文）。 */
    private boolean hasContent(Long projectId, Long sessionId) {
        List<ActorDialogueMessage> history = messageService.listEntities(projectId, sessionId);
        return history.stream().anyMatch(m -> m != null && m.getContent() != null && !m.getContent().isBlank());
    }

    /**
     * 单次开场白 AI 调用 + 用量记账（generate/generateGroup 共用）。
     * AI 失败/空输出返回 null（fail-open 不落库）。
     */
    private String callGreeting(Long projectId, Long userId, String characterRef, String system, String user) {
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_GREETING);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", system),
                        new AiChatRequest.ChatMessage("user", user)),
                spec.temperature(), spec.maxOutputTokens(), false);
        AiChatResult result;
        try {
            result = AiCallContext.run(SCENE_GREETING, NpcRefs.idOf(characterRef), (Supplier<AiChatResult>) () ->
                    aiChatGateway.chatCompletion(userId, projectId, null, request));
        } catch (AiCallException e) {
            log.warn("[对谈] 开场白生成失败（fail-open 不落库）: project={} : {}", projectId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[对谈] 开场白生成异常（fail-open 不落库）: project={} : {}", projectId, e.getMessage());
            return null;
        }
        String text = result == null || result.content() == null ? null : result.content().trim();
        if (text == null || text.isBlank()) {
            log.warn("[对谈] 开场白为空（fail-open 不落库）: project={}", projectId);
            return null;
        }
        // M4 用量记账（scene=dialogue_greeting；失败仅告警不阻断）
        if (usageLogService != null) {
            try {
                Integer in = result.promptTokens() != null ? result.promptTokens()
                        : TokenEstimator.estimatePromptTokens(request.messages());
                Integer out = result.completionTokens() != null ? result.completionTokens()
                        : TokenEstimator.estimateTokens(text);
                usageLogService.record(userId, projectId, NpcRefs.idOf(characterRef),
                        result.providerId(), result.model(), SCENE_GREETING, in, out,
                        result.cacheHitTokens(), result.cacheMissTokens(), null);
            } catch (Exception e) {
                log.warn("[对谈] 开场白用量记账失败（忽略）: {}", e.getMessage());
            }
        }
        return text;
    }

    /** 角色名（主表直查；失败返回 null）。 */
    private String characterNameOf(String characterRef) {
        if (characterRepository == null || characterRef == null) {
            return null;
        }
        try {
            Long id = NpcRefs.idOf(characterRef);
            if (id == null) {
                return null;
            }
            return characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
