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

import com.holzyn.actor.domain.item.entity.ActorItemRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 物品配方仓储（§4.4.5）。
 *
 * <p>只建仓储不调用；制作/建造阶段用 {@link #findByProjectIdAndRecipeKey}
 * 判「配方存不存在」，再由 {@code inputs_json} 判材料。</p>
 *
 * <p><b>两级查找</b>与 {@code ActorItemDefRepository} 同构：先本项目（{@code projectId>0}），
 * 再全局模板（{@code projectId=0}）。{@link #findByProjectIdAndOutputItemKey} 让「只知道要做什么
 * 东西、不知道配方键」的决策也能落到配方上。</p>
 */
@Repository
public interface ActorItemRecipeRepository extends JpaRepository<ActorItemRecipe, Long> {

    /**
     * 按项目 + 配方键查配方。
     *
     * @param projectId 项目 ID（0=全局模板）
     * @param recipeKey 配方键
     *
     * @return 配方
     */
    Optional<ActorItemRecipe> findByProjectIdAndRecipeKey(Long projectId, String recipeKey);

    /**
     * 按项目 + 产出物品键查配方（决策只说「打把柴刀」时用）。
     *
     * @param projectId     项目 ID（0=全局模板）
     * @param outputItemKey 产出物品键
     *
     * @return 配方列表（同一样东西可能有多条配方，调用方取第一条可用）
     */
    List<ActorItemRecipe> findByProjectIdAndOutputItemKeyOrderByIdAsc(Long projectId, String outputItemKey);

    /**
     * 某项目下全部可用配方（提示词里列「我会做什么」用；按 id 稳定排序保证文本可复现）。
     *
     * @param projectId 项目 ID（0=全局模板）
     * @param enabled   是否可用
     *
     * @return 配方列表
     */
    List<ActorItemRecipe> findByProjectIdAndEnabledOrderByIdAsc(Long projectId, Boolean enabled);
}
