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

package com.holzyn.actor.domain.growth.lineage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 谱系边仓库。
 * <p>只增不改：本仓库只提供查询与新增，无删除——谱系是家族事实，历史不可篡改
 * （婚姻终止用 {@code active=0} 标记，不删行）。</p>
 */
public interface ActorGrowthLineageRepository extends JpaRepository<ActorGrowthLineage, Long> {

    List<ActorGrowthLineage> findByProjectIdOrderByIdAsc(Long projectId);

    List<ActorGrowthLineage> findByProjectIdAndFromRefOrderByIdAsc(Long projectId, String fromRef);

    List<ActorGrowthLineage> findByProjectIdAndToRefOrderByIdAsc(Long projectId, String toRef);

    /** 某角色作为父母一方的全部亲子边（from=本人，to=子女）。 */
    List<ActorGrowthLineage> findByProjectIdAndEdgeTypeAndFromRefOrderByIdAsc(
            Long projectId, String edgeType, String fromRef);

    /** 某角色作为子女的全部亲子边（from=父母，to=本人）。 */
    List<ActorGrowthLineage> findByProjectIdAndEdgeTypeAndToRefOrderByIdAsc(
            Long projectId, String edgeType, String toRef);

    /** 存续婚姻边（from=本人为夫的婚姻）。 */
    Optional<ActorGrowthLineage> findFirstByProjectIdAndEdgeTypeAndFromRefAndActiveOrderByIdDesc(
            Long projectId, String edgeType, String fromRef, Integer active);

    /** 存续婚姻边（to=本人为妻的婚姻）。 */
    Optional<ActorGrowthLineage> findFirstByProjectIdAndEdgeTypeAndToRefAndActiveOrderByIdDesc(
            Long projectId, String edgeType, String toRef, Integer active);

    /** 某婚姻对是否存在（夫 from + 妻 to，任意存续）。 */
    List<ActorGrowthLineage> findByProjectIdAndEdgeTypeAndFromRefAndToRef(
            Long projectId, String edgeType, String fromRef, String toRef);
}
