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

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 中观组织仓储。
 * <p>查询口径：按项目/类型/状态筛选；uk(project_id+name) 幂等；自发成组/解散/合并生命周期
 * 用「按项目全量」+「按名查」。</p>
 */
public interface ActorMesoGroupRepository extends JpaRepository<ActorMesoGroup, Long> {

    /** 某项目全部组织（派生势力/指令路由/调试面板用） */
    List<ActorMesoGroup> findByProjectIdOrderByIdAsc(Long projectId);

    /** 某项目某类型组织（type=force 派生势力 / adhoc 自发群体等） */
    List<ActorMesoGroup> findByProjectIdAndTypeOrderByIdAsc(Long projectId, String type);

    /** 某项目某状态组织 */
    List<ActorMesoGroup> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    /** 按项目+名称幂等查（uk 约束） */
    Optional<ActorMesoGroup> findByProjectIdAndName(Long projectId, String name);

    /** 某项目组织数（首次建组判定） */
    long countByProjectId(Long projectId);

    /** 某项目排除某类型的组织数（图谱查询层·概览计数用：势力维度口径=正式组织 type≠adhoc） */
    long countByProjectIdAndTypeNot(Long projectId, String type);
}
