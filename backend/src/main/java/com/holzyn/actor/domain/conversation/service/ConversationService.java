package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.conversation.entity.ActorConversationMember;
import com.holzyn.actor.domain.conversation.entity.ActorMessage;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.conversation.vo.MessageVO;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationMemberRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.conversation.repository.ActorMessageRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与消息业务服务（A-C4）。
 * <p>职责：提供单聊/群聊会话的创建、列表、详情与历史消息读取；
 * 角色归属通过 会话→项目→用户 校验，越权访问返回 404。</p>
 * <p>所属模块：service/conversation（对话子域）</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ActorConversationRepository conversationRepository;
    private final ActorConversationMemberRepository memberRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorMessageRepository messageRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 创建会话（单聊/群聊）。
     *
     * @param projectId 项目 ID
     * @param dto       会话入参（mode/characterIds/location/gameTimeText）
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
        c.setLocation(normalizeBlank(dto.location()));
        c.setGameTimeText(normalizeBlank(dto.gameTimeText()));
        c.setWorldEventEnabled(0);
        ActorConversation saved = conversationRepository.save(c);

        List<Long> characterIds = dto.characterIds() == null ? List.of() : dto.characterIds();
        for (Long cid : characterIds) {
            // 校验角色确实属于该项目，防止越权加入他人角色
            ActorCharacter ch = characterRepository.findById(cid)
                    .filter(x -> projectId.equals(x.getProjectId()))
                    .filter(x -> Integer.valueOf(0).equals(x.getDeleted()))
                    .orElseThrow(() -> new BizException(400, "角色不存在或不属于该项目"));
            ActorConversationMember m = new ActorConversationMember();
            m.setConversationId(saved.getId());
            m.setCharacterId(ch.getId());
            m.setJoinTime(LocalDateTime.now());
            memberRepository.save(m);
        }
        return detailVO(saved);
    }

    /**
     * 更新会话「对话场景」（所在地 / 世界时间快照）。
     * <p>作用：会话创建后可随时调整对话发生的地点与时间，保存后会影响后续 NPC 的回答
     * （ChatService 组装消息时按最新场景注入）。</p>
     *
     * @param id        会话主键
     * @param location     对话所在地（null=不修改；空串=清空为远程通讯）
     * @param gameTimeText 世界时间快照（null=不修改；空串=清空）
     * @return 更新后的会话 VO
     */
    @Transactional
    public ConversationVO updateScene(Long id, String location, String gameTimeText) {
        ActorConversation c = requireOwned(id);
        if (location != null) {
            c.setLocation(normalizeBlank(location));
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
     * @return 会话 VO
     */
    @Transactional(readOnly = true)
    public ConversationVO detail(Long id) {
        return detailVO(requireOwned(id));
    }

    /**
     * 历史消息（按创建顺序升序）。
     *
     * @param id 会话主键
     * @return 消息 VO 列表
     */
    @Transactional(readOnly = true)
    public List<MessageVO> messages(Long id) {
        requireOwned(id);
        return messageRepository.findByConversationIdOrderByIdAsc(id).stream().map(MessageVO::of).toList();
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
     * 将会话实体组装为 VO（含成员角色 ID 列表）。
     *
     * @param c 会话实体
     * @return VO 对象
     */
    private ConversationVO detailVO(ActorConversation c) {
        List<Long> ids = memberRepository.findByConversationId(c.getId())
                .stream().map(ActorConversationMember::getCharacterId).toList();
        return ConversationVO.of(c, ids);
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
     * @param id     会话主键
     * @param userId 归属用户 ID（调用方保证为请求线程解析的真实用户）
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
     * @return 归一化后的字符串（null 或去首尾空白后的非空值）
     */
    private String normalizeBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}