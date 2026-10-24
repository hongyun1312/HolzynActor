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

package com.holzyn.actor.domain.account.repository;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 本地个人账户数据访问接口。
 * <p>职责：提供 LocalAccount 实体的数据库访问，按归属用户查询（本地单用户 id=1）。</p>
 */
public interface LocalAccountRepository extends JpaRepository<LocalAccount, Long> {

    /**
     * 按归属用户查询本地账户（单用户模式恒查 userId=1）。
     *
     * @param userId 归属用户 ID
     *
     * @return 本地账户（不存在时为空）
     */
    Optional<LocalAccount> findByUserId(Long userId);

    /**
     * 按归属用户查询并加行锁（用于「引导完成」「导览已看过」等独立标记的读改写）。
     *
     * <p>为什么需要行锁：引导完成瞬间会同时写两个标记（guide_completed / layout_tour_seen），
     * 两个并发请求都「整行读 → 改自己那列 → 整行写」，后提交者会用旧值覆盖前者
     * （实测：先落 guide_completed=1 被后到的 layout_tour_seen 写回 0）。加行锁后两个事务串行，
     * 第二次读到的是已提交的新值，两列都能保住。</p>
     *
     * @param userId 归属用户 ID
     *
     * @return 本地账户（不存在时为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LocalAccount a where a.userId = :userId")
    Optional<LocalAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
