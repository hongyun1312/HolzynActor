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

package com.holzyn.actor.domain.growth.mask;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 社会面具运行态仓库。
 * <p>一行一 NPC 一情境码；{@code findFirstByProjectIdAndNpcRefAndContextCode} 供
 * {@code MaskService}「表优先 + 档案兜底」读取；{@code findByProjectIdAndNpcRef} 供调试页
 * 展示与演化批量操作。</p>
 */
public interface ActorGrowthMaskRepository extends JpaRepository<ActorGrowthMask, Long> {

    /** 某角色全部情境面具行（调试页面具状态卡数据源）。 */
    List<ActorGrowthMask> findByProjectIdAndNpcRefOrderByContextCodeAsc(Long projectId, String npcRef);

    /** 某角色某情境面具行（唯一键；无行返回 empty → 消费方回退档案 ⑪ 维）。 */
    Optional<ActorGrowthMask> findFirstByProjectIdAndNpcRefAndContextCode(
            Long projectId, String npcRef, String contextCode);

    /** 项目全量面具行（评估周期批量演化用）。 */
    List<ActorGrowthMask> findByProjectIdOrderByNpcRefAsc(Long projectId);
}
