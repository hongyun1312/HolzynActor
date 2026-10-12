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

package com.holzyn.actor.domain.evaluation.repository;

import com.holzyn.actor.domain.evaluation.entity.ActorTestRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 测试运行时间线事件仓库。
 */
public interface ActorTestRunEventRepository extends JpaRepository<ActorTestRunEvent, Long> {

    /** 某 test run 的完整时间线（按 seq 升序，回放顺序）。 */
    List<ActorTestRunEvent> findByRunIdOrderBySeqAsc(Long runId);

    /** 某 test run 的最大事件序号（无则 0）。 */
    long countByRunId(Long runId);
}
