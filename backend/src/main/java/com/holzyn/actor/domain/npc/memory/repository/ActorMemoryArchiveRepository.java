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

import com.holzyn.actor.domain.npc.memory.entity.ActorMemoryArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 归档（冷存储）仓库。
 * <p>职责：归档条目检索（按项目/角色/类型/关键词/原因分页，Specification 动态过滤）、
 * 导出（全量倒序）、保留策略（每项目上限计数 / 软删超保留期物理清理）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.4/§8（归档）。
 * 充查询方法——列表过滤 / 导出 / 计数 / 二次清理。</p>
 */
public interface ActorMemoryArchiveRepository
        extends JpaRepository<ActorMemoryArchive, Long>, JpaSpecificationExecutor<ActorMemoryArchive> {

    /**
     * 查询某项目全部未删归档（按归档时间倒序，导出用；含软删过滤 deleted=0）。
     */
    List<ActorMemoryArchive> findByProjectIdAndDeletedOrderByArchivedAtDesc(Long projectId, Integer deleted);

    /**
     * 查询某项目某角色引用（可空=项目级/无归属）全部未删归档（按归档时间倒序，按角色导出/检索用）。
     */
    List<ActorMemoryArchive> findByProjectIdAndCharacterRefAndDeletedOrderByArchivedAtDesc(
            Long projectId, String characterRef, Integer deleted);

    /**
     * 统计某项目未删归档条数（每项目保留上限滚动判定用）。
     */
    long countByProjectIdAndDeleted(Long projectId, Integer deleted);

    /**
     * 查询某项目在指定时间之前已软删（deleted=1）的归档条目（超保留期物理二次清理用）。
     */
    List<ActorMemoryArchive> findByProjectIdAndDeletedAndUpdatedAtBefore(
            Long projectId, Integer deleted, LocalDateTime before);

    /**
     * 查询某项目归档时重要度最低且最旧的一批（每项目超上限时按「重要度低 + 旧」滚动软删；含全部 deleted 状态）。
     * <p>保留策略：超 max-per-project 时优先软删「归档时重要度低 + 归档时间早」的条目。</p>
     */
    List<ActorMemoryArchive> findByProjectIdOrderByImportanceAtArchiveAscArchivedAtAsc(Long projectId);

    // ===== （记忆治理健康度）：归档聚合查询 =====

    /**
     * 查询某项目全部未删归档（按归档时间倒序，健康度按原因/来源表分组用）。
     */
    List<ActorMemoryArchive> findByProjectIdAndDeletedOrderByArchivedAtAsc(Long projectId, Integer deleted);
}
