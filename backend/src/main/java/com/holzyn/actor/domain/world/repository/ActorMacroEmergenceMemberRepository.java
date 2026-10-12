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

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorMacroEmergenceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 二阶涌现参与成员仓储。
 * <p>查询口径：按涌现事件（详情回放参与角色明细）、按项目（调试面板）。</p>
 */
public interface ActorMacroEmergenceMemberRepository extends JpaRepository<ActorMacroEmergenceMember, Long> {

    /** 某涌现事件全部参与成员 */
    List<ActorMacroEmergenceMember> findByEmergenceIdOrderByIdAsc(Long emergenceId);

    /** 某项目全部参与成员（调试面板） */
    List<ActorMacroEmergenceMember> findByProjectIdOrderByIdAsc(Long projectId);
}
