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

package com.holzyn.actor.domain.npc.memory.repository;

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryWorking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * L1 短期（工作记忆）仓库。
 * <p>职责：工作记忆条目 CRUD——按角色/会话 key/状态/类型/过期时间查询；
 * 起支撑：会话热缓冲注入（active 项）、会话结束双缓冲巩固（按 session_key 取 active）、
 * TTL 清理（expires_at 过期）、定时巩固扫描（created_at 超龄）、工作记忆列表 API（分页）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.1（L1 表结构 + 索引
 */
public interface ActorMemoryWorkingRepository extends JpaRepository<ActorMemoryWorking, Long> {

    // ==================== 会话热缓冲注入 / 列表 / 淘汰 ====================

    /**
     * 按角色 + 状态查工作项（新→旧；热缓冲注入与列表用）。
     */
    List<ActorMemoryWorking> findByProjectIdAndCharacterRefAndStatusAndDeletedOrderByCreatedAtDesc(
            Long projectId, String characterRef, String status, Integer deleted);

    /**
     * 按角色 + 类型 + 状态查（context/perception 单槽替换、task 上限淘汰用）。
     */
    List<ActorMemoryWorking> findByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeletedOrderByCreatedAtDesc(
            Long projectId, String characterRef, String itemType, String status, Integer deleted);

    /**
     * 统计某角色某状态工作项数（上限淘汰判定用）。
     */
    long countByProjectIdAndCharacterRefAndStatusAndDeleted(
            Long projectId, String characterRef, String status, Integer deleted);

    /**
     * 统计某角色某类型某状态工作项数（context/perception 单槽判定用）。
     */
    long countByProjectIdAndCharacterRefAndItemTypeAndStatusAndDeleted(
            Long projectId, String characterRef, String itemType, String status, Integer deleted);

    // ==================== 会话结束双缓冲巩固 ====================

    /**
     * 按会话 key + 状态查（会话结束巩固：取该会话全部 active 工作项提升/过期）。
     */
    List<ActorMemoryWorking> findBySessionKeyAndStatusAndDeletedOrderByCreatedAtDesc(
            String sessionKey, String status, Integer deleted);

    // ==================== TTL 清理 / 定时巩固扫描 ====================

    /**
     * 查全部已过期 active 项（TTL 清理：expires_at < now → status=expired）。
     */
    List<ActorMemoryWorking> findByStatusAndDeletedAndExpiresAtBefore(
            String status, Integer deleted, LocalDateTime before);

    /**
     * 查全部超龄 active 项（定时巩固扫描：created_at < 阈值 → 提升/过期，防工作记忆表滞留膨胀）。
     */
    List<ActorMemoryWorking> findByStatusAndDeletedAndCreatedAtBefore(
            String status, Integer deleted, LocalDateTime before);

    /**
     * 查全部已终结（consolidated/expired）且超保留期项（二次物理清理）。
     */
    List<ActorMemoryWorking> findByStatusAndDeletedAndUpdatedAtBefore(
            String status, Integer deleted, LocalDateTime before);

    // ==================== 工作记忆列表 API（分页，status 可选） ====================

    /**
     * 工作记忆列表（角色级，status 可选；{@code:status} 为 null 时不过滤）。
     */
    @Query("""
            SELECT w FROM ActorMemoryWorking w
            WHERE w.projectId = :projectId AND w.characterRef = :characterRef AND w.deleted = 0
              AND (:status IS NULL OR w.status = :status)
            ORDER BY w.createdAt DESC
            """)
    Page<ActorMemoryWorking> findByProjectAndRefOptionalStatus(
            @Param("projectId") Long projectId,
            @Param("characterRef") String characterRef,
            @Param("status") String status,
            Pageable pageable);

    /**
     * 工作记忆列表（项目级，status 可选；{@code:status} 为 null 时不过滤）。
     */
    @Query("""
            SELECT w FROM ActorMemoryWorking w
            WHERE w.projectId = :projectId AND w.characterRef IS NULL AND w.deleted = 0
              AND (:status IS NULL OR w.status = :status)
            ORDER BY w.createdAt DESC
            """)
    Page<ActorMemoryWorking> findByProjectOptionalRefOptionalStatus(
            @Param("projectId") Long projectId,
            @Param("status") String status,
            Pageable pageable);

    // ===== （记忆治理健康度）：项目维度工作项查询 =====

    /**
     * 按项目 + 状态查（项目级 consolidateProject 用）。
     */
    List<ActorMemoryWorking> findByProjectIdAndStatusAndDeleted(
            Long projectId, String status, Integer deleted);

    /**
     * 按项目 + 类型 + 状态查。
     */
    List<ActorMemoryWorking> findByProjectIdAndItemTypeAndStatusAndDeleted(
            Long projectId, String itemType, String status, Integer deleted);
}
