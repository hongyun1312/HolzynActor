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

package com.holzyn.actor.domain.npc.dialogue.repository;

import com.holzyn.actor.domain.npc.dialogue.entity.ActorConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话仓库（actor_conversation）。
 * <p>职责：提供按项目+用户查询会话列表的能力（数据隔离）； 起支持「最近活跃项目」查询供世界模拟扫描。</p>
 */
public interface ActorConversationRepository extends JpaRepository<ActorConversation, Long> {

    /**
     * 查询某项目下某用户的会话列表（按更新时间倒序，最新在前）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     *
     * @return 会话列表
     */
    List<ActorConversation> findByProjectIdAndUserIdOrderByUpdatedAtDesc(Long projectId, Long userId);

    /**
     * 查询某项目下某类型的会话（无玩家群聊轮用）：
     * npc_group=「地点 NPC 内群」，按地点匹配/状态过滤由服务层内存处理（数量少）。
     *
     * @param projectId 项目 ID
     *
     * @param conversationType 会话类型（npc_group 等）
     *
     * @return 会话列表
     */
    List<ActorConversation> findByProjectIdAndConversationTypeAndStatus(Long projectId,
                                                                        String conversationType, String status);

    /**
     * 查询最近活跃的项目 ID 集合（活跃窗口内有最后消息的会话归属项目，去重）。
     * <p>世界模拟：仅推进近期活跃项目，避免全量扫描与无谓推进。</p>
     * <p>注意：必须用 @Query 显式投影 projectId——派生方法名 {@code findDistinctXxxBy...} 中的
     * subject 不支持指定返回字段，会把 {@code ProjectId} 当作结果类型标识并退化为
     * 「select distinct 实体」，返回 List&lt;ActorConversation&gt;，与声明返回 List&lt;Long&gt;
     * 不匹配，运行时抛出 ConversionFailedException（曾在线程 scheduling-1 的世界模拟定时任务中复现）。</p>
     *
     * @param after 活跃窗口起点（如 now-30 分钟）
     *
     * @return 活跃项目 ID 列表（去重）
     */
    @Query("select distinct c.projectId from ActorConversation c where c.lastMessageAt > :after")
    List<Long> findDistinctProjectIdByLastMessageAtAfter(@Param("after") LocalDateTime after);

    /**
     * 查询某项目下指定模式（single/group）的会话（专门测试接口 ② 状态聚合：
     * 群聊活跃统计用——group 模式会话集合 → 消息仓库按时间窗聚合）。
     *
     * @param projectId 项目 ID
     *
     * @param mode 会话模式（single/group）
     *
     * @return 会话列表
     */
    List<ActorConversation> findByProjectIdAndMode(Long projectId, String mode);
}
