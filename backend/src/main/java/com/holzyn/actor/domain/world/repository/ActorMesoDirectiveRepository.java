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

import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 中观指令仓储。
 * <p>查询口径：按项目/状态（感知注入取 active 有效指令，到期过滤由服务层做）、
 * 按组织、手动发布（调试/定向冒烟）。</p>
 */
public interface ActorMesoDirectiveRepository extends JpaRepository<ActorMesoDirective, Long> {

    /** 某项目全部指令（按发布倒序；调试面板回放） */
    List<ActorMesoDirective> findByProjectIdOrderByIdDesc(Long projectId);

    /** 某项目某状态指令（active 有效指令=感知注入路由源） */
    List<ActorMesoDirective> findByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);

    /** 某项目某组织指令 */
    List<ActorMesoDirective> findByProjectIdAndGroupIdOrderByIdDesc(Long projectId, Long groupId);

    /** 某项目某组织有效指令 */
    List<ActorMesoDirective> findByProjectIdAndGroupIdAndStatusOrderByIdDesc(Long projectId, Long groupId, String status);

    /** 某项目 null 组（全组织广播）的有效指令 */
    List<ActorMesoDirective> findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(Long projectId, String status);

    /** 按 ID 查（幂等/单条更新） */
    Optional<ActorMesoDirective> findById(Long id);
}
