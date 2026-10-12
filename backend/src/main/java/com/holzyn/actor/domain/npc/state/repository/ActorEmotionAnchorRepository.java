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

package com.holzyn.actor.domain.npc.state.repository;

import com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * PAD 基础情绪锚点仓库 ——情绪模型基础表持久层。
 * <p>职责：按 code 查询锚点（主导情绪判定/显式标签校验）；按排序查询全部锚点（最近锚点遍历）。</p>
 */
public interface ActorEmotionAnchorRepository extends JpaRepository<ActorEmotionAnchor, Long> {

    /** 按情绪编码查询（calm/happy/angry/…，唯一） */
    Optional<ActorEmotionAnchor> findByCode(String code);

    /** 按项目无关排序查询全部未删锚点（主导情绪最近锚点遍历用） */
    List<ActorEmotionAnchor> findByDeletedOrderBySortOrderAscIdAsc(Integer deleted);
}
