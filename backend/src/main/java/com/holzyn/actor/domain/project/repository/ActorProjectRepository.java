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

package com.holzyn.actor.domain.project.repository;

import com.holzyn.actor.domain.project.entity.ActorProject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/**
 * 项目仓库（actor_project）。
 * <p>职责：提供按用户归属查询与软删除过滤的项目访问能力。</p>
 */
public interface ActorProjectRepository extends JpaRepository<ActorProject, Long> {

    /**
     * 查询某用户未删除的项目列表（按更新时间倒序）。
     *
     * @param userId 归属用户 ID
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return 项目列表
     */
    List<ActorProject> findByUserIdAndDeletedOrderByUpdatedAtDesc(Long userId, Integer deleted);

    /**
     * 查询全部未删除的项目（B3 世界推进引擎 scanAndAdvance 扫描用——不再按「最近有对话」
     * 的活跃窗口筛选，改为按每项目 world_running 常驻开关筛选，修 L-02/）。
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return 项目列表（按 id 升序，顺序稳定）
     */
    List<ActorProject> findByDeletedOrderByIdAsc(Integer deleted);

    /**
     * 按 id + 用户 + 未删除查询（归属校验 + 软删除过滤）。
     *
     * @param id 项目主键
     *
     * @param userId 归属用户 ID
     *
     * @param deleted 软删除标记（0 正常）
     *
     * @return 匹配的项目（可能为空）
     */
    Optional<ActorProject> findByIdAndUserIdAndDeleted(Long id, Long userId, Integer deleted);

    /**
     * 按项目 UUID 查询（.holzyn 导入幂等检测依据）。
     *
     * @param projectUid 项目 UUID
     *
     * @return 匹配的项目（可能为空）
     */
    Optional<ActorProject> findByProjectUid(String projectUid);

    /**
     * 关键词检索（观测域全局搜索用，只读）。
     * <p>只检索**当前项目本体**（id 相等），命中字段：name / summary / code；
     * 用途是让「搜索项目名」能从搜索结果回到项目入口，不做跨项目越权检索。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（已 trim）
     *
     * @param pageable  分页/条数限制
     *
     * @return 命中的项目（至多 1 条）
     */
    @Query("select p from ActorProject p where p.id = :projectId and ("
            + "lower(p.name) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(p.summary, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(p.code, '')) like lower(concat('%', :q, '%')))"
            + " order by p.id asc")
    List<ActorProject> searchByKeyword(@Param("projectId") Long projectId,
                                       @Param("q") String q,
                                       Pageable pageable);
}