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
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 对谈长对话滚动窗口自动摘要（AI 调用，<b>不得带 {@code @Transactional}</b>）。
 * <p>职责：对谈消息数超过滚动窗口（默认 24 条）后，把<b>超窗前段</b>折叠为 AI 摘要
 * （scene=dialogue_summary，PLAIN_TEXT，0.4/300），缓存到会话 {@code summary} 字段；
 * 摘要随对话滚动<b>增量更新</b>（{@code summaryMessageCount} 水位推进：只摘要新超窗的
 * 一批消息，与既有摘要合并）。装配时摘要以【对话摘要】注入 L1 动态段，保证长对话
 * 上下文不丢前情。</p>
 * <p><b>触发</b>：{@link DialogueChatService} 在每条角色回复落库后调用
 * {@link #maybeSummarize}；仅在「消息数 &gt; 窗口 且 新超窗批非空」时发起 AI 摘要，其余跳过
 * （零开销）。</p>
 * <p><b>架构不变量</b>：本服务含 AI 调用，不得带 {@code @Transactional}
 * （由 {@code AiCallOutsideTransactionTest.dialogueSummaryIsNotTransactional} 反射断言守护）；
 * 摘要写回经 {@link DialogueSessionService#updateSummary}（其自身短事务）承担。</p>
 * <p><b>fail-open</b>：AI 失败/超时/摘要为空 → 保留既有摘要不动（零阻断），下次触发重试。</p>
 */
@Slf4j
@Service
public class DialogueSummaryService {

    /** 摘要场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE_SUMMARY = "dialogue_summary";

    /** 滚动窗口条数（设计 §6.2 默认如 24 条；超窗前段折叠为摘要） */
    public static final int HISTORY_WINDOW = 24;

    /** 摘要输入批次上限（单批纳入的会话消息条数，控制 prompt 输入 token） */
    static final int SUMMARY_BATCH_LIMIT = 20;

    /** 会话服务（会话读取 + 摘要短事务写回） */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（会话消息实体读取） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** AI 网关（非流式摘要调用） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量日志（scene=dialogue_summary 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 滚动窗口摘要触发：消息数超窗且有新超窗批 → AI 摘要 → 短事务写回。
     * <p>无新批/消息数未超窗/依赖未装配 → 直接返回（零开销）；AI 失败 fail-open。</p>
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    public void maybeSummarize(Long projectId, Long userId, Long sessionId) {
        if (messageService == null || sessionService == null || aiChatGateway == null) {
            return;
        }
        List<ActorDialogueMessage> messages;
        try {
            messages = messageService.listEntities(projectId, sessionId);
        } catch (Exception e) {
            log.debug("[对谈摘要] 消息读取失败（跳过）: {}", e.getMessage());
            return;
        }
        if (messages == null || messages.size() <= HISTORY_WINDOW) {
            return;
        }
        ActorDialogueSession session;
        try {
            session = sessionService.require(projectId, sessionId);
        } catch (Exception e) {
            log.debug("[对谈摘要] 会话读取失败（跳过）: {}", e.getMessage());
            return;
        }
        int foldable = messages.size() - HISTORY_WINDOW;
        int watermark = session.getSummaryMessageCount() == null ? 0 : session.getSummaryMessageCount();
        if (foldable <= watermark) {
            return; // 无新超窗批（增量水位未前进）
        }
        // 新增超窗批 = [watermark, foldable)；已有摘要与新增批合并
        List<String> batch = new ArrayList<>();
        for (int i = Math.max(0, watermark); i < Math.min(foldable, watermark + SUMMARY_BATCH_LIMIT); i++) {
            ActorDialogueMessage m = messages.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            batch.add(label(m) + "：" + m.getContent().trim());
        }
        if (batch.isEmpty()) {
            return;
        }
        String existing = session.getSummary();
        String summary = summarize(userId, projectId, session, existing, batch);
        if (summary == null || summary.isBlank()) {
            return; // AI 失败/空摘要 fail-open
        }
        try {
            // 摘要写回：短事务（AI 已在事务外完成）
            int newWatermark = Math.min(foldable, watermark + SUMMARY_BATCH_LIMIT);
            sessionService.updateSummary(projectId, sessionId, summary, newWatermark);
            log.info("[对谈摘要] 会话 {} 摘要增量更新：水位 {} → {}，摘要 {} 字",
                    sessionId, watermark, newWatermark, summary.length());
        } catch (Exception e) {
            log.warn("[对谈摘要] 摘要写回失败（fail-open，下次重试）: {}", e.getMessage());
        }
    }

    /**
     * 调用 AI 生成/合并摘要（事务外）。
     *
     * @param userId    用户 ID
     * @param projectId 项目 ID
     * @param session   会话（角色引用 → 摘要场景归属）
     * @param existing  既有摘要（可空/空串=首轮摘要）
     * @param batch     新增超窗批消息行（「玩家/角色：内容」）
     *
     * @return 合并后的完整摘要（AI 失败返回 null）
     */
    String summarize(Long userId, Long projectId, ActorDialogueSession session, String existing,
                     List<String> batch) {
        String system = "你是对谈摘要记录员：把对话内容浓缩成简洁的中文摘要（保留关键事实、约定、承诺与情绪变化），"
                + "作为后续对话的前置记忆使用。只输出摘要正文，不要任何前后缀或解释。";
        StringBuilder user = new StringBuilder();
        if (existing != null && !existing.isBlank()) {
            user.append("【既有摘要】\n").append(existing.trim()).append("\n\n");
        }
        user.append("【新增对话】\n");
        for (String line : batch) {
            user.append(line).append("\n");
        }
        user.append("\n请输出合并既有摘要后的完整摘要（≤300 字）。");
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_SUMMARY);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", system),
                        new AiChatRequest.ChatMessage("user", user.toString())),
                spec.temperature(), spec.maxOutputTokens(), false);
        AiChatResult result;
        try {
            result = AiCallContext.run(SCENE_SUMMARY, NpcRefs.idOf(session.getCharacterRef()),
                    (Supplier<AiChatResult>) () -> aiChatGateway.chatCompletion(userId, projectId, null, request));
        } catch (AiCallException e) {
            log.warn("[对谈摘要] AI 摘要失败（fail-open 保留既有摘要）: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[对谈摘要] AI 摘要异常（fail-open 保留既有摘要）: {}", e.getMessage());
            return null;
        }
        String text = result == null || result.content() == null ? null : result.content().trim();
        if (text == null || text.isBlank()) {
            log.warn("[对谈摘要] AI 摘要为空（fail-open 保留既有摘要）");
            return null;
        }
        // 用量记账（scene=dialogue_summary；供应商未返回 usage 时按本地估算兜底）
        if (usageLogService != null) {
            try {
                Integer in = result.promptTokens() != null ? result.promptTokens()
                        : TokenEstimator.estimatePromptTokens(request.messages());
                Integer out = result.completionTokens() != null ? result.completionTokens()
                        : TokenEstimator.estimateTokens(text);
                usageLogService.record(userId, projectId, NpcRefs.idOf(session.getCharacterRef()),
                        result.providerId(), result.model(), SCENE_SUMMARY, in, out, result.cacheHitTokens(),
                        result.cacheMissTokens(), null);
            } catch (Exception e) {
                log.warn("[对谈摘要] 用量记账失败（忽略）: {}", e.getMessage());
            }
        }
        return text;
    }

    /** 消息角色标签（摘要行：玩家/角色）。 */
    private static String label(ActorDialogueMessage m) {
        if (m == null) {
            return "系统";
        }
        return "user".equals(m.getRole()) ? "玩家" : "角色";
    }
}
