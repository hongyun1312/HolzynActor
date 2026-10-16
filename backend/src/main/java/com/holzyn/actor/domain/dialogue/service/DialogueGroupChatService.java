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
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
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
 * 对谈群聊回复编排（AI 调用，<b>不得带 {@code @Transactional}</b>）。
 * <p>职责（设计 §6.4/§8.2）：玩家发言 → <b>编排在场角色回应</b>——清醒成员
 * 全员依次（round-robin），<b>每回应独立 AI 调用</b>（复用单聊回应生成形态，串行流式，
 * 各自上下文=本人角色卡 + 场景段 + 【对家】最近他人发言 + 本群聊历史）；角色可接玩家
 * 的话、也可接其他角色的话（历史自然搭话）。</p>
 * <p><b>护栏（复用既有群聊编排 F1~F5 根治经验）</b>：</p>
 * <ul>
 *   <li>清醒过滤：睡着成员跳过（{@code DialogueGroupService.awakeMembers}，fail-open）；</li>
 *   <li>连选守卫：上一条群聊发言者与本轮成员相同 → 跳过（防自言自语/复读）；</li>
 *   <li>无进展检测：生成内容与最近一条群聊消息 Dice 重叠 ≥ {@value #NO_PROGRESS_DICE} → 跳过不落库；</li>
 *   <li>预算：每轮成员回应上限 {@value #ROUND_MEMBER_CAP}；</li>
 *   <li>失败跳过继续：单成员 AI 失败 → 该成员 error 事件，整轮继续（F2）；</li>
 *   <li>停止语义：流式期间连接断开 → 终止本轮不落库。</li>
 * </ul>
 * <p><b>SSE 事件契约（群聊）</b>：{@code meta(sessionId/type=group/total)} →
 * 每成员 {@code group-member(index/total/characterRef/characterName)} →
 * {@code token(delta/characterRef)*} → {@code group-member-done(messageId/content/characterRef/characterName/seq)}
 * ｜ {@code error(memberRef/message)} → 整轮 {@code done(sessionId/replied/skipped/failed)}。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用，不得带 {@code @Transactional}
 * （由 {@code AiCallOutsideTransactionTest.dialogueGroupChatIsNotTransactional} 反射断言守护）；
 * 落库全部委托 {@link DialogueMessageService}（短事务）；成员读取走 {@link DialogueGroupService}（零 AI）。</p>
 */
@Slf4j
@Service
public class DialogueGroupChatService {

    /** 群聊逐角色回复场景码（trace/模板版本留痕）。 */
    public static final String SCENE_GROUP_REPLY = "dialogue_group_reply";

    /** 群聊聊天用量记账场景码（UsageLogService 维度；与 trace 场景 dialogue_group_reply 区分） */
    public static final String SCENE_USAGE_GROUP = "dialogue_group_chat";

    /** 每轮成员回应上限（护栏预算）。 */
    static final int ROUND_MEMBER_CAP = 6;

    /** 无进展检测阈值：新回复与最近一条群聊消息 Dice 重叠 ≥ 该值 → 跳过不落库。 */
    static final double NO_PROGRESS_DICE = 0.9;

    /** 会话服务（会话隔离校验） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（短事务落库） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** 群聊成员服务（场景化成员/清醒过滤） */
    @Lazy
    @Autowired(required = false)
    private DialogueGroupService groupService;

    /** 上下文装配器 */
    @Lazy
    @Autowired(required = false)
    private DialogueContextAssembler assembler;

    /** 玩家-角色关系服务（玩家名） */
    @Lazy
    @Autowired(required = false)
    private DialogueRelationService relationService;

    /** 滚动窗口自动摘要服务（M4：群聊轮后触发超窗摘要） */
    @Lazy
    @Autowired(required = false)
    private DialogueSummaryService summaryService;

    /** 用量日志（M5：scene=dialogue_group_chat 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** AI 网关（流式） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 角色主表仓库（角色名解析） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 一轮群聊（SSE 串行流式逐角色回应）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID（须为群聊）
     * @param content   玩家发言（非空）
     * @param sink      SSE 输出口（群聊事件契约见类注释）
     */
    public void groupChat(Long projectId, Long userId, Long sessionId, String content, DialogueChatService.SseSink sink) {
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        if (!"group".equals(session.getType())) {
            throw new BizException(400, "仅群聊会话支持群聊发言");
        }
        // ① 玩家消息落库（短事务）
        messageService.appendUser(projectId, sessionId, content);
        // ② 历史（剔除刚落库的本轮发言——防「上一条」误判为自身）
        List<ActorDialogueMessage> history = messageService.listEntities(projectId, sessionId);
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).getRole())) {
            history = history.subList(0, history.size() - 1);
        }
        // ③ 成员：清醒过滤（全员不清醒 → 仍按原名单 fail-open 兜底）
        List<DialogueGroupMemberVO> members = groupService.members(projectId, sessionId);
        List<DialogueGroupMemberVO> awake = groupService.awakeMembers(projectId, sessionId);
        List<DialogueGroupMemberVO> roster = awake.isEmpty() ? members : awake;
        if (roster.isEmpty()) {
            sink.send("error", Map.of("message", "群聊里还没有成员，先换场或拉人"));
            return;
        }
        // ④ 全员依次（round-robin）：每回应独立 AI 调用，串行流式
        List<String> allNames = members.stream().map(m -> m.characterName() == null
                ? nameOf(m.characterRef()) : m.characterName()).toList();
        String playerName = assembler == null ? "你" : playerNameOf(userId);
        sink.send("meta", Map.of("sessionId", session.getId(), "type", "group", "total", roster.size()));
        List<ActorDialogueMessage> running = new ArrayList<>(history); // 本轮已产出回复追加，供后续成员看到
        int replied = 0, skipped = 0, failed = 0;
        boolean aborted = false;
        for (int i = 0; i < roster.size() && i < ROUND_MEMBER_CAP; i++) {
            DialogueGroupMemberVO member = roster.get(i);
            // 连选守卫：上一条群聊发言者与本人相同 → 跳过（防自言自语/复读）
            ActorDialogueMessage last = lastGroupMessage(running);
            if (last != null && member.characterRef().equals(last.getCharacterRef())) {
                skipped++;
                continue;
            }
            sink.send("group-member", Map.of("index", i + 1, "total", roster.size(),
                    "characterRef", member.characterRef(), "characterName", member.characterName()));
            Outcome out = streamMemberReply(projectId, userId, session, member, playerName,
                    content, running, allNames, sink);
            if (out.aborted()) {
                aborted = true;
                break;
            }
            if (out.message() != null) {
                running.add(out.message());
                replied++;
            } else if (out.skippedDup()) {
                skipped++;
            } else {
                failed++;
            }
        }
        sink.send("done", Map.of("sessionId", session.getId(), "replied", replied,
                "skipped", skipped, "failed", failed, "aborted", aborted));
        // M4 长对话滚动窗口自动摘要（done 后触发；超窗才发起 AI 摘要，零开销）
        if (summaryService != null) {
            try {
                summaryService.maybeSummarize(projectId, userId, sessionId);
            } catch (Exception e) {
                log.warn("[群聊] 自动摘要触发失败（fail-open，不影响本轮回复）: {}", e.getMessage());
            }
        }
    }

    /** 单成员回复结果。 */
    private record Outcome(ActorDialogueMessage message, boolean skippedDup, boolean aborted) {
        static Outcome saved(ActorDialogueMessage m) {
            return new Outcome(m, false, false);
        }
        static Outcome dup() {
            return new Outcome(null, true, false);
        }
        static Outcome failed() {
            return new Outcome(null, false, false);
        }
        static Outcome abort() {
            return new Outcome(null, false, true);
        }
    }

    /**
     * 单成员独立 AI 调用（串行流式生成 → 无进展检测 → 用量记账 → 落库）。
     * <p>失败 fail-open（F2）：单成员 error 事件，整轮继续。</p>
     */
    private Outcome streamMemberReply(Long projectId, Long userId, ActorDialogueSession session,
                                      DialogueGroupMemberVO member, String playerName, String content,
                                      List<ActorDialogueMessage> running, List<String> allNames,
                                      DialogueChatService.SseSink sink) {
        // 上下文装配（本人 L0 稳定前缀 + 群聊 L1 动态段）
        DialogueContextAssembler.ComposedContext ctx;
        try {
            ctx = assembler.composeGroup(projectId, userId, session, member.characterRef(),
                    member.characterName(), playerName, allNames, content, running);
        } catch (Exception e) {
            log.warn("[群聊] 上下文装配失败，降级最小上下文: {}", e.getMessage());
            ctx = new DialogueContextAssembler.ComposedContext(
                    "你是" + member.characterName() + "，绝不能跳出角色。", "【本轮】" + content);
        }
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_GROUP_REPLY);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", ctx.system()),
                        new AiChatRequest.ChatMessage("user", ctx.user())),
                spec.temperature(), spec.maxOutputTokens(), false);
        StringBuilder answer = new StringBuilder();
        boolean[] broken = {false};
        AiUsage[] usage = {null};
        try {
            AiCallContext.run(SCENE_GROUP_REPLY, NpcRefs.idOf(member.characterRef()), (Supplier<Void>) () -> {
                aiChatGateway.chatCompletionStream(userId, projectId, null, request,
                        delta -> {
                            if (broken[0]) {
                                return;
                            }
                            answer.append(delta);
                            if (!sink.send("token", Map.of("delta", delta, "characterRef", member.characterRef()))) {
                                broken[0] = true;
                            }
                        }, (AiUsage u) -> usage[0] = u);
                return null;
            });
        } catch (Exception e) {
            String msg = e instanceof AiCallException ae && ae.getMessage() != null
                    ? ae.getMessage() : "回复生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
            log.warn("[群聊] 成员回复失败（跳过继续）: ref={} : {}", member.characterRef(), e.getMessage());
            sink.send("error", Map.of("memberRef", member.characterRef(), "message", msg));
            return Outcome.failed();
        }
        // 停止语义：连接已断开 → 终止整轮（不落库）
        if (broken[0]) {
            log.info("[群聊] 连接已断开（用户停止），本轮终止不落库: session={}", session.getId());
            return Outcome.abort();
        }
        String text = answer.toString().trim();
        if (text.isBlank()) {
            log.warn("[群聊] 成员回复为空（跳过）: ref={}", member.characterRef());
            sink.send("error", Map.of("memberRef", member.characterRef(), "message", "回复为空"));
            return Outcome.failed();
        }
        // 无进展检测：与最近一条群聊消息 Dice 重叠 ≥ 阈值 → 跳过不落库（防复读/空转）
        ActorDialogueMessage last = lastGroupMessage(running);
        if (last != null && MemoryDedup.overlapScore(text, last.getContent()) >= NO_PROGRESS_DICE) {
            log.info("[群聊] 无进展（与上一条高度重复），跳过不落库: ref={}", member.characterRef());
            return Outcome.dup();
        }
        // M5 用量记账（scene=dialogue_group_chat；失败仅告警不阻断）
        recordUsage(userId, projectId, member, request, text, usage[0]);
        // 落库 + 单成员完成
        try {
            ActorDialogueMessage saved = messageService.appendGroup(projectId, session.getId(),
                    member.characterRef(), text, null);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", saved.getId());
            done.put("content", text);
            done.put("characterRef", member.characterRef());
            done.put("characterName", member.characterName());
            done.put("seq", saved.getSeq());
            sink.send("group-member-done", done);
            return Outcome.saved(saved);
        } catch (Exception e) {
            log.warn("[群聊] 回复落库失败（回答已生成，跳过落库）: {}", e.getMessage());
            sink.send("group-member-done", Map.of("content", text,
                    "characterRef", member.characterRef(), "characterName", member.characterName()));
            return Outcome.saved(null);
        }
    }

    /** 最近一条群聊/角色消息（空返回 null）。 */
    private ActorDialogueMessage lastGroupMessage(List<ActorDialogueMessage> list) {
        if (list == null) {
            return null;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            ActorDialogueMessage m = list.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if ("group".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                return m;
            }
        }
        return null;
    }

    /** 用量记账（M5：复用 {@link UsageLogService}，scene=dialogue_group_chat；失败仅告警）。 */
    private void recordUsage(Long userId, Long projectId, DialogueGroupMemberVO member,
                             AiChatRequest request, String answer, AiUsage usage) {
        if (usageLogService == null) {
            return;
        }
        try {
            Integer in = usage != null && usage.promptTokens() != null ? usage.promptTokens()
                    : TokenEstimator.estimatePromptTokens(request.messages());
            Integer out = usage != null && usage.completionTokens() != null ? usage.completionTokens()
                    : TokenEstimator.estimateTokens(answer);
            usageLogService.record(userId, projectId, NpcRefs.idOf(member.characterRef()), null, null,
                    SCENE_USAGE_GROUP, in, out, usage == null ? null : usage.cacheHitTokens(),
                    usage == null ? null : usage.cacheMissTokens(), null);
        } catch (Exception e) {
            log.warn("[群聊] 用量记账失败（忽略，不影响回复）: {}", e.getMessage());
        }
    }

    /** 玩家名（关系服务读取失败回退「你」）。 */
    private String playerNameOf(Long userId) {
        if (relationService == null) {
            return "你";
        }
        try {
            String name = relationService.playerNameOf(userId);
            return name == null || name.isBlank() ? "你" : name;
        } catch (Exception e) {
            return "你";
        }
    }

    /** 角色名（主表直查；失败返回「角色」）。 */
    private String nameOf(String characterRef) {
        if (characterRepository == null || characterRef == null) {
            return "角色";
        }
        try {
            Long id = NpcRefs.idOf(characterRef);
            if (id == null) {
                return "角色";
            }
            String n = characterRepository.findById(id)
                    .map(com.holzyn.actor.domain.npc.profile.entity.ActorCharacter::getName).orElse(null);
            return n == null || n.isBlank() ? "角色" : n;
        } catch (Exception e) {
            return "角色";
        }
    }
}
