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

package com.holzyn.actor.ai.nanojev.ab.repository;

import com.holzyn.actor.ai.nanojev.ab.entity.ActorNanoJevAbRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * NanoJev A/B 批跑主表仓库。
 */
public interface ActorNanoJevAbRunRepository extends JpaRepository<ActorNanoJevAbRun, Long> {

    /** 项目最近 N 条 A/B 批跑（按创建时间倒序）。 */
    List<ActorNanoJevAbRun> findTop20ByProjectIdOrderByIdDesc(Long projectId);
}
