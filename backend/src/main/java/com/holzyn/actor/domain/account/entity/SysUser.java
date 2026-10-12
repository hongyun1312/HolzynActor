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

package com.holzyn.actor.domain.account.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统用户实体。
 * <p>职责：存储用户信息，与 Casdoor 身份平台同步（casdoorUserId 关联）。
 * 扩展：新增昵称/手机号/个人简介/实名状态/管理员标记/更新时间，支撑用户中心「基本资料」与权限控制。</p>
 */
@Data
@Entity
@Table(name = "sys_user")
public class SysUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Casdoor 用户唯一标识（登录集成后同步） */
    @Column(name = "casdoor_user_id", length = 128)
    private String casdoorUserId;
    /** 登录名（Casdoor 用户名） */
    private String name;
    /** 用户昵称（用户中心可编辑，缺省回退 name） */
    @Column(length = 64)
    private String nickname;
    /** 头像 URL（相对 /uploads 或绝对地址） */
    @Column(length = 512)
    private String avatar;
    /** 绑定邮箱 */
    @Column(length = 128)
    private String email;
    /** 绑定手机号 */
    @Column(length = 32)
    private String phone;
    /** 个人简介（用户中心可编辑） */
    @Column(length = 512)
    private String bio;
    /** 实名认证状态（占位：真实认证体系后续接入） */
    private Boolean verified = false;
    /** 是否管理员（登录时按 Casdoor isAdmin/用户名同步，用于 /api/admin 权限） */
    @Column(name = "is_admin")
    private Boolean isAdmin = false;
    @Column(name = "create_time")
    private LocalDateTime createTime;
    /** 时间（资料/状态变更自动维护） */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    void prePersist() {
        if (createTime == null) createTime = LocalDateTime.now();
        updateTime = createTime;
        if (verified == null) verified = false;
        if (isAdmin == null) isAdmin = false;
    }

    @PreUpdate
    void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}