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

import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

/**
 * 长期记忆仓库。
 * <p>职责：提供按项目/角色/类型查询记忆的能力——角色级记忆（character_id 非空）、
 * 项目级记忆（character_id IS NULL）、全部记忆（分页）；软删标记统一按 deleted=0 过滤；
 * type 四类（semantic/episodic/reflection/plan）；会话摘要经 context_json subtype=summary 区分。</p>
 */
public interface ActorMemoryRepository extends JpaRepository<ActorMemory, Long> {

    /**
     * 查询某角色的全部未删记忆（按创建时间倒序，注入/淘汰用）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(
            Long projectId, Long characterId, Integer deleted);

    /**
     * 查询某角色的指定类型未删记忆（按创建时间倒序，摘要/事实分场景用）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
            Long projectId, Long characterId, String type, Integer deleted);

    /**
     * 统计某角色的指定类型未删记忆条数（MUSE 策略切换·经历卷计算用；索引覆盖，零全表扫描）。
     */
    long countByProjectIdAndCharacterIdAndTypeAndDeleted(
            Long projectId, Long characterId, String type, Integer deleted);

    /**
     * 查询某角色的多类未删记忆（按创建时间倒序，semantic+episodic 合并注入用）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndTypeInAndDeletedOrderByCreatedAtDesc(
            Long projectId, Long characterId, List<String> types, Integer deleted);

    /**
     * 查询某角色引用（character_ref：special:{id}/crowd:{id}）的全部未删记忆
     * （按创建时间倒序，去重/预算淘汰/检索用）。
     */
    List<ActorMemory> findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
            Long projectId, String characterRef, Integer deleted);

    /**
     * 查询某角色引用（character_ref）的指定类型未删记忆。
     */
    List<ActorMemory> findByProjectIdAndCharacterRefAndTypeAndDeletedOrderByCreatedAtDesc(
            Long projectId, String characterRef, String type, Integer deleted);

    /**
     * 查询某角色引用的全部未删记忆（分页，普通 NPC 记忆列表用）。
     */
    Page<ActorMemory> findByProjectIdAndCharacterRefAndDeleted(
            Long projectId, String characterRef, Integer deleted, Pageable pageable);

    /**
     * 查询项目级未删记忆（character_id IS NULL，按创建时间倒序）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(
            Long projectId, Integer deleted);

    /**
     * 查询某角色 + 项目级全部未删记忆（注入用，合并一次查询）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndDeletedOrderByIdAsc(
            Long projectId, Long characterId, Integer deleted);

    /**
     * 查询项目下全部未删记忆（分页，管理列表用）。
     */
    Page<ActorMemory> findByProjectIdAndDeleted(Long projectId, Integer deleted, Pageable pageable);

    /**
     * 查询项目下全部未删记忆（ID 倒序，时间线记忆里程碑用）。
     */
    List<ActorMemory> findByProjectIdAndDeletedOrderByIdDesc(Long projectId, Integer deleted);

    /**
     * 查询某角色未删记忆（分页，角色记忆列表用）。
     */
    Page<ActorMemory> findByProjectIdAndCharacterIdAndDeleted(
            Long projectId, Long characterId, Integer deleted, Pageable pageable);

    /**
     * 查询某角色的「会话摘要」未删记忆（type=semantic 且 context_json 含 {"subtype":"summary"}，
     * 按创建时间倒序—— 起会话摘要与事实同属 semantic，经 context_json 子类型区分）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @return 会话摘要记忆列表（新在前）
     */
    @Query("SELECT m FROM ActorMemory m WHERE m.projectId = :projectId AND m.characterId = :characterId "
            + "AND m.type = 'semantic' AND m.contextJson LIKE '%\"subtype\":\"summary\"%' AND m.deleted = 0 "
            + "ORDER BY m.createdAt DESC")
    List<ActorMemory> findSummariesByProjectIdAndCharacterId(@Param("projectId") Long projectId,
                                                             @Param("characterId") Long characterId);

    // ===== （决策链路可视化 · 漂移度量视图）：矛盾率聚合查询 =====

    /**
     * 查询某项目下指定类型的全部未删记忆（漂移度量：矛盾率=反思记忆含矛盾类关键词条数占比，
     * 按项目聚合，按 id 升序）。
     */
    List<ActorMemory> findByProjectIdAndTypeAndDeletedOrderByIdAsc(Long projectId, String type, Integer deleted);

    /**
     * 查询某项目 + 角色下指定类型的全部未删记忆（漂移度量：矛盾率按角色聚合，按 id 升序）。
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByIdAsc(
            Long projectId, Long characterId, String type, Integer deleted);

    /**
     * 查询全部指定类型的未删记忆（漂移度量：全局口径，无项目过滤时使用，按 id 升序）。
     */
    List<ActorMemory> findByTypeAndDeletedOrderByIdAsc(String type, Integer deleted);

    // ===== （记忆治理健康度）：版本链 / 分层分布聚合查询 =====

    /**
     * 查询某项目下已进入版本链（supersedes_id 非空）的未删记忆。
     * 按 id 升序。
     */
    List<ActorMemory> findByProjectIdAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(
            Long projectId, Integer deleted);

    /**
     * 查询某项目下指定角色的已进入版本链（supersedes_id 非空）未删记忆（按角色维度健康度）。
     */
    List<ActorMemory> findByProjectIdAndCharacterRefAndSupersedesIdIsNotNullAndDeletedOrderByIdAsc(
            Long projectId, String characterRef, Integer deleted);

    /**
     * 查询某项目全部未删记忆。
     */
    List<ActorMemory> findByProjectIdAndDeletedOrderByIdAsc(Long projectId, Integer deleted);

    // ===== （记忆写并发治理，）：L1→L2 蒸馏幂等守卫查询 =====

    /**
     * 查询某项目下「语义型巩固记忆」中 evidence_ids 精确包含指定 L1 working id 的未删行
     * （蒸馏幂等守卫用：evidence_ids 为逗号分隔的 id 列表，须按 token 边界精确匹配，
     * 避免 {@code LIKE '%34%'} 误命中 134/340 等子串；蒸馏写入的 evidence 恰为一个 id）。
     *
     * @param projectId 项目 ID
     *
     * @param wid L1 working id 文本（evidence_ids 中的一个 token）
     *
     * @return 命中行（按 id 升序）
     */
    @Query("SELECT m FROM ActorMemory m WHERE m.projectId = :projectId AND m.deleted = 0 "
            + "AND m.type = 'semantic' AND m.source = 'consolidation' AND "
            + "(m.evidenceIds = :wid OR m.evidenceIds LIKE CONCAT('%,', :wid) "
            + "OR m.evidenceIds LIKE CONCAT(:wid, ',%') OR m.evidenceIds LIKE CONCAT('%,', :wid, ',%')) "
            + "ORDER BY m.id ASC")
    List<ActorMemory> findConsolidatedL2ByEvidenceId(@Param("projectId") Long projectId,
                                                     @Param("wid") String wid);
}
