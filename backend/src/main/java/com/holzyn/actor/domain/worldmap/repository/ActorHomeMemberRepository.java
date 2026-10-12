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

package com.holzyn.actor.domain.worldmap.repository;

import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 住户仓库（actor_home_member）。
 * <p>职责：按项目/住所/角色查询住户行；删除项目全部住户（地图重生成幂等重建用）。</p>
 */
public interface ActorHomeMemberRepository extends JpaRepository<ActorHomeMember, Long> {

    /** 按项目查询全部住户（home_id 升序 + id 升序，稳定顺序） */
    List<ActorHomeMember> findByProjectIdOrderByHomeIdAscIdAsc(Long projectId);

    /** 按住所查询住户（id 升序：户主排前由调用方排序） */
    List<ActorHomeMember> findByHomeIdOrderByIdAsc(Long homeId);

    /** 按角色查询住户行（唯一语义：每角色恰一间住所） */
    Optional<ActorHomeMember> findByProjectIdAndCharacterRef(Long projectId, String characterRef);

    /**
     * 按项目删除全部住户（重生成时先清后建，幂等）。
     * <p>⚠️ 必须 bulk 立即执行（flushAutomatically）：同一事务内先删后插时，
     * Hibernate flush 默认 INSERT 先于 DELETE 会触发唯一约束冲突（冒烟实证 2026-10-06）。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ActorHomeMember m where m.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    /** 按项目删除指定住所的住户（搬空/废弃时用） */
    void deleteByProjectIdAndHomeId(Long projectId, Long homeId);
}
