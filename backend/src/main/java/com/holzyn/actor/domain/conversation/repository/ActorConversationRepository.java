package com.holzyn.actor.domain.conversation.repository;

import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话仓库（actor_conversation）。
 * <p>职责：提供按项目+用户查询会话列表的能力（数据隔离）；P4-2 起支持「最近活跃项目」查询供世界模拟扫描。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorConversationRepository extends JpaRepository<ActorConversation, Long> {

    /**
     * 查询某项目下某用户的会话列表（按更新时间倒序，最新在前）。
     *
     * @param projectId 项目 ID
     * @param userId    归属用户 ID
     * @return 会话列表
     */
    List<ActorConversation> findByProjectIdAndUserIdOrderByUpdatedAtDesc(Long projectId, Long userId);

    /**
     * 查询最近活跃的项目 ID 集合（活跃窗口内有最后消息的会话归属项目，去重）。
     * <p>P4-2 世界模拟：仅推进近期活跃项目，避免全量扫描与无谓推进。</p>
     * <p>注意：必须用 @Query 显式投影 projectId——派生方法名 {@code findDistinctXxxBy...} 中的
     * subject 不支持指定返回字段，会把 {@code ProjectId} 当作结果类型标识并退化为
     * 「select distinct 实体」，返回 List&lt;ActorConversation&gt;，与声明返回 List&lt;Long&gt;
     * 不匹配，运行时抛出 ConversionFailedException（曾在线程 scheduling-1 的世界模拟定时任务中复现）。</p>
     *
     * @param after 活跃窗口起点（如 now-30 分钟）
     * @return 活跃项目 ID 列表（去重）
     */
    @Query("select distinct c.projectId from ActorConversation c where c.lastMessageAt > :after")
    List<Long> findDistinctProjectIdByLastMessageAtAfter(@Param("after") LocalDateTime after);
}
