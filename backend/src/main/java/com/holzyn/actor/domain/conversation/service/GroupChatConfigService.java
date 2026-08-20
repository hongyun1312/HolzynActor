package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.conversation.entity.ActorGroupChatConfig;
import com.holzyn.actor.domain.conversation.repository.ActorGroupChatConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 群聊配置服务（用户级）。
 * <p>职责：提供每轮回复上限的读取（默认 5）与保存（upsert），
 * 供 GroupChatService 编排读取与前端配置接口调用。</p>
 * <p>所属模块：service/conversation（对话子域-群聊配置）</p>
 */
@Service
@RequiredArgsConstructor
public class GroupChatConfigService {

    /** 默认每轮回复上限 */
    public static final int DEFAULT_MAX_REPLIES = 5;

    /** 群聊配置仓库 */
    private final ActorGroupChatConfigRepository repository;

    /**
     * 读取用户每轮回复上限（无配置时返回默认 5）。
     *
     * @param userId 归属用户 ID
     * @return 每轮回复上限（1~20）
     */
    public int getMaxReplies(Long userId) {
        return repository.findByUserId(userId)
                .map(ActorGroupChatConfig::getMaxReplies)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_MAX_REPLIES);
    }

    /**
     * 保存用户每轮回复上限（不存在则新建，存在则更新）。
     *
     * @param userId      归属用户 ID
     * @param maxReplies  每轮回复上限（1~20）
     * @return 保存后的上限
     */
    public int saveMaxReplies(Long userId, Integer maxReplies) {
        if (maxReplies == null || maxReplies < 1 || maxReplies > 20) {
            throw new BizException(400, "每轮回复上限须在 1~20 之间");
        }
        ActorGroupChatConfig cfg = repository.findByUserId(userId).orElseGet(() -> {
            ActorGroupChatConfig c = new ActorGroupChatConfig();
            c.setUserId(userId);
            return c;
        });
        cfg.setMaxReplies(maxReplies);
        cfg.setUpdatedAt(LocalDateTime.now());
        repository.save(cfg);
        return maxReplies;
    }
}