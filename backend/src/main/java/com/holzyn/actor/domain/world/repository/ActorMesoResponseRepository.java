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

import com.holzyn.actor.domain.world.entity.ActorMesoResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 中观指令成员响应仓储。
 * <p>查询口径：按项目（统计遵从/违抗分布）、按指令（指令回放）、按角色（个体响应历史）。</p>
 */
public interface ActorMesoResponseRepository extends JpaRepository<ActorMesoResponse, Long> {

    /** 某项目全部响应（按时间倒序；调试面板回放） */
    List<ActorMesoResponse> findByProjectIdOrderByIdDesc(Long projectId);

    /** 某项目某角色响应（感知/个体回放） */
    List<ActorMesoResponse> findByProjectIdAndActorRefOrderByIdDesc(Long projectId, String actorRef);

    /** 某指令全部响应（指令回放：谁遵从/谁违抗） */
    List<ActorMesoResponse> findByDirectiveIdOrderByIdAsc(Long directiveId);

    /** 某项目某响应类型计数（违抗累计→组内张力判定用） */
    long countByProjectIdAndResponseType(Long projectId, String responseType);
}
