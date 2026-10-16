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
import com.holzyn.actor.domain.assistant.entity.ActorAssistantMessage;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantSession;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantMessageRepository;
import com.holzyn.actor.domain.assistant.repository.ActorAssistantSessionRepository;
import com.holzyn.actor.domain.assistant.vo.AssistantMessageVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 助手会话服务（纯本地 DB，零 AI）。
 * <p>职责：会话的创建/列表/删除、消息的追加与读取、会话更新时间维护、首条消息生成标题。</p>
 * <p><b>事务纪律</b>：本服务<b>不调用任何 AI</b>，方法可安全携带 {@code @Transactional}
 * （短事务，无跨 AI 持锁风险）；AI 调用全部在 {@link AssistantChatService}（无事务）内。
 * 会话删除会级联删除消息（同一事务内）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSessionService {

    /** 会话仓库 */
    private final ActorAssistantSessionRepository sessionRepository;

    /** 消息仓库 */
    private final ActorAssistantMessageRepository messageRepository;

    /** 默认标题截断长度（字符） */
    private static final int TITLE_MAX = 40;

    /**
     * 新建会话（标题可空=延迟到首条消息生成；默认模式叙事）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param title     会话标题（可空）
     * @param defaultMode 默认回答模式（narrative/fact；非法值回退 narrative）
     *
     * @return 新会话视图
     */
    @Transactional
    public AssistantSessionVO create(Long projectId, Long userId, String title, String defaultMode) {
        ActorAssistantSession s = new ActorAssistantSession();
        s.setProjectId(projectId);
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? null : trim(title));
        s.setDefaultMode(normalizeMode(defaultMode));
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        ActorAssistantSession saved = sessionRepository.save(s);
        return toVO(saved, 0L);
    }

    /**
     * 项目会话列表（按更新时间倒序，含消息数）。
     *
     * @param projectId 项目 ID
     *
     * @return 会话视图列表
     */
    public List<AssistantSessionVO> list(Long projectId) {
        return sessionRepository.findByProjectIdOrderByUpdatedAtDescIdDesc(projectId).stream()
                .map(s -> toVO(s, messageRepository.countBySessionId(s.getId())))
                .toList();
    }

    /**
     * 单会话（项目隔离校验；不存在抛 404）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 会话视图
     */
    public AssistantSessionVO get(Long projectId, Long sessionId) {
        return toVO(require(projectId, sessionId), messageRepository.countBySessionId(sessionId));
    }

    /**
     * 删除会话（级联删除全部消息）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     */
    @Transactional
    public void delete(Long projectId, Long sessionId) {
        ActorAssistantSession s = require(projectId, sessionId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(s);
        log.info("[助手] 删除会话 project={} session={}（含消息）", projectId, sessionId);
    }

    /**
     * 更新会话默认回答模式。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param defaultMode narrative/fact
     */
    @Transactional
    public void updateDefaultMode(Long projectId, Long sessionId, String defaultMode) {
        ActorAssistantSession s = require(projectId, sessionId);
        s.setDefaultMode(normalizeMode(defaultMode));
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    // ==================== M4：干预确认粒度 / 放行记录 ====================

    /**
     * 会话干预确认粒度（归一化：strict/standard/trust，非法回退 standard）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 确认粒度
     */
    public String confirmModeOf(Long projectId, Long sessionId) {
        return normalizeConfirmMode(require(projectId, sessionId).getConfirmMode());
    }

    /**
     * 更新会话干预确认粒度。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param mode      strict/standard/trust（非法回退 standard）
     */
    @Transactional
    public void updateConfirmMode(Long projectId, Long sessionId, String mode) {
        ActorAssistantSession s = require(projectId, sessionId);
        s.setConfirmMode(normalizeConfirmMode(mode));
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 该操作类型是否已在本会话放行（标准模式低风险首次确认后累积；approved_ops CSV）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param op        操作类型（talk/action/weather/...）
     */
    public boolean isApprovedOp(Long projectId, Long sessionId, String op) {
        String csv = require(projectId, sessionId).getApprovedOps();
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (part != null && part.trim().equals(op)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 放行某操作类型（标准模式：低风险操作首次确认后加入放行集）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param op        操作类型
     */
    @Transactional
    public void markApprovedOp(Long projectId, Long sessionId, String op) {
        if (op == null || op.isBlank()) {
            return;
        }
        ActorAssistantSession s = require(projectId, sessionId);
        String csv = s.getApprovedOps();
        StringBuilder sb = new StringBuilder();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty() && !t.equals(op)) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(t);
                }
            }
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(op);
        s.setApprovedOps(sb.toString());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 会话消息列表（时间正序）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 消息视图列表
     */
    public List<AssistantMessageVO> messages(Long projectId, Long sessionId) {
        require(projectId, sessionId);
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(AssistantSessionService::toMessageVO)
                .toList();
    }

    /**
     * 追加一条用户消息（会话标题为空时以首条用户消息生成标题）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param question  用户问题（非空）
     *
     * @return 落库的用户消息
     */
    @Transactional
    public ActorAssistantMessage appendUserMessage(Long projectId, Long sessionId, String question) {
        ActorAssistantSession s = require(projectId, sessionId);
        if (question == null || question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }
        if (s.getTitle() == null || s.getTitle().isBlank()) {
            s.setTitle(trim(question));
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        }
        ActorAssistantMessage m = new ActorAssistantMessage();
        m.setSessionId(sessionId);
        m.setProjectId(projectId);
        m.setRole("user");
        m.setContent(question);
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    /**
     * 追加一条助手消息（回答 + 结构化载荷）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param content   回答正文（可空=仅卡片）
     * @param mode      回答模式
     * @param cardJson  数据卡片 JSON（可空）
     * @param sourcesJson 来源标注 JSON（可空）
     * @param toolCallsJson 工具调用记录 JSON（可空）
     * @param quickFollowsJson 快速追问 JSON（可空）
     *
     * @return 落库的助手消息
     */
    @Transactional
    public ActorAssistantMessage appendAssistantMessage(Long projectId, Long sessionId, String content,
                                                        String mode, String cardJson, String sourcesJson,
                                                        String toolCallsJson, String quickFollowsJson) {
        ActorAssistantSession s = require(projectId, sessionId);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
        ActorAssistantMessage m = new ActorAssistantMessage();
        m.setSessionId(sessionId);
        m.setProjectId(projectId);
        m.setRole("assistant");
        m.setContent(content);
        m.setMode(normalizeMode(mode));
        m.setCardJson(cardJson);
        m.setSourcesJson(sourcesJson);
        m.setToolCallsJson(toolCallsJson);
        m.setQuickFollowsJson(quickFollowsJson);
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    /** 最近 N 条会话消息（时间正序；供上下文窗口；N<=0 返回空）。 */
    public List<ActorAssistantMessage> recentMessages(Long sessionId, int window) {
        if (window <= 0) {
            return List.of();
        }
        List<ActorAssistantMessage> desc = messageRepository.findBySessionIdOrderByIdDesc(sessionId);
        if (desc.size() > window) {
            desc = desc.subList(0, window);
        }
        return desc.reversed();
    }

    /** 项目隔离取会话（不存在抛 404）。 */
    private ActorAssistantSession require(Long projectId, Long sessionId) {
        return sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new BizException(404, "会话不存在"));
    }

    /** 会话 → 视图。 */
    private AssistantSessionVO toVO(ActorAssistantSession s, Long messageCount) {
        return new AssistantSessionVO(s.getId(), s.getProjectId(), s.getTitle(), s.getDefaultMode(),
                normalizeConfirmMode(s.getConfirmMode()), messageCount, s.getCreatedAt(), s.getUpdatedAt());
    }

    /** 消息实体 → 视图。 */
    private static AssistantMessageVO toMessageVO(ActorAssistantMessage m) {
        return new AssistantMessageVO(m.getId(), m.getSessionId(), m.getRole(), m.getContent(), m.getMode(),
                m.getCardJson(), m.getSourcesJson(), m.getToolCallsJson(), m.getQuickFollowsJson(),
                m.getCreatedAt());
    }

    /** 模式归一化（非法值回退 narrative）。 */
    private static String normalizeMode(String mode) {
        if (mode != null && "fact".equals(mode.trim())) {
            return "fact";
        }
        return "narrative";
    }

    /** 确认粒度归一化（非法值回退 standard）。 */
    private static String normalizeConfirmMode(String mode) {
        if (mode != null && ("strict".equals(mode.trim()) || "trust".equals(mode.trim()))) {
            return mode.trim();
        }
        return "standard";
    }

    /** 截断到标题上限（防长问题撑爆标题列）。 */
    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }
}
