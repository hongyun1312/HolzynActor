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

package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.roleplay.RoleplayContextService;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversationMember;
import com.holzyn.actor.domain.npc.dialogue.entity.ActorMessage;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.conversation.vo.ConversationMemberVO;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.conversation.vo.MessageVO;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorConversationRepository;
import com.holzyn.actor.domain.npc.dialogue.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话与消息业务服务（A-）。
 * <p>职责：提供单聊/群聊会话的创建、列表、详情与历史消息读取；
 * 角色归属通过 会话→项目→用户 校验，越权访问返回 404。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final RoleplayContextService roleplayContextService;
    private final ActorMessageRepository messageRepository;
    private final CurrentUserProvider currentUserProvider;

    /** 位置归一（字段注入保持构造器不变；null=归一关闭，对话不落 location_id） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.LocationNormalizationService locationNormalizationService;

    /**
     * 位置归一落地点 ID（；归一服务缺失/未命中返回 null，自由文本兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param location 自由文本位置（可空/空=远程通讯，不归一）
     *
     * @return 地点表 ID 或 null
     */
    private Long normalizeLocationId(Long projectId, String location) {
        if (locationNormalizationService == null || projectId == null || location == null || location.isBlank()) {
            return null;
        }
        try {
            return locationNormalizationService.normalizedId(projectId, location);
        } catch (Exception e) {
            log.warn("[位置归一] 会话位置归一失败（保留自由文本）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * 创建会话（单聊/群聊，B5 成员类型化：支持普通型 NPC 以 npc:{id} 进会话）。
     *
     * @param projectId 项目 ID
     *
     * @param dto 会话入参（mode/characterIds/memberRefs/location/gameTimeText）
     *
     * @return 创建后的会话 VO
     */
    @Transactional
    public ConversationVO create(Long projectId, ConversationDTO dto) {
        requireProject(projectId);
        ActorConversation c = new ActorConversation();
        c.setProjectId(projectId);
        c.setUserId(currentUserProvider.currentUserId());
        c.setMode(dto.mode());
        c.setTitle(dto.title());
        // 对话场景：所在地（空=远程通讯）+ 世界时间快照，创建后持久化并注入 NPC 回答
        // ：对话所在地按地点表归一落 location_id（事件按地点路由基准）
        c.setLocation(normalizeBlank(dto.location()));
        c.setLocationId(normalizeLocationId(projectId, c.getLocation()));
        c.setGameTimeText(normalizeBlank(dto.gameTimeText()));
        c.setWorldEventEnabled(0);
        ActorConversation saved = conversationRepository.save(c);

        // 成员解析：memberRefs 优先（统一 npc:{id}），空则用 characterIds 兼容旧调用（=special）
        List<String> refs = resolveMemberRefs(dto);
        for (String ref : refs) {
            ActorConversationMember m = createMember(projectId, saved.getId(), ref);
            memberRepository.save(m);
        }
        return detailVO(saved);
    }

    /**
     * 解析成员引用列表：memberRefs 非空用之；否则由 characterIds 转 npc:{id}（兼容旧调用）。
     */
    private List<String> resolveMemberRefs(ConversationDTO dto) {
        if (dto.memberRefs() != null && !dto.memberRefs().isEmpty()) {
            return dto.memberRefs();
        }
        List<Long> ids = dto.characterIds() == null ? List.of() : dto.characterIds();
        List<String> refs = new ArrayList<>();
        for (Long cid : ids) {
            if (cid != null) {
                refs.add(com.holzyn.actor.common.NpcRefs.of(cid));
            }
        }
        return refs;
    }

    /**
     * 创建会话成员：
     * {@code npc:{id}} → 校验 actor_profile 行属于项目，member_type 取档案行 type
     * （special → 名称由角色表解析；common → member_name 名称快照）。
     *
     * @param projectId 项目 ID
     *
     * @param convId 会话 ID
     *
     * @param ref 成员引用（统一 {@code npc:{id}}）
     *
     * @return 待保存的成员实体
     */
    private ActorConversationMember createMember(Long projectId, Long convId, String ref) {
        String refText = ref == null ? "" : ref.trim();
        ActorConversationMember m = new ActorConversationMember();
        m.setConversationId(convId);
        // ：npc:{id} 不再编码类型，此处按意图 A/B 处理（单表校验 actor_profile 行，类型取行内 type）
        Long id = NpcRefs.idOf(refText);
        // 校验 NPC 确实属于该项目，防止越权加入他人角色
        ActorCharacter ch = id == null ? null
                : characterRepository.findById(id)
                .filter(x -> projectId.equals(x.getProjectId()))
                .filter(x -> Integer.valueOf(0).equals(x.getDeleted()))
                .orElse(null);
        if (ch == null) {
            throw new BizException(400, "NPC 不存在或不属于该项目");
        }
        boolean common = NpcRefs.isCommonType(ch.getType());
        m.setMemberType(common ? NpcRefs.TYPE_COMMON : NpcRefs.TYPE_SPECIAL);
        m.setCharacterId(ch.getId());
        m.setMemberName(common ? ch.getName() : null); // 特殊角色名称由角色表解析
        return m;
    }

    /**
     * 会话「对话场景」（所在地 / 世界时间快照）。
     * <p>作用：会话创建后可随时调整对话发生的地点与时间，保存后会影响后续 NPC 的回答
     * （ChatService 组装消息时按最新场景注入）。</p>
     *
     * @param id 会话主键
     *
     * @param location 对话所在地（null=不修改；空串=清空为远程通讯）
     *
     * @param gameTimeText 世界时间快照（null=不修改；空串=清空）
     *
     * @return 更新后的会话 VO
     */
    @Transactional
    public ConversationVO updateScene(Long id, String location, String gameTimeText) {
        ActorConversation c = requireOwned(id);
        if (location != null) {
            c.setLocation(normalizeBlank(location));
            // ：场景修改同样归一（空=远程通讯，location_id 清空）
            c.setLocationId(normalizeLocationId(c.getProjectId(), c.getLocation()));
        }
        if (gameTimeText != null) {
            c.setGameTimeText(normalizeBlank(gameTimeText));
        }
        conversationRepository.save(c);
        return detailVO(c);
    }

    /**
     * 项目会话列表（按更新时间倒序）。
     *
     * @param projectId 项目 ID
     *
     * @return 会话 VO 列表
     */
    @Transactional(readOnly = true)
    public List<ConversationVO> list(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        return conversationRepository.findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId)
                .stream().map(this::detailVO).toList();
    }

    /**
     * 会话详情（归属校验）。
     *
     * @param id 会话主键
     *
     * @return 会话 VO
     */
    @Transactional(readOnly = true)
    public ConversationVO detail(Long id) {
        return detailVO(requireOwned(id));
    }

    /**
     * 历史消息（按创建顺序升序）。
     * <p>2026-10 长跑修复（前端「只显示角色名没有内容」）：过滤掉<b>内容为空</b>的消息行
     * ——failed/streaming 占位（content=null/空白）此前原样返回，前端渲染成「只有角色名、
     * 没有正文」的空气泡（如 AI 调用中断残留、空回复占位）。系统/事件/正常发言均有内容，不受影响。</p>
     *
     * @param id 会话主键
     *
     * @return 消息 VO 列表（仅含可渲染内容的消息）
     */
    @Transactional(readOnly = true)
    public List<MessageVO> messages(Long id) {
        requireOwned(id);
        return messageRepository.findByConversationIdOrderByIdAsc(id).stream()
                .filter(m -> m != null && m.getContent() != null && !m.getContent().isBlank())
                .map(MessageVO::of)
                .toList();
    }

    /**
     * 删除会话（归属校验）：连同其消息与成员关系一并删除。
     *
     * @param id 会话主键
     */
    @Transactional
    public void delete(Long id) {
        ActorConversation c = requireOwned(id);
        // 先删消息与成员（表间无外键级联，需显式清理），再删会话本身
        messageRepository.deleteByConversationId(id);
        memberRepository.deleteByConversationId(id);
        conversationRepository.delete(c);
    }

    /**
     * 将会话实体组装为 VO（含成员详情列表，B5 成员类型化）。
     *
     * @param c 会话实体
     *
     * @return VO 对象
     */
    private ConversationVO detailVO(ActorConversation c) {
        List<ConversationMemberVO> members = memberRepository.findByConversationId(c.getId())
                .stream().map(m -> {
                    // ：npc:{id} 不再编码类型，此处按意图 B 处理（member_type 经 NpcRefs 归一）
                    String type = NpcRefs.normalizeType(m.getMemberType());
                    String name = m.getMemberName();
                    if (name == null || name.isBlank()) {
                        name = roleplayContextService.characterName(type, m.getCharacterId());
                    }
                    return new ConversationMemberVO(m.getCharacterId(), type, name);
                }).toList();
        return ConversationVO.of(c, members);
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 按 id + 当前用户归属查询会话。
     * <p>默认实现委托显式 userId 版本（取当前线程 SecurityContext 用户）。
     * 仅可在请求线程调用；异步/后台线程无 SecurityContext 时必须用
     * {@link #requireOwned(Long, Long)} 并显式传入从请求线程捕获的 userId。</p>
     *
     * @param id 会话主键
     *
     * @return 会话实体
     */
    public ActorConversation requireOwned(Long id) {
        return requireOwned(id, currentUserProvider.currentUserId());
    }

    /**
     * 按 id + 显式归属用户查询会话（异步/后台线程安全）。
     * <p>关键：OIDC 启用时 currentUserId() 依赖 SecurityContextHolder，而虚拟线程
     * 不会继承 SecurityContext——异步任务（如记忆抽取）内解析会错误回退到演示用户 id=1，
     * 导致真实用户的会话被误判「不存在或无权访问」。本重载让调用方把请求线程捕获的
     * userId 显式传入做归属校验，规避该问题。</p>
     *
     * @param id 会话主键
     *
     * @param userId 归属用户 ID（调用方保证为请求线程解析的真实用户）
     *
     * @return 会话实体
     */
    public ActorConversation requireOwned(Long id, Long userId) {
        ActorConversation c = conversationRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "会话不存在或无权访问"));
        if (!userId.equals(c.getUserId())) {
            throw new BizException(404, "会话不存在或无权访问");
        }
        return c;
    }

    /**
     * 空白字符串归一化：null 与纯空白统一转为 null，避免存空串导致「远程通讯」判定歧义。
     *
     * @param s 原始字符串
     *
     * @return 归一化后的字符串（null 或去首尾空白后的非空值）
     */
    private String normalizeBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
