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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorTalkWorldbook;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorTalkWorldbookRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueWorldbookVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对谈世界书服务（零 AI，纯本地 DB 短事务）。
 * <p>职责：<b>会话级世界书条目</b>（M4 记忆/世界书等价物——关键词触发，对齐 SillyTavern
 * Lorebook 关键词激活的轻量版）的 CRUD 与命中扫描：关键词命中最近 N 条消息正文 →
 * 对应条目以【世界书片段】注入对谈上下文 L1 动态段（标注触发关键词〔来源〕）。
 * 与「检索式世界书」（{@code KnowledgeRetrievalService} RAG 多路召回，项目级知识）互补——
 * 本服务承载用户手工维护的会话级关键词→知识片段映射。</p>
 * <p><b>写入口</b>：本服务是 actor_talk_worldbook 的唯一写入口（CRUD 均短事务，
 * 零 AI 可安全携带 {@code @Transactional}）；只读命中扫描供
 * {@link DialogueContextAssembler} 装配使用。</p>
 */
@Slf4j
@Service
public class DialogueWorldbookService {

    /** 世界书片段默认命中上限（每轮注入条目数） */
    static final int DEFAULT_HIT_LIMIT = 3;

    /** 会话仓库（项目隔离校验） */
    @Autowired(required = false)
    private ActorDialogueSessionRepository sessionRepository;

    /** 世界书条目仓库 */
    @Autowired(required = false)
    private ActorTalkWorldbookRepository worldbookRepository;

    /**
     * 会话世界书条目列表（管理视图，含停用条目；按 priority 升序）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 条目视图列表（空列表）
     */
    public List<DialogueWorldbookVO> list(Long projectId, Long sessionId) {
        requireSession(projectId, sessionId);
        if (worldbookRepository == null) {
            return List.of();
        }
        return worldbookRepository.findBySessionIdOrderByPriorityAscIdAsc(sessionId).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 新增/更新世界书条目（同会话同关键词唯一：已存在 → 更新内容与开关）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param keyword   触发关键词（非空）
     * @param content   知识片段内容（可空=空片段，命中仅提示关键词）
     * @param enabled   启用标记（null=true）
     * @param priority  排序权重（null=0）
     *
     * @return 保存后的条目视图
     */
    @Transactional
    public DialogueWorldbookVO upsert(Long projectId, Long sessionId, String keyword, String content,
                                      Boolean enabled, Integer priority) {
        requireSession(projectId, sessionId);
        String k = keyword == null ? "" : keyword.trim();
        if (k.isBlank()) {
            throw new BizException(400, "世界书关键词不能为空");
        }
        if (k.length() > 100) {
            throw new BizException(400, "世界书关键词过长（≤100 字符）");
        }
        ActorTalkWorldbook entry = worldbookRepository == null ? null
                : worldbookRepository.findBySessionIdAndKeyword(sessionId, k).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (entry == null) {
            entry = new ActorTalkWorldbook();
            entry.setSessionId(sessionId);
            entry.setProjectId(projectId);
            entry.setKeyword(k);
            entry.setCreatedAt(now);
        }
        entry.setContent(content == null ? "" : content);
        entry.setEnabled(enabled == null || enabled);
        entry.setPriority(priority == null ? 0 : priority);
        entry.setUpdatedAt(now);
        return toVO(worldbookRepository.save(entry));
    }

    /**
     * 更新已存在条目（按条目 ID，归属校验；不存在抛 404）。
     */
    @Transactional
    public DialogueWorldbookVO update(Long projectId, Long sessionId, Long entryId, String keyword,
                                      String content, Boolean enabled, Integer priority) {
        requireSession(projectId, sessionId);
        ActorTalkWorldbook entry = requireEntry(projectId, sessionId, entryId);
        String k = keyword == null ? "" : keyword.trim();
        if (k.isBlank()) {
            throw new BizException(400, "世界书关键词不能为空");
        }
        // 关键词变更需校验同会话内不与其他条目冲突
        if (!k.equals(entry.getKeyword()) && worldbookRepository != null
                && worldbookRepository.findBySessionIdAndKeyword(sessionId, k).isPresent()) {
            throw new BizException(400, "关键词「" + k + "」已存在");
        }
        entry.setKeyword(k);
        entry.setContent(content == null ? "" : content);
        entry.setEnabled(enabled == null || enabled);
        entry.setPriority(priority == null ? 0 : priority);
        entry.setUpdatedAt(LocalDateTime.now());
        return toVO(worldbookRepository.save(entry));
    }

    /**
     * 删除世界书条目（归属校验；不存在抛 404）。
     */
    @Transactional
    public void remove(Long projectId, Long sessionId, Long entryId) {
        requireSession(projectId, sessionId);
        ActorTalkWorldbook entry = requireEntry(projectId, sessionId, entryId);
        worldbookRepository.delete(entry);
    }

    /**
     * 关键词命中扫描（只读，装配用）：在最近消息正文中匹配已启用条目的关键词，
     * 去重后按 priority 升序（+ id 升序）返回，上限 {@code limit}（默认 3）。
     * <p>匹配语义：条目标签字符串（精确子串命中）——对齐 SillyTavern 关键词激活的
     * 轻量版（不做递归/正则），一条消息命中同关键词只计一次。</p>
     *
     * @param sessionId  会话 ID
     * @param recentTexts 最近 N 条消息正文（含本轮发言）
     * @param limit       命中上限（null/<=0 → 默认 3）
     *
     * @return 命中条目（空列表）
     */
    public List<ActorTalkWorldbook> keywordHits(Long sessionId, List<String> recentTexts, Integer limit) {
        if (worldbookRepository == null || sessionId == null) {
            return List.of();
        }
        int cap = limit == null || limit <= 0 ? DEFAULT_HIT_LIMIT : limit;
        List<ActorTalkWorldbook> enabled;
        try {
            enabled = worldbookRepository.findBySessionIdAndEnabledTrueOrderByPriorityAscIdAsc(sessionId);
        } catch (Exception e) {
            log.warn("[对谈世界书] 条目读取失败（跳过，零阻断）: {}", e.getMessage());
            return List.of();
        }
        if (enabled.isEmpty() || recentTexts == null || recentTexts.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<ActorTalkWorldbook> hits = new ArrayList<>();
        for (ActorTalkWorldbook entry : enabled) {
            if (entry == null || entry.getKeyword() == null || entry.getKeyword().isBlank()) {
                continue;
            }
            if (seen.contains(entry.getId())) {
                continue;
            }
            if (containsKeyword(recentTexts, entry.getKeyword())) {
                seen.add(entry.getId());
                hits.add(entry);
                if (hits.size() >= cap) {
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 渲染【世界书片段】段（只读，装配用）：命中条目按「〔关键词「X」〕片段」注入。
     *
     * @param sessionId   会话 ID
     * @param recentTexts 最近 N 条消息正文（含本轮发言）
     * @param limit       命中上限
     *
     * @return 世界书段文本（无命中返回空串）
     */
    public String worldbookContext(Long sessionId, List<String> recentTexts, Integer limit) {
        List<ActorTalkWorldbook> hits = keywordHits(sessionId, recentTexts, limit);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【世界书片段】");
        for (ActorTalkWorldbook h : hits) {
            sb.append("\n- 〔关键词「").append(h.getKeyword().trim()).append("」〕");
            if (h.getContent() != null && !h.getContent().isBlank()) {
                sb.append(h.getContent().trim());
            } else {
                sb.append("（空片段）");
            }
        }
        return sb.toString();
    }

    // ==================== 内部 ====================

    /** 项目隔离取会话（不存在抛 404）。 */
    private ActorDialogueSession requireSession(Long projectId, Long sessionId) {
        if (sessionRepository == null) {
            throw new BizException(404, "对谈会话不存在");
        }
        return sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new BizException(404, "对谈会话不存在"));
    }

    /** 项目隔离取条目（不存在抛 404）。 */
    private ActorTalkWorldbook requireEntry(Long projectId, Long sessionId, Long entryId) {
        if (worldbookRepository == null) {
            throw new BizException(404, "世界书条目不存在");
        }
        return worldbookRepository.findById(entryId)
                .filter(e -> e.getSessionId() != null && e.getSessionId().equals(sessionId)
                        && e.getProjectId() != null && e.getProjectId().equals(projectId))
                .orElseThrow(() -> new BizException(404, "世界书条目不存在"));
    }

    /** 最近消息正文中是否包含关键词（任一消息命中即 true）。 */
    private static boolean containsKeyword(List<String> recentTexts, String keyword) {
        String k = keyword.trim();
        if (k.isBlank()) {
            return false;
        }
        for (String t : recentTexts) {
            if (t != null && t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 条目 → 视图。 */
    private DialogueWorldbookVO toVO(ActorTalkWorldbook e) {
        return new DialogueWorldbookVO(e.getId(), e.getSessionId(), e.getProjectId(), e.getKeyword(),
                e.getContent(), e.getEnabled(), e.getPriority(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
