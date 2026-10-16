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
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueMessageRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueMessageVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对谈消息服务（纯本地 DB，零 AI）。
 * <p>职责：对谈消息的追加（玩家/角色）、读取、<b>编辑/删除/换一条（swipe）</b>与 seq 会话内序号分配、
 * 会话更新时间维护。编辑/删除采用「作废后续链」截断语义（历史变更使后续回复失效，避免上下文错位）。</p>
 * <p><b>事务纪律</b>：本服务<b>不调用任何 AI</b>，方法可安全携带 {@code @Transactional}
 * （短事务，无跨 AI 持锁风险）；AI 生成全部在 {@link DialogueChatService}/{@link DialogueGreetingService}
 * （无事务）内完成后，再经本服务落库。</p>
 */
@Slf4j
@Service
public class DialogueMessageService {

    /** JSON 序列化器（静态只读，线程安全；与同域服务一致用 tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 消息仓库 */
    @Autowired(required = false)
    private ActorDialogueMessageRepository messageRepository;

    /** 会话仓库（会话隔离校验 + 更新时间维护） */
    @Autowired(required = false)
    private ActorDialogueSessionRepository sessionRepository;

    /** 角色主表仓库（消息角色名附带） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 追加一条玩家消息（role=user；刷新会话更新时间）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param content   玩家发言（非空）
     *
     * @return 落库的玩家消息
     */
    @Transactional
    public ActorDialogueMessage appendUser(Long projectId, Long sessionId, String content) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        if (content == null || content.isBlank()) {
            throw new BizException(400, "发言不能为空");
        }
        touch(s);
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setContent(content);
        m.setSeq(nextSeq(sessionId));
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    /**
     * 追加一条角色消息（role=assistant；刷新会话更新时间）。
     *
     * @param projectId    项目 ID
     * @param sessionId    会话 ID
     * @param characterRef 发言角色（npc:{id}）
     * @param content      回复正文
     * @param metaJson     元数据 JSON（可空）
     *
     * @return 落库的角色消息
     */
    @Transactional
    public ActorDialogueMessage appendAssistant(Long projectId, Long sessionId, String characterRef,
                                                String content, String metaJson) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        touch(s);
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setSessionId(sessionId);
        m.setRole("assistant");
        m.setCharacterRef(characterRef);
        m.setContent(content == null ? "" : content);
        m.setMetaJson(metaJson);
        m.setSeq(nextSeq(sessionId));
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    /**
     * 追加一条群聊角色消息（role=group；M5 群聊逐角色发言，含开场白）。
     *
     * @param projectId    项目 ID
     * @param sessionId    会话 ID
     * @param characterRef 发言角色（npc:{id}）
     * @param content      回复正文
     * @param metaJson     元数据 JSON（可空；群聊开场白传 {"kind":"greeting"}）
     *
     * @return 落库的群聊消息
     */
    @Transactional
    public ActorDialogueMessage appendGroup(Long projectId, Long sessionId, String characterRef,
                                            String content, String metaJson) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        touch(s);
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setSessionId(sessionId);
        m.setRole("group");
        m.setCharacterRef(characterRef);
        m.setContent(content == null ? "" : content);
        m.setMetaJson(metaJson);
        m.setSeq(nextSeq(sessionId));
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    /**
     * 会话消息列表（seq 正序；角色消息附角色名）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 消息视图列表
     */
    public List<DialogueMessageVO> list(Long projectId, Long sessionId) {
        requireSession(projectId, sessionId);
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 会话消息实体列表（seq 正序；上下文装配用，含空行不过滤——装配器自行处理）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     *
     * @return 消息实体列表
     */
    public List<ActorDialogueMessage> listEntities(Long projectId, Long sessionId) {
        requireSession(projectId, sessionId);
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    /**
     * 编辑玩家消息（仅 role=user；内容更新 + 作废其后所有消息——编辑历史使后续链失效，
     * 截断避免「新编辑的发言 + 旧回复」上下文错位）。零 AI，短事务。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param messageId 目标消息 ID
     * @param content   新内容（非空）
     *
     * @return 更新后的消息视图
     */
    @Transactional
    public DialogueMessageVO edit(Long projectId, Long sessionId, Long messageId, String content) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        ActorDialogueMessage m = messageRepository.findByIdAndSessionId(messageId, sessionId)
                .orElseThrow(() -> new BizException(404, "对谈消息不存在"));
        if (!"user".equals(m.getRole())) {
            throw new BizException(400, "只能编辑玩家的发言");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(400, "内容不能为空");
        }
        m.setContent(content);
        messageRepository.save(m);
        // 作废后续链：删除目标之后的所有消息
        truncateAfter(sessionId, messageId);
        touch(s);
        return toVO(m);
    }

    /**
     * 删除消息（含其后所有消息——删除作废后续链；零 AI，短事务）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param messageId 目标消息 ID
     */
    @Transactional
    public void remove(Long projectId, Long sessionId, Long messageId) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        messageRepository.findByIdAndSessionId(messageId, sessionId)
                .orElseThrow(() -> new BizException(404, "对谈消息不存在"));
        truncateFrom(sessionId, messageId);
        touch(s);
    }

    /**
     * 换一条（swipe）：把角色回复轮换到下一候选（metaJson.swipes 队列——重写/编辑时积累的旧回复）。
     * 零 AI，短事务。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param messageId 目标消息 ID
     *
     * @return 轮换后的消息视图
     */
    @Transactional
    public DialogueMessageVO swipe(Long projectId, Long sessionId, Long messageId) {
        ActorDialogueSession s = requireSession(projectId, sessionId);
        ActorDialogueMessage m = messageRepository.findByIdAndSessionId(messageId, sessionId)
                .orElseThrow(() -> new BizException(404, "对谈消息不存在"));
        if (!"assistant".equals(m.getRole())) {
            throw new BizException(400, "只有角色回复可以换一条");
        }
        List<String> swipes = parseSwipes(m.getMetaJson());
        if (swipes.isEmpty()) {
            throw new BizException(400, "没有可切换的备选回复（可先对这条回复点「重写」生成备选）");
        }
        String next = swipes.remove(0);
        swipes.add(m.getContent());
        m.setContent(next);
        m.setMetaJson(serializeSwipes(swipes));
        messageRepository.save(m);
        touch(s);
        return toVO(m);
    }

    /** 删除 id 严格大于 {@code messageId} 的消息（编辑作废后续链，目标保留）。 */
    private void truncateAfter(Long sessionId, Long messageId) {
        List<ActorDialogueMessage> tail = messageRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, messageId);
        if (!tail.isEmpty()) {
            messageRepository.deleteAll(tail);
        }
    }

    /** 删除 id 大于等于 {@code messageId} 的消息（删除/重写截断，含目标本身）。 */
    private void truncateFrom(Long sessionId, Long messageId) {
        List<ActorDialogueMessage> tail = messageRepository.findBySessionIdAndIdGreaterThanEqualOrderByIdAsc(sessionId, messageId);
        if (!tail.isEmpty()) {
            messageRepository.deleteAll(tail);
        }
    }

    /** 解析 metaJson 中的备选回复队列（无/损坏返回空列表）。 */
    static List<String> parseSwipes(String metaJson) {
        List<String> result = new ArrayList<>();
        if (metaJson == null || metaJson.isBlank()) {
            return result;
        }
        try {
            JsonNode root = MAPPER.readTree(metaJson);
            JsonNode swipes = root.get("swipes");
            if (swipes != null && swipes.isArray()) {
                for (JsonNode n : swipes) {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        result.add(n.asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[对谈] 解析 metaJson.swipes 失败，按空处理: {}", e.getMessage());
        }
        return result;
    }

    /** 序列化备选回复队列（空队列返回 null——保持既有 meta 语义）。 */
    static String serializeSwipes(List<String> swipes) {
        if (swipes == null || swipes.isEmpty()) {
            return null;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = root.putArray("swipes");
            swipes.forEach(arr::add);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[对谈] 序列化 metaJson.swipes 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 项目隔离取会话（不存在抛 404）。 */
    public ActorDialogueSession requireSession(Long projectId, Long sessionId) {
        return sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new BizException(404, "对谈会话不存在"));
    }

    /** 会话内下一序号（消息数 + 1，单调递增）。 */
    private int nextSeq(Long sessionId) {
        return (int) messageRepository.countBySessionId(sessionId) + 1;
    }

    /** 刷新会话更新时间（会话列表排序 + 最新活动提示）。 */
    private void touch(ActorDialogueSession s) {
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /** 消息实体 → 视图（角色名附带）。 */
    private DialogueMessageVO toVO(ActorDialogueMessage m) {
        String name = resolveName(m.getCharacterRef());
        return new DialogueMessageVO(m.getId(), m.getSessionId(), m.getRole(), m.getCharacterRef(), name,
                m.getContent(), m.getMetaJson(), m.getSeq(), m.getCreatedAt());
    }

    /** 角色引用 → 角色名（解析失败返回 null）。 */
    private String resolveName(String characterRef) {
        if (characterRef == null || characterRef.isBlank() || characterRepository == null) {
            return null;
        }
        Long id = com.holzyn.actor.common.NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        return characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
    }
}
