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

package com.holzyn.actor.domain.agent.runtime.repository;

import com.holzyn.actor.domain.agent.runtime.entity.ActorForesightRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 前瞻推演记录仓库。
 * <p>职责：前瞻记录 CRUD——按项目/角色倒序查（调试前端回放）、按项目全量查。</p>
 */
public interface ActorForesightRecordRepository extends JpaRepository<ActorForesightRecord, Long> {

    /** 按项目 + 角色倒序查（调试回放：最新在前）。 */
    List<ActorForesightRecord> findByProjectIdAndNpcRefOrderByIdDesc(Long projectId, String npcRef);

    /** 按项目倒序查（全项目回放：最新在前）。 */
    List<ActorForesightRecord> findByProjectIdOrderByIdDesc(Long projectId);
}
