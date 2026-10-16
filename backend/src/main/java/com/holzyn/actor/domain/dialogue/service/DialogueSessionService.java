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
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueMessageRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorDialogueSessionRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对谈会话服务（纯本地 DB，零 AI）。
 * <p>职责：对谈会话的创建（含进入即冻结）/列表/详情/重命名/关闭。
 * 创建时校验对谈角色归属本项目，并调用 {@link DialogueFreezeService#freeze}
 * 完成「运行中自动暂停 + 冻结快照」落库。</p>
 * <p><b>事务纪律</b>：本服务<b>不调用任何 AI</b>（冻结=零 AI 只读+短事务暂停），
 * 方法可安全携带 {@code @Transactional}（短事务，无跨 AI 持锁风险）。</p>
 */
@Slf4j
@Service
public class DialogueSessionService {

    /** Jackson 3 序列化器（冻结快照 JSON） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话仓库 */
    @Autowired(required = false)
    private ActorDialogueSessionRepository sessionRepository;

    /** 消息仓库（消息数统计） */
    @Autowired(required = false)
    private ActorDialogueMessageRepository messageRepository;

    /** 角色主表仓库（角色归属校验 + 角色名附带） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** 冻结服务（进入对谈自动暂停 + 快照） */
    @Lazy
    @Autowired(required = false)
    private DialogueFreezeService freezeService;

    /** 默认标题截断长度（字符） */
    private static final int TITLE_MAX = 40;

    /**
     * 新建对谈会话（单聊/群聊）。
     * <p>创建即冻结：{@link DialogueFreezeService#freeze} 自动暂停运行中的世界并快照
     * 冻结时刻/角色状态/在场，快照序列化存入会话行。</p>
     *
     * @param projectId    项目 ID
     * @param userId       用户 ID
     * @param type         会话类型（single/group；M1 仅 single）
     * @param characterRef 单聊对谈角色（npc:{id}；type=single 必填）
     * @param title        会话标题（可空=延迟到首条玩家消息生成）
     *
     * @return 新会话视图
     */
    @Transactional
    public DialogueSessionVO create(Long projectId, Long userId, String type, String characterRef, String title) {
        String t = type == null || type.isBlank() ? "single" : type.trim();
        if (!"single".equals(t) && !"group".equals(t)) {
            throw new BizException(400, "对谈类型只支持 single / group");
        }
        if ("single".equals(t) && (characterRef == null || characterRef.isBlank())) {
            throw new BizException(400, "单聊对谈必须指定角色（characterRef）");
        }
        ActorCharacter ch = requireCharacter(projectId, characterRef);
        // 进入即冻结（运行中自动暂停 + 冻结时刻/角色状态/在场快照；fail-open）
        DialogueFreezeService.FreezeSnapshot snap = freezeService == null
                ? null : freezeService.freeze(projectId, userId, characterRef);
        ActorDialogueSession s = new ActorDialogueSession();
        s.setProjectId(projectId);
        s.setUserId(userId);
        s.setType(t);
        s.setCharacterRef(characterRef);
        s.setTitle(title == null || title.isBlank() ? null : trim(title));
        s.setFrozenGameSecond(snap == null ? null : snap.gameSecond());
        s.setFrozenSnapshotJson(snap == null ? null : snapshotJson(snap));
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        ActorDialogueSession saved = sessionRepository.save(s);
        log.info("[对谈] 创建会话 project={} session={} type={} 角色={} 冻结时刻={}",
                projectId, saved.getId(), t, characterRef, snap == null ? null : snap.gameSecond());
        return toVO(saved, 0L);
    }

    /**
     * 项目对谈会话列表（按更新时间倒序，含消息数与角色名）。
     *
     * @param projectId 项目 ID
     *
     * @return 会话视图列表
     */
    public List<DialogueSessionVO> list(Long projectId) {
        return sessionRepository.findByProjectIdOrderByUpdatedAtDescIdDesc(projectId).stream()
                .map(s -> toVO(s, messageRepository == null ? 0L : messageRepository.countBySessionId(s.getId())))
                .toList();
    }

    /**
     * 会话搜索（M6 打磨）：按关键字匹配标题/角色名/群聊场景地点名（大小写不敏感）。
     * 关键字为空/空白 → 等价 {@link #list}（全量）。纯本地 DB 只读，零 AI。
     *
     * @param projectId 项目 ID
     * @param keyword   搜索关键字（可空=全量）
     *
     * @return 过滤后的会话视图列表（按更新时间倒序）
     */
    public List<DialogueSessionVO> search(Long projectId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return list(projectId);
        }
        String kw = keyword.trim().toLowerCase();
        return sessionRepository.findByProjectIdOrderByUpdatedAtDescIdDesc(projectId).stream()
                .filter(s -> matches(s, kw))
                .map(s -> toVO(s, messageRepository == null ? 0L : messageRepository.countBySessionId(s.getId())))
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
    public DialogueSessionVO get(Long projectId, Long sessionId) {
        ActorDialogueSession s = require(projectId, sessionId);
        return toVO(s, messageRepository == null ? 0L : messageRepository.countBySessionId(sessionId));
    }

    /**
     * 重命名会话。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param title     新标题（非空）
     */
    @Transactional
    public void rename(Long projectId, Long sessionId, String title) {
        ActorDialogueSession s = require(projectId, sessionId);
        if (title == null || title.isBlank()) {
            throw new BizException(400, "标题不能为空");
        }
        s.setTitle(trim(title));
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 关闭会话（status=closed；历史保留可查阅）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     */
    @Transactional
    public void close(Long projectId, Long sessionId) {
        ActorDialogueSession s = require(projectId, sessionId);
        s.setStatus("closed");
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 更新作者注释配置（M4，可选）：常驻提示 + 位置（深度）+ 频率（每 N 条注入）。
     * 零 AI，短事务。作者注释为空串=清除（不再注入）。
     *
     * @param projectId        项目 ID
     * @param sessionId        会话 ID
     * @param authorNote       作者注释内容（空串清除）
     * @param authorNoteDepth  位置：0=system 最前 / 1=system 末尾（默认）/ 2=L1 动态段首
     * @param authorNoteFrequency 频率：每 N 条消息注入一次（null/<=0 = 恒注入）
     *
     * @return 更新后的会话视图
     */
    @Transactional
    public DialogueSessionVO updateAuthorNote(Long projectId, Long sessionId, String authorNote,
                                              Integer authorNoteDepth, Integer authorNoteFrequency) {
        ActorDialogueSession s = require(projectId, sessionId);
        s.setAuthorNote(authorNote == null ? "" : authorNote.trim());
        Integer depth = authorNoteDepth;
        if (depth == null || depth < 0) {
            depth = 1;
        }
        if (depth > 2) {
            depth = 2;
        }
        s.setAuthorNoteDepth(depth);
        s.setAuthorNoteFrequency(authorNoteFrequency == null || authorNoteFrequency <= 0 ? 1 : authorNoteFrequency);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
        return toVO(s, messageRepository == null ? 0L : messageRepository.countBySessionId(sessionId));
    }

    /**
     * 更新滚动窗口摘要（M4 超窗自动摘要落点；由 {@code DialogueSummaryService} 在 AI 摘要
     * 生成成功后经本方法短事务写回——AI 调用方无 @Transactional，写回与 AI 分离）。
     *
     * @param projectId           项目 ID
     * @param sessionId           会话 ID
     * @param summary             摘要文本（空串=清除）
     * @param summaryMessageCount 已折叠进摘要的消息条数（增量摘要水位；null 不改动）
     */
    @Transactional
    public void updateSummary(Long projectId, Long sessionId, String summary, Integer summaryMessageCount) {
        ActorDialogueSession s = require(projectId, sessionId);
        s.setSummary(summary == null ? "" : summary.trim());
        if (summaryMessageCount != null && summaryMessageCount >= 0) {
            s.setSummaryMessageCount(summaryMessageCount);
        }
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 更新群聊场景地点（M5 换场；会话行写入口，由 {@code DialogueGroupService} 编排调用）。
     * 零 AI，短事务。locationId/locationName 均可空（空=无场景地点，成员可由拉人补充）。
     *
     * @param projectId    项目 ID
     * @param sessionId    会话 ID
     * @param locationId   场景地点 ID（可空）
     * @param locationName 场景地点名（可空）
     */
    @Transactional
    public void updateLocation(Long projectId, Long sessionId, Long locationId, String locationName) {
        ActorDialogueSession s = require(projectId, sessionId);
        s.setLocationId(locationId);
        s.setLocationName(locationName == null || locationName.isBlank() ? null : locationName.trim());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /**
     * 记录沉淀版本（M6：会话 {@code player_relation_version} 写入口，短事务）。
     * 由 {@code DialogueConsolidationService.apply} 在关系/记忆写完后调用（其自身零 AI）。
     *
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param version   本轮沉淀后的关系版本（= 本轮最高角色关系 version；null 归 0）
     */
    @Transactional
    public void markConsolidated(Long projectId, Long sessionId, Integer version) {
        ActorDialogueSession s = require(projectId, sessionId);
        s.setPlayerRelationVersion(version == null ? 0 : version);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);
    }

    /** 项目隔离取会话（不存在抛 404）。 */
    public ActorDialogueSession require(Long projectId, Long sessionId) {
        return sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new BizException(404, "对谈会话不存在"));
    }

    // ==================== 内部 ====================

    /** 会话是否命中搜索关键字（标题/角色名/群聊场景地点名，大小写不敏感）。 */
    private boolean matches(ActorDialogueSession s, String kw) {
        if (s.getTitle() != null && s.getTitle().toLowerCase().contains(kw)) {
            return true;
        }
        if (s.getLocationName() != null && s.getLocationName().toLowerCase().contains(kw)) {
            return true;
        }
        if (s.getCharacterRef() != null) {
            Long id = NpcRefs.idOf(s.getCharacterRef());
            if (id != null && characterRepository != null) {
                try {
                    String name = characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
                    if (name != null && name.toLowerCase().contains(kw)) {
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("[对谈] 搜索角色名解析失败（忽略该会话）: {}", e.getMessage());
                }
            }
        }
        return false;
    }

    /** 单聊角色归属校验（存在且属于项目，否则 404）。 */
    private ActorCharacter requireCharacter(Long projectId, String characterRef) {
        if (characterRef == null) {
            return null;
        }
        Long id = NpcRefs.idOf(characterRef);
        if (id == null || characterRepository == null) {
            throw new BizException(404, "对谈角色不存在");
        }
        return characterRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new BizException(404, "对谈角色不存在或不属于该项目"));
    }

    /** 冻结快照 → JSON（序列化失败返回 null，不阻断会话创建）。 */
    private String snapshotJson(DialogueFreezeService.FreezeSnapshot snap) {
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("game_second", snap.gameSecond() == null ? null : snap.gameSecond().longValue());
            n.put("world_dynamics", snap.worldDynamics() == null ? "" : snap.worldDynamics());
            n.put("character_name", snap.characterName() == null ? "" : snap.characterName());
            n.put("character_location", snap.characterLocation() == null ? "" : snap.characterLocation());
            n.put("character_mood", snap.characterMood() == null ? "" : snap.characterMood());
            n.put("was_running", snap.wasRunning());
            ArrayNode present = n.putArray("present_refs");
            if (snap.presentRefs() != null) {
                for (String ref : snap.presentRefs()) {
                    present.add(ref);
                }
            }
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[对谈] 冻结快照序列化失败（降级 null）: {}", e.getMessage());
            return null;
        }
    }

    /** 冻结快照 JSON → 世界动态行（展示「世界已静止于 X」；解析失败回退空串）。 */
    private String worldDynamicsOf(String frozenSnapshotJson) {
        if (frozenSnapshotJson == null || frozenSnapshotJson.isBlank()) {
            return "";
        }
        try {
            JsonNode n = MAPPER.readTree(frozenSnapshotJson);
            JsonNode d = n.path("world_dynamics");
            return d.isMissingNode() || d.isNull() ? "" : d.asText();
        } catch (Exception e) {
            return "";
        }
    }

    /** 会话 → 视图（角色名 + 冻结时间文本附带）。 */
    private DialogueSessionVO toVO(ActorDialogueSession s, Long messageCount) {
        String characterName = null;
        if (s.getCharacterRef() != null && characterRepository != null) {
            Long id = NpcRefs.idOf(s.getCharacterRef());
            if (id != null) {
                characterName = characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
            }
        }
        return new DialogueSessionVO(s.getId(), s.getProjectId(), s.getTitle(), s.getType(), s.getCharacterRef(),
                characterName, s.getLocationName(), s.getFrozenGameSecond(),
                worldDynamicsOf(s.getFrozenSnapshotJson()), s.getStatus(), messageCount,
                s.getCreatedAt(), s.getUpdatedAt());
    }

    /** 截断到标题上限（防长标题撑爆列）。 */
    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }
}
