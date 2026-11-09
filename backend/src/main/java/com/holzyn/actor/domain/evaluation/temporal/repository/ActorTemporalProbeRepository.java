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

package com.holzyn.actor.domain.evaluation.temporal.repository;

import com.holzyn.actor.domain.evaluation.temporal.entity.ActorTemporalProbe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 时间性探针结果仓库（只读查询 + 审计层写入）。
 */
public interface ActorTemporalProbeRepository extends JpaRepository<ActorTemporalProbe, Long> {

    /** 项目内全部探针（倒序，调试页用）。 */
    List<ActorTemporalProbe> findByProjectIdOrderByIdDesc(Long projectId);

    /** 项目内按批次取探针（正序，报告/对照分析用）。 */
    List<ActorTemporalProbe> findByProjectIdAndRunTagOrderByIdAsc(Long projectId, String runTag);

    /** 项目内按类型取探针（正序）。 */
    List<ActorTemporalProbe> findByProjectIdAndProbeTypeOrderByIdAsc(Long projectId, String probeType);
}
