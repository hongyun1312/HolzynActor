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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.conversation.service.ConversationContextService;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 对谈上下文装配器（纯文本组装，零 AI、零 @Transactional）。
 * <p>职责：按《对谈设计方案 V1.0》§8.1 装配单聊上下文——<b>L0 稳定前缀（system）</b>：
 * 角色锚定+行为护栏（复用 {@link AiContextInjector#dialogueGuardRail}）+ 世界观
 * （{@link AiContextInjector#worldBrief}）+ 我的身份与处境（{@link AiContextInjector#characterCard}）
 * + 与{玩家}的关系（{@link DialogueRelationService#relationSegmentOf}）+ 关于{玩家}
 * （{@link DialogueRelationService#playerPersonaSegmentOf}，M3 玩家 persona 段）；<b>L1 动态段（user）</b>：
 * 冻结时刻（会话冻结快照）+ 相关记忆（复用 {@link ConversationContextService#buildContextByRef}
 * = 记忆注入 + RAG 知识带〔来源〕）+ <b>世界书片段（M4：会话级关键词触发，
 * {@link DialogueWorldbookService#worldbookContext}）</b> + 对话历史（<b>M4：超窗摘要
 * 以【对话摘要】前置</b>）+ 本轮任务。</p>
 * <p><b>作者注释（M4，可选）</b>：会话级常驻提示，按位置（depth：0=system 最前 /
 * 1=system 末尾[默认] / 2=user 段首）+ 频率（每 N 条消息）注入。</p>
 * <p><b>稳定前缀在前</b>：L0 恒定（命中 prompt caching），L1 动态在后。</p>
 * <p><b>fail-open</b>：依赖服务未装配/数据缺失→该段整段跳过（对齐 AiContextInjector 哲学），
 * 全空返回空串，不阻断对话。</p>
 */
@Slf4j
@Service
public class DialogueContextAssembler {

    /** Jackson 3 序列化器（冻结快照解析） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 历史窗口条数（M1 滚动窗口；M4 升级为超窗自动摘要，与 {@link DialogueSummaryService} 同源） */
    private static final int HISTORY_WINDOW = DialogueSummaryService.HISTORY_WINDOW;

    /**
     * 单条历史消息注入上限（字符，2026-10-13 第 61 轮冒烟抓修·上下文压缩）：
     * 此前历史段整条全文注入（角色回复实测 555~867 字/条 → 24 条窗口轻松 12K+ 字符），
     * 与【对话摘要】前置折叠配合，把单条压到 ~160 字以内，抑制上下文膨胀与生成超时。
     */
    private static final int HISTORY_LINE_MAX_CHARS = 160;

    /** 世界书关键词扫描窗口（最近 N 条消息正文，含本轮发言） */
    private static final int WORLDBOOK_SCAN_WINDOW = 6;

    /** 统一 AI 上下文注入器（世界观/角色卡/护栏/世界动态行；null=降级） */
    @Lazy
    @Autowired(required = false)
    private AiContextInjector injector;

    /** 玩家-角色关系服务（关系段；null=降级） */
    @Lazy
    @Autowired(required = false)
    private DialogueRelationService relationService;

    /** 对话上下文注入服务（记忆 + RAG 知识复用；null=降级） */
    @Autowired(required = false)
    private ConversationContextService conversationContextService;

    /** 会话级世界书服务（M4 关键词触发；null=降级） */
    @Autowired(required = false)
    private DialogueWorldbookService worldbookService;

    /** 角色主表仓库（角色名解析） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /**
     * 装配单聊上下文。
     *
     * @param projectId  项目 ID
     * @param userId     用户 ID（记忆注入凭据归属）
     * @param session    对谈会话（冻结快照/角色引用）
     * @param userMessage 本轮玩家发言
     * @param history    历史消息（<b>不含本轮发言</b>；seq 正序）
     *
     * @return L0 system + L1 user（双段文本）
     */
    public ComposedContext composeSingle(Long projectId, Long userId, ActorDialogueSession session,
                                         String userMessage, List<ActorDialogueMessage> history) {
        String characterRef = session.getCharacterRef();
        String characterName = characterNameOf(characterRef);
        String playerName = relationService == null ? "你" : relationService.playerNameOf(userId);
        String system = stableSystem(projectId, userId, characterRef, characterName, playerName);
        String user = dynamicUser(projectId, userId, session, characterRef, characterName, playerName,
                userMessage, history);
        // 作者注释（M4）：按位置注入——depth 0=system 最前 / 1=system 末尾（默认）/ 2=user 段首
        return applyAuthorNote(system, user, session, history == null ? 0 : history.size());
    }

    /**
     * 装配群聊单回应者上下文（M5，设计 §8.2）。
     * <p>每回应者独立调用：本人 L0 稳定前缀（角色卡/关系/玩家 persona）+ L1 动态段——
     * 【场景】（地点/在场名单/冻结时刻）+ 相关记忆 + 世界书片段 + 【对家】最近他人发言摘要
     * + 本群聊历史（逐消息标注说话人）+ 本轮任务（对玩家/在场对话）。</p>
     *
     * @param projectId   项目 ID
     * @param userId      用户 ID（记忆注入凭据归属）
     * @param session     群聊会话（场景地点/冻结快照）
     * @param memberRef   回应者角色引用（npc:{id}）
     * @param memberName  回应者角色名（可空=「你」）
     * @param playerName  玩家名
     * @param memberNames 在场成员名单（场景段展示；可空）
     * @param userMessage 本轮玩家发言
     * @param history     历史消息（<b>不含本轮发言</b>；seq 正序）
     *
     * @return L0 system + L1 user（双段文本）
     */
    public ComposedContext composeGroup(Long projectId, Long userId, ActorDialogueSession session,
                                        String memberRef, String memberName, String playerName,
                                        List<String> memberNames, String userMessage,
                                        List<ActorDialogueMessage> history) {
        String system = stableSystem(projectId, userId, memberRef, memberName, playerName);
        String user = groupDynamicUser(projectId, userId, session, memberRef, memberName, playerName,
                memberNames, userMessage, history);
        return applyAuthorNote(system, user, session, history == null ? 0 : history.size());
    }

    /**
     * 作者注释合并（M4 复用）：按位置注入——depth 0=system 最前 / 1=system 末尾（默认）/ 2=user 段首。
     */
    private ComposedContext applyAuthorNote(String system, String user, ActorDialogueSession session,
                                            int historySize) {
        String note = authorNoteSegment(session, historySize);
        if (note.isBlank()) {
            return new ComposedContext(system, user);
        }
        int depth = session.getAuthorNoteDepth() == null ? 1 : session.getAuthorNoteDepth();
        if (depth == 2) {
            user = user.isBlank() ? note : note + "\n\n" + user;
        } else if (depth <= 0) {
            system = system.isBlank() ? note : note + "\n\n" + system;
        } else {
            system = system.isBlank() ? note : system + "\n\n" + note;
        }
        return new ComposedContext(system, user);
    }

    /**
     * L0 稳定前缀（system）：角色锚定 + 护栏 + 世界观 + 身份处境 + 与玩家关系 + 关于玩家。
     * <p>重载便捷入口：未传 userId 时按本地单用户 1 装配（开场白等场景复用）。</p>
     *
     * @param projectId    项目 ID
     * @param characterRef 对谈角色（可空）
     * @param characterName 角色名（可空=「你」）
     * @param playerName   玩家名
     *
     * @return system 文本（全空返回空串）
     */
    public String stableSystem(Long projectId, String characterRef, String characterName, String playerName) {
        return stableSystem(projectId, 1L, characterRef, characterName, playerName);
    }

    /**
     * L0 稳定前缀（system）：角色锚定 + 护栏 + 世界观 + 身份处境 + 与玩家关系 + 关于玩家。
     *
     * @param projectId    项目 ID
     * @param userId       用户 ID（玩家 persona 段归属）
     * @param characterRef 对谈角色（可空）
     * @param characterName 角色名（可空=「你」）
     * @param playerName   玩家名
     *
     * @return system 文本（全空返回空串）
     */
    public String stableSystem(Long projectId, Long userId, String characterRef, String characterName,
                               String playerName) {
        List<String> segs = new ArrayList<>();
        String anchor = roleAnchor(characterName);
        if (!anchor.isBlank()) {
            segs.add(anchor);
        }
        if (injector != null) {
            String world = blank(injector.worldBrief(projectId));
            if (!world.isBlank()) {
                segs.add("【世界观】\n" + world);
            }
            if (characterRef != null && !characterRef.isBlank()) {
                String card = blank(injector.characterCard(projectId, characterRef));
                if (!card.isBlank()) {
                    segs.add("【我的身份与处境】\n" + card);
                }
            }
        }
        if (relationService != null) {
            if (characterRef != null && !characterRef.isBlank()) {
                String relation = blank(relationService.relationSegmentOf(projectId, characterRef, playerName));
                if (!relation.isBlank()) {
                    segs.add(relation);
                }
            }
            // 关于{玩家}（M3 玩家 persona 段：身份/背景/性格/说话风格/目标/能力）
            String about = blank(relationService.playerPersonaSegmentOf(userId, playerName));
            if (!about.isBlank()) {
                segs.add(about);
            }
        }
        return segs.isEmpty() ? "" : String.join("\n\n", segs).trim();
    }

    /**
     * L1 动态段（user）：冻结时刻 + 相关记忆 + 世界书片段 + 对话历史 + 本轮任务。
     */
    public String dynamicUser(Long projectId, Long userId, ActorDialogueSession session, String characterRef,
                              String characterName, String playerName, String userMessage,
                              List<ActorDialogueMessage> history) {
        List<String> segs = new ArrayList<>();
        String frozen = frozenContext(session);
        if (!frozen.isBlank()) {
            segs.add(frozen);
        }
        String memory = memoryContext(projectId, userId, characterRef, userMessage, history);
        if (!memory.isBlank()) {
            segs.add(memory);
        }
        String worldbook = worldbookContext(session, history, userMessage);
        if (!worldbook.isBlank()) {
            segs.add(worldbook);
        }
        String hist = historyText(session, history, playerName, characterName);
        if (!hist.isBlank()) {
            segs.add("【对话历史】\n" + hist);
        }
        String who = characterName == null || characterName.isBlank() ? "你" : characterName;
        String you = playerName == null || playerName.isBlank() ? "你" : playerName;
        segs.add("【本轮】" + you + "对你说：" + (userMessage == null ? "" : userMessage.trim()) + "\n"
                + "请直接以" + who + "的口吻回复正文（第一人称，像真人一样自然回应），不要输出任何 JSON 外壳、"
                + "不要标注角色名、不要替" + you + "说话。");
        return String.join("\n\n", segs).trim();
    }

    /**
     * AI 开场白输入（L1 动态段 · 无记忆）：冻结时刻 + 场景 + 任务（对齐设计 §6.7：
     * 输入=角色卡[稳定 system] + 冻结状态 + 对玩家态度[关系段在 system] + 场景）。
     */
    public String greetingPrompt(Long projectId, ActorDialogueSession session, String characterName,
                                 String playerName) {
        List<String> segs = new ArrayList<>();
        String frozen = frozenContext(session);
        if (!frozen.isBlank()) {
            segs.add(frozen);
        }
        String who = characterName == null || characterName.isBlank() ? "你" : characterName;
        String you = playerName == null || playerName.isBlank() ? "你" : playerName;
        segs.add("【场景】" + you + "来到了你身边，想与你聊聊天。"
                + "\n请以" + who + "的口吻说一句开场白（第一人称，不超过 80 字，自然亲切，像真人见面打招呼），"
                + "不要输出任何 JSON 外壳、不要标注角色名。");
        return String.join("\n\n", segs).trim();
    }

    /**
     * 群聊 L1 动态段（M5，设计 §8.2）：场景段（地点/在场/冻结时刻）+ 相关记忆 + 世界书片段
     * + 【对家】最近他人发言 + 本群聊历史（逐消息标注说话人）+ 本轮任务。
     */
    private String groupDynamicUser(Long projectId, Long userId, ActorDialogueSession session,
                                    String memberRef, String memberName, String playerName,
                                    List<String> memberNames, String userMessage,
                                    List<ActorDialogueMessage> history) {
        List<String> segs = new ArrayList<>();
        String scene = sceneContext(session, memberNames);
        if (!scene.isBlank()) {
            segs.add(scene);
        }
        String memory = memoryContext(projectId, userId, memberRef, userMessage, history);
        if (!memory.isBlank()) {
            segs.add(memory);
        }
        String worldbook = worldbookContext(session, history, userMessage);
        if (!worldbook.isBlank()) {
            segs.add(worldbook);
        }
        String counter = counterPartSegment(history, memberRef);
        if (!counter.isBlank()) {
            segs.add(counter);
        }
        String hist = groupHistoryText(session, history, playerName);
        if (!hist.isBlank()) {
            segs.add("【对话历史】\n" + hist);
        }
        String who = memberName == null || memberName.isBlank() ? "你" : memberName;
        String you = playerName == null || playerName.isBlank() ? "你" : playerName;
        segs.add("【本轮】" + you + "对大家说：" + (userMessage == null ? "" : userMessage.trim()) + "\n"
                + "你正身处这场对谈。请以" + who + "的口吻回应（第一人称，像真人一样自然、简短地接话）："
                + "可以回应" + you + "，也可以接刚才其他角色的话（见【对家】）。"
                + "不要替其他角色或" + you + "说话，不要输出任何 JSON 外壳、不要标注角色名。");
        return String.join("\n\n", segs).trim();
    }

    /** 场景段（群聊）：地点 + 在场名单 + 冻结时刻（世界动态行）。 */
    private String sceneContext(ActorDialogueSession session, List<String> memberNames) {
        if (session == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String location = session.getLocationName();
        if (location != null && !location.isBlank()) {
            parts.add("你们在" + location);
        }
        if (memberNames != null && !memberNames.isEmpty()) {
            parts.add("在场的有：" + String.join("、", memberNames));
        }
        String dynamics = snapshotField(session.getFrozenSnapshotJson(), "world_dynamics");
        if (!dynamics.isBlank()) {
            parts.add(dynamics);
        }
        return parts.isEmpty() ? "" : "【场景】" + String.join("，", parts);
    }

    /** 【对家】段（群聊）：最近非本人发言者的简短摘要（截断 3 条，供回应他人搭话）。 */
    private String counterPartSegment(List<ActorDialogueMessage> history, String memberRef) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && lines.size() < 3; i--) {
            ActorDialogueMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if (!("group".equals(m.getRole()) || "assistant".equals(m.getRole()))) {
                continue;
            }
            if (memberRef != null && memberRef.equals(m.getCharacterRef())) {
                continue; // 本人发言不算「对家」
            }
            String name = m.getCharacterRef() == null ? "角色" : characterNameOf(m.getCharacterRef());
            String text = m.getContent().trim();
            if (text.length() > 60) {
                text = text.substring(0, 60) + "…";
            }
            lines.add("「" + (name == null ? "角色" : name) + "」：" + text);
        }
        if (lines.isEmpty()) {
            return "";
        }
        java.util.Collections.reverse(lines);
        return "【对家】最近你听到：\n" + String.join("\n", lines);
    }

    /** 群聊历史段（逐消息标注说话人；M5）。 */
    private String groupHistoryText(ActorDialogueSession session, List<ActorDialogueMessage> history,
                                    String playerName) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String summary = session == null || session.getSummary() == null ? "" : session.getSummary().trim();
        if (!summary.isBlank()) {
            sb.append("【对话摘要】").append(summary);
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        for (int i = from; i < history.size(); i++) {
            ActorDialogueMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            String speaker;
            if ("user".equals(m.getRole())) {
                speaker = playerName == null || playerName.isBlank() ? "玩家" : playerName;
            } else if ("group".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                String n = m.getCharacterRef() == null ? null : characterNameOf(m.getCharacterRef());
                speaker = n == null || n.isBlank() ? "角色" : n;
            } else {
                speaker = "系统";
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(speaker).append("：").append(clipHistory(m.getContent().trim()));
        }
        return sb.toString();
    }

    /**
     * 群聊开场白输入（M5，设计 §6.7 群聊开场：场景段 + 首个角色开口）。
     *
     * @param session     群聊会话（场景地点/冻结快照）
     * @param memberName  回应者角色名
     * @param playerName  玩家名
     * @param memberNames 在场成员名单（场景段展示；可空）
     *
     * @return L1 开场输入文本
     */
    public String groupGreetingPrompt(ActorDialogueSession session, String memberName, String playerName,
                                      List<String> memberNames) {
        List<String> segs = new ArrayList<>();
        String scene = sceneContext(session, memberNames);
        if (!scene.isBlank()) {
            segs.add(scene);
        }
        String who = memberName == null || memberName.isBlank() ? "你" : memberName;
        String you = playerName == null || playerName.isBlank() ? "你" : playerName;
        segs.add("【场景】" + you + "来到了大家面前，想与在场的人打个照面聊聊天。"
                + "\n请以" + who + "的口吻说一句开场白（第一人称，不超过 80 字，自然亲切，像真人见面打招呼），"
                + "不要输出任何 JSON 外壳、不要标注角色名。");
        return String.join("\n\n", segs).trim();
    }

    // ==================== 段构建 ====================

    /** 角色锚定 + 行为护栏（复用注入器护栏，稳定前缀）。 */
    private String roleAnchor(String characterName) {
        String who = characterName == null || characterName.isBlank() ? "你" : characterName;
        String guard = injector == null ? "" : AiContextInjector.dialogueGuardRail(characterName);
        String head = "你是" + who + "，绝不能跳出角色。";
        return guard == null || guard.isBlank() ? head : head + "\n" + guard;
    }

    /** 冻结时刻段（游戏时间/季节/天气 + 位置/情绪；快照缺失回退实时读）。 */
    private String frozenContext(ActorDialogueSession session) {
        if (session == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String dynamics = snapshotField(session.getFrozenSnapshotJson(), "world_dynamics");
        if (!dynamics.isBlank()) {
            parts.add(dynamics);
        }
        String location = snapshotField(session.getFrozenSnapshotJson(), "character_location");
        if (!location.isBlank()) {
            parts.add("你在" + location);
        }
        String mood = snapshotField(session.getFrozenSnapshotJson(), "character_mood");
        if (!mood.isBlank() && !"calm".equalsIgnoreCase(mood)) {
            parts.add("心情" + mood);
        }
        if (parts.isEmpty() && injector != null && session.getProjectId() != null) {
            // 快照缺失 → 回退实时读（世界已冻结，实时=冻结）
            try {
                String line = injector.worldDynamicsLine(session.getProjectId());
                if (line != null && !line.isBlank()) {
                    parts.add(line);
                }
            } catch (Exception e) {
                log.debug("[对谈装配] 冻结时刻回退实时读失败（跳过）: {}", e.getMessage());
            }
        }
        return parts.isEmpty() ? "" : "【冻结时刻】" + String.join("，", parts);
    }

    /** 相关记忆段（复用对话上下文注入：记忆注入 + RAG 知识带〔来源〕）。 */
    private String memoryContext(Long projectId, Long userId, String characterRef, String userMessage,
                                 List<ActorDialogueMessage> history) {
        if (conversationContextService == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            List<String> excludeTexts = historyTextLines(history);
            return blank(conversationContextService.buildContextByRef(userId, projectId, characterRef,
                    userMessage == null ? "" : userMessage, excludeTexts));
        } catch (Exception e) {
            log.debug("[对谈装配] 相关记忆检索失败（跳过，零阻断）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 世界书片段段（M4：会话级关键词触发世界书）。
     * <p>扫描最近 {@link #WORLDBOOK_SCAN_WINDOW} 条历史 + 本轮发言的正文，
     * 命中条目以【世界书片段】注入（标注触发关键词〔来源〕）。</p>
     */
    private String worldbookContext(ActorDialogueSession session, List<ActorDialogueMessage> history,
                                    String userMessage) {
        if (worldbookService == null || session == null || session.getId() == null) {
            return "";
        }
        try {
            List<String> recent = new ArrayList<>();
            if (history != null) {
                int from = Math.max(0, history.size() - WORLDBOOK_SCAN_WINDOW);
                for (int i = from; i < history.size(); i++) {
                    ActorDialogueMessage m = history.get(i);
                    if (m != null && m.getContent() != null && !m.getContent().isBlank()) {
                        recent.add(m.getContent().trim());
                    }
                }
            }
            if (userMessage != null && !userMessage.isBlank()) {
                recent.add(userMessage.trim());
            }
            return worldbookService.worldbookContext(session.getId(), recent, null);
        } catch (Exception e) {
            log.debug("[对谈装配] 世界书检索失败（跳过，零阻断）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 作者注释段文本（M4，可选）：会话级常驻提示，按「位置（深度）+ 频率（每 N 条）」注入。
     * <p>频率门控：{@code authorNoteFrequency} 每 N 条消息注入一次（null/&lt;=0 = 恒注入）。</p>
     *
     * @param session     会话（含作者注释配置）
     * @param historySize 当前消息条数（频率门控依据）
     *
     * @return 作者注释段（未启用/频率未命中返回空串）
     */
    public String authorNoteSegment(ActorDialogueSession session, int historySize) {
        if (session == null || session.getAuthorNote() == null || session.getAuthorNote().isBlank()) {
            return "";
        }
        Integer freq = session.getAuthorNoteFrequency();
        if (freq != null && freq > 0 && historySize % freq != 0) {
            return "";
        }
        return "【作者注释】" + session.getAuthorNote().trim();
    }

    /**
     * 便捷入口（开场白等非 compose 场景复用）：按位置把作者注释并入 system。
     * depth 2（user 段首）不入 system——由调用方自行并入 user；其余按 0=最前 / 1=末尾。
     *
     * @param system     既有 system 文本
     * @param session    会话（含作者注释配置）
     * @param historySize 当前消息条数
     *
     * @return 并入后的 system 文本
     */
    public String withSystemAuthorNote(String system, ActorDialogueSession session, int historySize) {
        String note = authorNoteSegment(session, historySize);
        if (note.isBlank()) {
            return system;
        }
        int depth = session.getAuthorNoteDepth() == null ? 1 : session.getAuthorNoteDepth();
        if (depth == 2) {
            return system;
        }
        if (depth <= 0) {
            return system == null || system.isBlank() ? note : note + "\n\n" + system;
        }
        return system == null || system.isBlank() ? note : system + "\n\n" + note;
    }

    /** 历史消息行截断（第 61 轮冒烟抓修·上下文压缩）：超长单条裁剪到 {@link #HISTORY_LINE_MAX_CHARS} 并标注省略。 */
    private static String clipHistory(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= HISTORY_LINE_MAX_CHARS) {
            return text;
        }
        return text.substring(0, HISTORY_LINE_MAX_CHARS) + "…（前文过长已略）";
    }

    /** 对话历史段（对谈专用前缀；M4：超窗摘要以【对话摘要】前置；末尾本轮发言已由调用方剔除）。 */
    private String historyText(ActorDialogueSession session, List<ActorDialogueMessage> history,
                               String playerName, String characterName) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String summary = session == null || session.getSummary() == null ? "" : session.getSummary().trim();
        if (!summary.isBlank()) {
            sb.append("【对话摘要】").append(summary);
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        for (int i = from; i < history.size(); i++) {
            ActorDialogueMessage m = history.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            String speaker;
            if ("user".equals(m.getRole())) {
                speaker = playerName == null || playerName.isBlank() ? "玩家" : playerName;
            } else if ("assistant".equals(m.getRole()) || "group".equals(m.getRole())) {
                speaker = characterName == null || characterName.isBlank() ? "角色" : characterName;
            } else {
                speaker = "系统";
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(speaker).append("：").append(clipHistory(m.getContent().trim()));
        }
        return sb.toString();
    }

    /** 历史文本行（记忆注入去重用：剔除与历史窗口重叠的记忆）。 */
    private List<String> historyTextLines(List<ActorDialogueMessage> history) {
        List<String> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        for (int i = from; i < history.size(); i++) {
            ActorDialogueMessage m = history.get(i);
            if (m != null && m.getContent() != null && !m.getContent().isBlank()) {
                out.add(m.getContent().trim());
            }
        }
        return out;
    }

    /** 冻结快照 JSON 字段（解析失败/缺失返回空串）。 */
    private static String snapshotField(String frozenSnapshotJson, String field) {
        if (frozenSnapshotJson == null || frozenSnapshotJson.isBlank()) {
            return "";
        }
        try {
            JsonNode n = MAPPER.readTree(frozenSnapshotJson);
            JsonNode v = n.path(field);
            return v.isMissingNode() || v.isNull() ? "" : v.asText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 角色名（解析失败返回 null）。 */
    private String characterNameOf(String characterRef) {
        if (characterRepository == null || characterRef == null) {
            return null;
        }
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        try {
            return characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** null → 空串（fail-open：外部服务异常返回 null 时不 NPE）。 */
    private static String blank(String s) {
        return s == null ? "" : s;
    }

    /**
     * 装配结果（L0 system + L1 user 双段）。
     *
     * @param system L0 稳定前缀
     * @param user   L1 动态段（含本轮任务）
     */
    public record ComposedContext(String system, String user) {
    }
}
