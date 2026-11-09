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

import com.holzyn.actor.domain.item.entity.ActorItemLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 物品账本仓储（§4.4.5）。
 *
 * <p>幂等判定只认 {@code (project_id, idempotency_key)}：唯一索引是最后一道闸门，
 * 但正常路径先用 {@link #existsByProjectIdAndIdempotencyKey} 短路，避免把
 * 约束冲突异常抛进事务（H2 会把事务标成 rollback-only）。</p>
 */
@Repository
public interface ActorItemLedgerRepository extends JpaRepository<ActorItemLedger, Long> {

    /**
     * 幂等键是否已存在。
     *
     * @param projectId      项目 ID
     * @param idempotencyKey 幂等键
     *
     * @return true=已处理过
     */
    boolean existsByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    /**
     * 某项目的全部账本（按写入顺序；冒烟/对账用）。
     *
     * @param projectId 项目 ID
     *
     * @return 账本列表
     */
    List<ActorItemLedger> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 某项目最近的账本（新→旧，可分页）。
     *
     * <p>物品面板的「最近流水」：世界跑久了账本会有上万行，绝不能整表往回读。</p>
     *
     * @param projectId 项目 ID
     *
     * @param pageable  分页（第 0 页 + 每页条数）
     *
     * @return 账本列表
     */
    org.springframework.data.domain.Page<ActorItemLedger> findByProjectIdOrderByIdDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);

    /**
     * 与某持有者相关的最近账本（新→旧）。
     *
     * <p>账本没有 {@code holder_ref} 列：一次变动是「从 A 到 B」，持有者出现在任一端。
     * 所以按 {@code fromRef = holder 或 toRef = holder} 查，而不是硬加一列冗余。</p>
     *
     * @param projectId 项目 ID
     *
     * @param holderRef 持有者 ref
     *
     * @param pageable  分页
     *
     * @return 账本列表
     */
    @org.springframework.data.jpa.repository.Query("select l from ActorItemLedger l "
            + "where l.projectId = :projectId and (l.fromRef = :holderRef or l.toRef = :holderRef) "
            + "order by l.id desc")
    org.springframework.data.domain.Page<ActorItemLedger> findRecentByHolder(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("holderRef") String holderRef,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 某项目的账本条数。
     *
     * @param projectId 项目 ID
     *
     * @return 条数
     */
    long countByProjectId(Long projectId);
}
