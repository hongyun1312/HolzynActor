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

import com.holzyn.actor.domain.worldmap.entity.ActorMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 在途移动瞬态仓库（位置移动与地图生成系统 · M2，actor_movement）。
 * <p>职责：按项目/状态/角色引用查询移动行；到达/打断更新；重生成地图时整项目清理
 * （坐标系变更后旧移动失效）。</p>
 */
public interface ActorMovementRepository extends JpaRepository<ActorMovement, Long> {

    /** 按项目查询在途移动（walking） */
    List<ActorMovement> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    /** 按项目查询全部移动（列表倒序，供调试/历史） */
    List<ActorMovement> findByProjectIdOrderByIdDesc(Long projectId);

    /** 按项目 + 角色引用查询某角色的移动（最新在前） */
    List<ActorMovement> findByProjectIdAndCharacterRefOrderByIdDesc(Long projectId, String characterRef);

    /** 按项目 + 角色引用 + 状态查询（在途判定用） */
    Optional<ActorMovement> findFirstByProjectIdAndCharacterRefAndStatusOrderByIdDesc(
            Long projectId, String characterRef, String status);

    /** 角色是否在途（存在 walking 行） */
    boolean existsByProjectIdAndCharacterRefAndStatus(Long projectId, String characterRef, String status);

    /**
     * 整项目清理移动行（地图重生成坐标系变更后旧移动失效）。
     * <p>⚠️ Hibernate flush 顺序坑：derived delete 在 flush 时与同事务 insert 的先后不定，
     * 但移动行无唯一约束、清理后同项目后续 tick 会重建，无冲突风险；仍用 bulk 立即执行
     * （对齐 M1 住所/住户 deleteByProjectId 的 @Modifying 处置）。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ActorMovement m where m.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") Long projectId);
}
