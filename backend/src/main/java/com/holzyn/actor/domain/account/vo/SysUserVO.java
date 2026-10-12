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

package com.holzyn.actor.domain.account.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统用户视图对象。
 * <p>职责：作为用户中心/会话接口返回的用户信息载体（含昵称、头像、认证状态、管理员标记等）。</p>
 */
@Data
public class SysUserVO {
    /** 用户 ID */
    private Long id;
    /** Casdoor 用户唯一标识 */
    private String casdoorUserId;
    /** 登录名 */
    private String name;
    /** 昵称（用户中心可编辑） */
    private String nickname;
    /** 头像 URL */
    private String avatar;
    /** 绑定邮箱 */
    private String email;
    /** 绑定手机号 */
    private String phone;
    /** 个人简介 */
    private String bio;
    /** 实名认证状态 */
    private Boolean verified;
    /** 是否管理员 */
    private Boolean isAdmin;
    /** 注册时间 */
    private LocalDateTime createTime;
    /** 时间 */
    private LocalDateTime updateTime;
}