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

import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorTalkWorldbook;
import com.holzyn.actor.domain.dialogue.vo.DialogueContextConfigVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueUsageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对谈上下文配置/用量服务（零 AI，只读）。
 * <p>职责：① 上下文配置视图（M4：作者注释 + 滚动窗口摘要 + 世界书条目数）——供前端
 * 上下文面板展示与编辑回填；② 上下文用量估算（M4：消息/段字符 + 估算 token，复用
 * {@link TokenEstimator}）——token 用量实时显示。真实 token 由供应商返回并记入
 * actor_usage_log（scene=dialogue_chat 等），本服务只做本地展示估算。</p>
 * <p><b>边界</b>：本服务零 AI、只读；配置写入口 = {@link DialogueSessionService}
 * （作者注释/摘要）与 {@link DialogueWorldbookService}（世界书条目）。</p>
 */
@Slf4j
@Service
public class DialogueUsageService {

    /** 会话服务（会话读取） */
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务（消息实体读取） */
    @Autowired(required = false)
    private DialogueMessageService messageService;

    /** 世界书服务（关键词命中扫描 + 条目统计） */
    @Autowired(required = false)
    private DialogueWorldbookService worldbookService;

    /** 世界书命中扫描窗口（与装配器一致：最近 6 条历史） */
    private static final int RECENT_WINDOW = 6;

    /**
     * 上下文配置视图（作者注释 + 摘要水位 + 世界书条目数）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 配置视图
     */
    public DialogueContextConfigVO contextConfigOf(Long projectId, Long sessionId) {
        ActorDialogueSession s = sessionService.require(projectId, sessionId);
        Long worldbookCount = 0L;
        if (worldbookService != null) {
            try {
                worldbookCount = (long) worldbookService.list(projectId, sessionId).size();
            } catch (Exception e) {
                log.debug("[对谈用量] 世界书条目统计失败（按 0 处理）: {}", e.getMessage());
            }
        }
        return new DialogueContextConfigVO(
                s.getAuthorNote() == null ? "" : s.getAuthorNote(),
                s.getAuthorNoteDepth() == null ? 1 : s.getAuthorNoteDepth(),
                s.getAuthorNoteFrequency() == null ? 1 : s.getAuthorNoteFrequency(),
                s.getSummary() == null ? "" : s.getSummary(),
                s.getSummaryMessageCount() == null ? 0 : s.getSummaryMessageCount(),
                DialogueSummaryService.HISTORY_WINDOW,
                worldbookCount);
    }

    /**
     * 上下文用量估算（消息正文 + 摘要 + 作者注释 + 世界书命中片段）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 用量视图
     */
    public DialogueUsageVO usageOf(Long projectId, Long sessionId) {
        ActorDialogueSession s = sessionService.require(projectId, sessionId);
        List<ActorDialogueMessage> messages = messageService == null ? List.of()
                : safeList(projectId, sessionId);
        int messageChars = 0;
        int messageTokens = 0;
        for (ActorDialogueMessage m : messages) {
            if (m == null || m.getContent() == null) {
                continue;
            }
            messageChars += m.getContent().length();
            messageTokens += TokenEstimator.estimateTokens(m.getContent());
        }
        // 摘要段
        String summary = s.getSummary() == null ? "" : s.getSummary();
        int summaryChars = summary.length();
        int summaryTokens = summary.isBlank() ? 0 : TokenEstimator.estimateTokens(summary);
        // 作者注释段（与装配器同频率门控：每 N 条注入一次）
        int authorNoteChars = 0;
        int authorNoteTokens = 0;
        if (s.getAuthorNote() != null && !s.getAuthorNote().isBlank()
                && authorNoteActive(s, messages.size())) {
            authorNoteChars = s.getAuthorNote().length();
            authorNoteTokens = TokenEstimator.estimateTokens(s.getAuthorNote());
        }
        // 世界书命中片段段（扫描最近 RECENT_WINDOW 条正文）
        int worldbookChars = 0;
        int worldbookTokens = 0;
        if (worldbookService != null && s.getId() != null) {
            List<String> recent = recentTexts(messages);
            try {
                List<ActorTalkWorldbook> hits = worldbookService.keywordHits(s.getId(), recent, null);
                for (ActorTalkWorldbook h : hits) {
                    if (h == null || h.getContent() == null) {
                        continue;
                    }
                    worldbookChars += h.getContent().length();
                    worldbookTokens += TokenEstimator.estimateTokens(h.getContent());
                }
            } catch (Exception e) {
                log.debug("[对谈用量] 世界书命中估算失败（按 0 处理）: {}", e.getMessage());
            }
        }
        int totalChars = messageChars + summaryChars + authorNoteChars + worldbookChars;
        int totalTokens = messageTokens + summaryTokens + authorNoteTokens + worldbookTokens;
        return new DialogueUsageVO(sessionId, messages.size(), totalChars, totalTokens,
                summaryChars, authorNoteChars, worldbookChars);
    }

    // ==================== 内部 ====================

    /** 消息实体安全读取（失败返回空列表）。 */
    private List<ActorDialogueMessage> safeList(Long projectId, Long sessionId) {
        try {
            return messageService.listEntities(projectId, sessionId);
        } catch (Exception e) {
            log.debug("[对谈用量] 消息读取失败（按空处理）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 最近 RECENT_WINDOW 条消息正文（世界书命中扫描用）。 */
    private List<String> recentTexts(List<ActorDialogueMessage> messages) {
        List<String> out = new ArrayList<>();
        int from = Math.max(0, messages.size() - RECENT_WINDOW);
        for (int i = from; i < messages.size(); i++) {
            ActorDialogueMessage m = messages.get(i);
            if (m != null && m.getContent() != null && !m.getContent().isBlank()) {
                out.add(m.getContent().trim());
            }
        }
        return out;
    }

    /** 作者注释频率门控（与装配器一致：每 N 条注入一次，null/<=0=恒注入）。 */
    private static boolean authorNoteActive(ActorDialogueSession s, int historySize) {
        Integer freq = s.getAuthorNoteFrequency();
        if (freq == null || freq <= 0) {
            return true;
        }
        return historySize % freq == 0;
    }
}
