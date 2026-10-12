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

package com.holzyn.actor.domain.worldgen.repository;

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 世界观生成工作流定义仓库（actor_worldgen_workflow）。
 */
public interface ActorWorldgenWorkflowRepository extends JpaRepository<ActorWorldgenWorkflow, Long> {

    /**
     * 按编码查询（唯一）。
     *
     * @param code 工作流编码（如 worldgen_build_v1）
     *
     * @return 匹配的定义（可能为空）
     */
    Optional<ActorWorldgenWorkflow> findByCode(String code);

    /**
     * 查询全部启用的工作流（按 id 升序，顺序稳定）。
     *
     * @param enabled 启用标记（1 启用）
     *
     * @return 启用的工作流列表
     */
    List<ActorWorldgenWorkflow> findByEnabledOrderByIdAsc(Integer enabled);
}
