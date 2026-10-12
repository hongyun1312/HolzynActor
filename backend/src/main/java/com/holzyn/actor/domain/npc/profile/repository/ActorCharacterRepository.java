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

package com.holzyn.actor.domain.npc.profile.repository;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 角色统一主表仓库。
 * <p><b>合并说明</b>：原 {@code ActorCharacterRepository} 的全部查询能力并入本接口，
 * 并以 {@code type} 区分 special / common——因为单表之后"普通型 NPC"不再是一张表，
 * 而是主表中 {@code type='common'} 的行。历史 {@code actor_profile_ordinary} 表与实体已物理废弃。</p>
 * <p><b>调用约定</b>：</p>
 * <ul>
 * <li>需要"全部 NPC"→ 用不带 type 的方法；</li>
 * <li>需要"特殊型角色"→ {@code type='special'}（表单页/对话角色选择等）；</li>
 * <li>需要"普通型 NPC"→ {@code type='common'}（人群调度/沙盘批量等）。</li>
 * </ul>
 */
public interface ActorCharacterRepository extends JpaRepository<ActorCharacter, Long> {

    // ==================== 全量（不分类型） ====================

    /**
     * 查询某项目未删除的全部 NPC（按创建顺序；含 special 与 common）。
     *
     * @param projectId 项目 ID
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByProjectIdAndDeletedOrderByIdAsc(Long projectId, Integer deleted);

    /**
     * 查询全部未删除 NPC（跨项目，按创建顺序）——BrainProfile 存量补行遍历用。
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByDeletedOrderByIdAsc(Integer deleted);

    /**
     * 查询某项目全部 NPC（含软删除；导入/导出/迁移用）。
     *
     * @param projectId 项目 ID
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 按 id + 项目查询（归属校验）。
     *
     * @param id 主键
     *
     * @param projectId 项目 ID
     *
     * @return 匹配行（可能为空）
     */
    Optional<ActorCharacter> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 按名称解析（关系拓扑/生成去重用，同项目内；可能同名多个）。
     *
     * @param projectId 项目 ID
     *
     * @param name 名称
     *
     * @return 匹配列表
     */
    List<ActorCharacter> findByProjectIdAndName(Long projectId, String name);

    /**
     * 按一批主键批量查询（关系端点解析/批量渲染用）。
     *
     * @param ids 主键集合
     *
     * @return 匹配列表
     */
    List<ActorCharacter> findByIdIn(List<Long> ids);

    /**
     * 删除某项目全部 NPC（数据清空/导入覆盖用）。
     *
     * @param projectId 项目 ID
     */
    void deleteByProjectId(Long projectId);

    // ==================== 按类型（special / common） ====================

    /**
     * 查询某项目的指定类型 NPC（按创建顺序）。
     *
     * @param projectId 项目 ID
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByProjectIdAndTypeOrderByIdAsc(Long projectId, String type);

    /**
     * 查询某项目指定类型且未删除的 NPC（按创建顺序）——NPC 列表/沙盘/人群调度主查询。
     *
     * @param projectId 项目 ID
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByProjectIdAndTypeAndDeletedOrderByIdAsc(Long projectId, String type, Integer deleted);

    /**
     * 查询某项目未删除且位于指定地点的 NPC（图谱查询层·地点视角「在场角色」用）。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 归一化地点 ID（actor_world_location.id）
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return NPC 列表
     */
    List<ActorCharacter> findByProjectIdAndLocationIdAndDeletedOrderByIdAsc(Long projectId, Long locationId,
                                                                            Integer deleted);

    /**
     * 统计某项目未删除 NPC 数量（图谱查询层·概览计数用）。
     *
     * @param projectId 项目 ID
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return 数量
     */
    long countByProjectIdAndDeleted(Long projectId, Integer deleted);

    /**
     * 统计某项目指定类型 NPC 数量（含软删除；人群上限校验用）。
     *
     * @param projectId 项目 ID
     *
     * @param type 类型
     *
     * @return 数量
     */
    long countByProjectIdAndType(Long projectId, String type);

    /**
     * 统计某项目未删除的指定类型 NPC 数量。
     *
     * @param projectId 项目 ID
     *
     * @param type 类型
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return 数量
     */
    long countByProjectIdAndTypeAndDeleted(Long projectId, String type, Integer deleted);

    /**
     * 按名称解析指定类型（生成去重；同项目内）。
     *
     * @param projectId 项目 ID
     *
     * @param type 类型
     *
     * @param name 名称
     *
     * @return 匹配列表
     */
    List<ActorCharacter> findByProjectIdAndTypeAndName(Long projectId, String type, String name);

    /**
     * 删除某项目指定类型全部 NPC（人群重建时只清普通型，不动特殊角色）。
     *
     * @param projectId 项目 ID
     *
     * @param type 类型
     */
    void deleteByProjectIdAndType(Long projectId, String type);

    /**
     * 关键词检索（观测域全局搜索用，只读）。
     * <p>命中字段：name / title / occupation / affiliation / detail；大小写不敏感子串匹配；
     * 仅检索未软删除行（deleted=0）；名称命中优先，其次按 importance 降序、id 升序；
     * 由调用方 Pageable 限制条数（不加载全表）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（已 trim）
     *
     * @param pageable  分页/条数限制
     *
     * @return 命中的角色（名称命中优先）
     */
    @Query("select c from ActorCharacter c where c.projectId = :projectId and c.deleted = 0 and ("
            + "lower(c.name) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(c.title, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(c.occupation, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(c.affiliation, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(c.detail, '')) like lower(concat('%', :q, '%')))"
            + " order by case when lower(c.name) like lower(concat('%', :q, '%')) then 0 else 1 end,"
            + " coalesce(c.importance, 1) desc, c.id asc")
    List<ActorCharacter> searchByKeyword(@Param("projectId") Long projectId,
                                         @Param("q") String q,
                                         Pageable pageable);
}