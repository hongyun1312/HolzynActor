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

package com.holzyn.actor.domain.sandbox.repository;

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 沙盘场景定义仓库（世界沙盘 S1，actor_sandbox_scene）。
 * <p>职责：按项目查询沙盘场景；S1 起读接口/后续 AI 坐标图落库共用。</p>
 */
public interface ActorSandboxSceneRepository extends JpaRepository<ActorSandboxScene, Long> {

    /** 按项目查询场景（创建时间升序，稳定顺序） */
    List<ActorSandboxScene> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    /** 按项目查询第一个场景（项目主场景） */
    Optional<ActorSandboxScene> findFirstByProjectIdOrderByIdAsc(Long projectId);
}
