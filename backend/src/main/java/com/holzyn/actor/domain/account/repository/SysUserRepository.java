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

import com.holzyn.actor.domain.account.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 系统用户仓库。
 * <p>职责：提供 SysUser 实体的数据库访问，支持按 Casdoor 用户唯一标识查询（登录同步用）。</p>
 */
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    /** 按 Casdoor 用户唯一标识查询（登录 upsert 时使用） */
    Optional<SysUser> findByCasdoorUserId(String casdoorUserId);
}