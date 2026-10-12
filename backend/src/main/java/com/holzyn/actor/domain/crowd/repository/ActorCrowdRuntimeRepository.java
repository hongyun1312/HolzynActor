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

package com.holzyn.actor.domain.crowd.repository;

import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 普通型 NPC 项目级调度运行时仓库（actor_crowd_runtime）。
 * <p>职责：提供按项目定位运行时行（一项目一行）、查询全部启用定时调度的项目
 * （定时任务推进用）。</p>
 * <p><b>口径</b>：本仓库不受"统一主表"影响——{@code actor_crowd_runtime} 存的是
 * 项目级调度配置/快照，与 NPC 档案（{@code actor_profile}，普通型为 {@code type='common'} 的行）
 * 及运行态（{@code actor_state}）都不重叠，因此无需 type 过滤。</p>
 */
public interface ActorCrowdRuntimeRepository extends JpaRepository<ActorCrowdRuntime, Long> {

    /**
     * 按项目查询运行时行（唯一约束，一项目一行）。
     *
     * @param projectId 项目 ID
     *
     * @return 匹配的运行时行（可能为空）
     */
    Optional<ActorCrowdRuntime> findByProjectId(Long projectId);

    /**
     * 查询全部启用定时调度项目的运行时行。
     * <p>B3（双推进线收敛）：原 CrowdScheduledJob 真实钟 5 分钟推进已停用删除，
     * 人群逐时推进由 WorldEngine 按游戏小时驱动；本方法保留供手动/将来触发路径使用。</p>
     *
     * @param enabled 启用标记（1=启用）
     *
     * @return 启用项目的运行时行列表
     */
    List<ActorCrowdRuntime> findByEnabledOrderByIdAsc(Integer enabled);
}
