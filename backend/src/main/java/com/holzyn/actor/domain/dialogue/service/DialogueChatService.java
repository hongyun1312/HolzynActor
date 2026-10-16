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
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对谈单聊编排（AI 调用，<b>不得带 {@code @Transactional}</b>）。
 * <p>流程：玩家消息落库（{@link DialogueMessageService} 短事务）→ 上下文装配
 * （{@link DialogueContextAssembler}：L0 system + L1 user）→ <b>SSE 流式回复</b>
 * （scene=dialogue_reply，PLAIN_TEXT，经 {@link AiCallContext#run} 记 trace）→
 * 回复落库 → done。对话消息只存 {@code actor_dialogue_*}（零 OOC）。</p>
 * <p><b>重写（regenerate）</b>：目标角色回复（默认最后一条 assistant）→ 截断目标及其后
 * （{@link DialogueMessageService#remove}）→ 取截断后最后一条玩家发言为触发 → 复用
 * {@link #streamReply} 重新生成；旧回复（含既有备选）写入新回复 {@code metaJson.swipes}
 * 供「换一条（swipe）」轮换。</p>
 * <p><b>停止语义</b>：流式期间连接断开（{@link SseSink#send} 返回 false，即用户点击停止）→
 * 停止累积且<b>不落库</b>（停止=丢弃本轮生成，避免半截回复污染历史）。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用，不得带 {@code @Transactional}
 * （由 {@code AiCallOutsideTransactionTest.dialogueChatIsNotTransactional}/
 * {@code dialogueRegenerateIsNotTransactional} 反射断言守护）；
 * 落库全部委托 {@link DialogueMessageService}（其自身短事务）。</p>
 * <p><b>失败即降级</b>：流式调用失败 → SSE error 事件（friendly 提示），不编造。</p>
 */
@Slf4j
@Service
public class DialogueChatService {

    /** 单聊回复场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE_REPLY = "dialogue_reply";

    /** 对谈聊天用量记账场景码（UsageLogService 维度；与 trace 场景 dialogue_reply 区分） */
    public static final String SCENE_USAGE = "dialogue_chat";

    /**
     * SSE 事件输出口（控制器适配 SseEmitter；测试用捕获型假实现）。
     */
    @FunctionalInterface
    public interface SseSink {
        /** @return false=连接已断开（调用方停止后续推送） */
        boolean send(String name, Object data);
    }

    /** 会话服务（会话隔离校验） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（短事务落库） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** 上下文装配器 */
    @Lazy
    @Autowired(required = false)
    private DialogueContextAssembler assembler;

    /** 滚动窗口自动摘要服务（M4：回复落库后触发超窗摘要） */
    @Lazy
    @Autowired(required = false)
    private DialogueSummaryService summaryService;

    /** 用量日志（M4：scene=dialogue_chat 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** AI 网关（流式） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 角色主表仓库（角色名解析） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository characterRepository;

    /**
     * 一轮对谈（SSE 流式）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param content   玩家发言（非空）
     * @param sink      SSE 输出口（事件：meta → token* → done{messageId,content,...} | error）
     */
    public void chat(Long projectId, Long userId, Long sessionId, String content, SseSink sink) {
        // ① 玩家消息落库（短事务；标题首条自动生成）
        messageService.appendUser(projectId, sessionId, content);
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        // ② 历史（不含本轮刚落库的发言——防「上一条」误判为自身，对齐助手域 M5 抓修）
        List<ActorDialogueMessage> history = messageService.listEntities(projectId, sessionId);
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).getRole())) {
            history = history.subList(0, history.size() - 1);
        }
        // ③~⑤ 装配 + 流式 + 落库（新回复无 swipe 备选）
        streamReply(projectId, userId, session, content, history, sink, null);
    }

    /**
     * 重写一条角色回复（SSE 流式，语义见类注释）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param messageId 目标回复 ID（可空：缺省取会话最后一条 assistant）
     * @param sink      SSE 输出口（事件契约同 {@link #chat}）
     */
    public void regenerate(Long projectId, Long userId, Long sessionId, Long messageId, SseSink sink) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        // 目标回复定位（缺省=最后一条 assistant）
        List<ActorDialogueMessage> all = messageService.listEntities(projectId, sessionId);
        ActorDialogueMessage target = resolveTarget(all, messageId);
        if (target == null) {
            sink.send("error", Map.of("message", "没有可重写的角色回复"));
            return;
        }
        // 旧回复（含既有备选）→ 新回复的 swipe 候选
        List<String> candidates = new ArrayList<>();
        if (target.getContent() != null && !target.getContent().isBlank()) {
            candidates.add(target.getContent());
        }
        candidates.addAll(DialogueMessageService.parseSwipes(target.getMetaJson()));
        // 截断：目标及其后全部作废（重写从目标处重新分叉）
        messageService.remove(projectId, sessionId, target.getId());
        // 触发发言 = 截断后最后一条玩家消息
        List<ActorDialogueMessage> remaining = messageService.listEntities(projectId, sessionId);
        if (remaining.isEmpty() || !"user".equals(remaining.get(remaining.size() - 1).getRole())) {
            sink.send("error", Map.of("message", "重写前没有玩家的发言可回应"));
            return;
        }
        String trigger = remaining.get(remaining.size() - 1).getContent();
        List<ActorDialogueMessage> history = remaining.subList(0, remaining.size() - 1);
        String meta = DialogueMessageService.serializeSwipes(candidates);
        streamReply(projectId, userId, session, trigger, history, sink, meta);
    }

    /**
     * 装配 + SSE 流式回复 + 落库（chat/regenerate 共用）。
     * <p>事件：meta(sessionId/characterRef) → token(delta)* → done(messageId/content/... ) |
     * error(message)；连接断开（sink 返回 false）→ 停止累积且不落库。</p>
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param session   会话（含 characterRef）
     * @param content   本轮触发发言（chat=刚落库的玩家发言；regenerate=截断后最后一条玩家发言）
     * @param history   历史（不含触发发言本身）
     * @param sink      SSE 输出口
     * @param metaJson  新回复的 metaJson（regenerate 携带 swipe 候选；chat 为 null）
     */
    private void streamReply(Long projectId, Long userId, ActorDialogueSession session,
                             String content, List<ActorDialogueMessage> history,
                             SseSink sink, String metaJson) {
        // ③ 上下文装配（L0 system + L1 user）
        DialogueContextAssembler.ComposedContext ctx;
        try {
            ctx = assembler.composeSingle(projectId, userId, session, content, history);
        } catch (Exception e) {
            log.warn("[对谈] 上下文装配失败，降级最小上下文: {}", e.getMessage());
            ctx = new DialogueContextAssembler.ComposedContext(
                    "你是" + characterNameOf(session) + "，绝不能跳出角色。",
                    "【本轮】" + content);
        }
        sink.send("meta", Map.of("sessionId", session.getId(), "characterRef", session.getCharacterRef()));
        // ④ 流式回复
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_REPLY);
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", ctx.system()));
        messages.add(new AiChatRequest.ChatMessage("user", ctx.user()));
        AiChatRequest request = new AiChatRequest(null, messages, spec.temperature(), spec.maxOutputTokens(), false);
        StringBuilder answer = new StringBuilder();
        // 断连标记：连接已断开（用户点击停止/页面离开）→ 停止累积，不落库
        boolean[] broken = {false};
        // 实际用量（供应商返回；可能为 null → 本地估算兜底）
        AiUsage[] usage = {null};
        try {
            AiCallContext.run(SCENE_REPLY, NpcRefs.idOf(session.getCharacterRef()), (Supplier<Void>) () -> {
                aiChatGateway.chatCompletionStream(userId, projectId, null, request,
                        delta -> {
                            if (broken[0]) {
                                return;
                            }
                            answer.append(delta);
                            if (!sink.send("token", Map.of("delta", delta))) {
                                broken[0] = true;
                            }
                        }, (AiUsage u) -> usage[0] = u);
                return null;
            });
        } catch (Exception e) {
            String msg = e instanceof AiCallException ae && ae.getMessage() != null
                    ? ae.getMessage() : "回复生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
            log.warn("[对谈] 回复流式失败: project={} session={} : {}", projectId, session.getId(), e.getMessage());
            sink.send("error", Map.of("message", msg));
            return;
        }
        // 停止语义：连接已断开 → 本轮回复不落库（丢弃半截生成）
        if (broken[0]) {
            log.info("[对谈] 连接已断开（用户停止），本轮回复不落库: session={}", session.getId());
            return;
        }
        // M4 用量记账（scene=dialogue_chat；失败仅告警不阻断）
        recordChatUsage(userId, projectId, session, request, answer.toString(), usage[0]);
        // ⑤ 回复落库 + done
        try {
            ActorDialogueMessage saved = messageService.appendAssistant(projectId, session.getId(),
                    session.getCharacterRef(), answer.toString(), metaJson);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", saved.getId());
            done.put("content", answer.toString());
            done.put("characterRef", session.getCharacterRef());
            done.put("characterName", characterNameOf(session));
            done.put("seq", saved.getSeq());
            sink.send("done", done);
        } catch (Exception e) {
            log.warn("[对谈] 回复落库失败（回答已生成）: {}", e.getMessage());
            sink.send("done", Map.of("content", answer.toString(), "characterRef", session.getCharacterRef()));
        }
        // M4 长对话滚动窗口自动摘要（done 已推送后触发；超窗才发起 AI 摘要，零开销）
        if (summaryService != null) {
            try {
                summaryService.maybeSummarize(projectId, userId, session.getId());
            } catch (Exception e) {
                log.warn("[对谈] 自动摘要触发失败（fail-open，不影响本轮回复）: {}", e.getMessage());
            }
        }
    }

    /**
     * 用量记账（M4：复用 {@link UsageLogService}，scene=dialogue_chat）。
     * 供应商返回真实 usage 用之，否则按 {@link TokenEstimator} 本地估算兜底；
     * 记账失败仅告警，绝不影响回复主流程。
     */
    private void recordChatUsage(Long userId, Long projectId, ActorDialogueSession session,
                                 AiChatRequest request, String answer, AiUsage usage) {
        if (usageLogService == null) {
            return;
        }
        try {
            Integer in = usage != null && usage.promptTokens() != null ? usage.promptTokens()
                    : TokenEstimator.estimatePromptTokens(request.messages());
            Integer out = usage != null && usage.completionTokens() != null ? usage.completionTokens()
                    : TokenEstimator.estimateTokens(answer);
            usageLogService.record(userId, projectId, NpcRefs.idOf(session.getCharacterRef()), null, null,
                    SCENE_USAGE, in, out, usage == null ? null : usage.cacheHitTokens(),
                    usage == null ? null : usage.cacheMissTokens(), null);
        } catch (Exception e) {
            log.warn("[对谈] 用量记账失败（忽略，不影响回复）: {}", e.getMessage());
        }
    }

    /** 定位重写目标：messageId 指定（须为 assistant）或回退会话最后一条 assistant；找不到返回 null。 */
    private ActorDialogueMessage resolveTarget(List<ActorDialogueMessage> all, Long messageId) {
        if (messageId != null) {
            for (ActorDialogueMessage m : all) {
                if (m.getId() != null && m.getId().equals(messageId) && "assistant".equals(m.getRole())) {
                    return m;
                }
            }
            return null;
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("assistant".equals(all.get(i).getRole())) {
                return all.get(i);
            }
        }
        return null;
    }

    /** 角色名（会话角色引用解析；失败回退「角色」）。 */
    private String characterNameOf(ActorDialogueSession session) {
        if (session == null || session.getCharacterRef() == null) {
            return "角色";
        }
        String name = nameOf(session.getCharacterRef());
        return name == null || name.isBlank() ? "角色" : name;
    }

    /** 角色引用 → 角色名（主表直查；失败返回 null）。 */
    private String nameOf(String characterRef) {
        if (characterRepository == null) {
            return null;
        }
        try {
            Long id = NpcRefs.idOf(characterRef);
            if (id == null) {
                return null;
            }
            return characterRepository.findById(id)
                    .map(com.holzyn.actor.domain.npc.profile.entity.ActorCharacter::getName)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
