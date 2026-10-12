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

package com.holzyn.actor.domain.growth.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 角色档案版本历史仓库。
 * <p>只增不改的版本快照查询：按角色取全量历史（升序）、按角色+版本号取单版（回滚用）、
 * 按项目清空（导入覆盖/项目删除时联动）。</p>
 */
public interface ActorProfileRevisionRepository extends JpaRepository<ActorProfileRevision, Long> {

    /**
     * 查询某角色全部档案版本（按版本号升序）。
     *
     * @return 版本列表（升序；无历史返回空列表）
     */
    List<ActorProfileRevision> findByProfileIdOrderByPersonaVersionAsc(Long profileId);

    /**
     * 查询某角色的指定版本（回滚源）。
     *
     * @param personaVersion 档案版本号
     *
     * @return 匹配行（可能为空）
     */
    Optional<ActorProfileRevision> findByProfileIdAndPersonaVersion(Long profileId, Integer personaVersion);

    /**
     * 删除某项目的全部版本历史（导入覆盖 / 项目级清理时联动；只增不改表在正常使用中不删除）。
     *
     * @param projectId 项目 ID
     */
    void deleteByProjectId(Long projectId);
}
