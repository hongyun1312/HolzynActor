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

import com.holzyn.actor.domain.world.entity.ActorWorldChronicleArchive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 项目记忆编年史归档仓库。
 * <p>职责：编年史超龄归档的写入/检索（归档不参与日常注入，可按项目+游戏日回看）。</p>
 */
public interface ActorWorldChronicleArchiveRepository extends JpaRepository<ActorWorldChronicleArchive, Long> {

    /** 按项目倒序（归档检索）。 */
    List<ActorWorldChronicleArchive> findTop100ByProjectIdOrderByGameDayDescIdDesc(Long projectId);

    /** 按项目统计（概览用）。 */
    long countByProjectId(Long projectId);
}
