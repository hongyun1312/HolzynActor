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

package com.holzyn.actor.domain.item.repository;

import com.holzyn.actor.domain.item.entity.ActorItemDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 物品定义仓储（§4.4.5）。
 *
 * <p>两级查找：先本项目（{@code projectId>0}），再全局模板（{@code projectId=0}）。
 * 不提供「跨项目模糊匹配」，避免一个世界的物品定义泄漏到另一个世界。</p>
 */
@Repository
public interface ActorItemDefRepository extends JpaRepository<ActorItemDef, Long> {

    /**
     * 按项目 + 物品键查定义。
     *
     * @param projectId 项目 ID（0=全局模板）
     * @param itemKey   物品键
     *
     * @return 定义
     */
    Optional<ActorItemDef> findByProjectIdAndItemKey(Long projectId, String itemKey);

    /**
     * 批量查某项目下的定义（列表展示用）。
     *
     * @param projectId 项目 ID
     * @param itemKeys  物品键集合
     *
     * @return 定义列表
     */
    List<ActorItemDef> findByProjectIdAndItemKeyIn(Long projectId, Collection<String> itemKeys);

    /**
     * 某项目登记过的全部定义（按物品键排序）。
     *
     * <p>物品面板用：一次把本项目 + 全局（{@code project_id=0}）的定义捞出来，
     * 前端才能把「柴刀」显示成「柴刀（武器/手上）」而不是一串键。</p>
     *
     * @param projectIds 项目 ID 集合（通常含 0 与本项目）
     *
     * @return 定义列表
     */
    List<ActorItemDef> findByProjectIdInOrderByProjectIdAscItemKeyAsc(Collection<Long> projectIds);
}
