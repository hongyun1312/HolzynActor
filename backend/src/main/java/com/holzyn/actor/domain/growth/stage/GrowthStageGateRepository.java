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

package com.holzyn.actor.domain.growth.stage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 阶段能力门控仓库。
 */
public interface GrowthStageGateRepository extends JpaRepository<GrowthStageGate, Long> {

    /** 按项目无关的全量列表（门控表全局共享，不分项目——阶段是生理事实）。 */
    List<GrowthStageGate> findAllByOrderByStageAscSortOrderAsc();

    /** 按阶段取全部门控行。 */
    List<GrowthStageGate> findByStageOrderBySortOrderAsc(String stage);

    /** 是否存在某阶段 × 某能力的行（seed 幂等判定）。 */
    boolean existsByStageAndAbility(String stage, String ability);

    /** 统计行数（seed 是否已初始化）。 */
    long countByStageIsNotNull();
}
