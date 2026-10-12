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

import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 世界观设定仓库（actor_world_setting）。
 * <p>职责：提供按项目读取最新版本世界观的能力（版本化）。</p>
 */
public interface ActorWorldSettingRepository extends JpaRepository<ActorWorldSetting, Long> {

    /**
     * 查询某项目最新版本的世界观设定。
     *
     * @param projectId 项目 ID
     *
     * @return 版本号最大的世界观（可能为空）
     */
    Optional<ActorWorldSetting> findTopByProjectIdOrderByVersionDesc(Long projectId);
}