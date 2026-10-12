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
 * 本地个人账户视图对象。
 * <p>职责：作为本地账户接口（GET/PUT /api/local-account）的返回载体，
 * 供前端展示用户信息，并在设置页编辑；onboarded 供首次向导判断。</p>
 */
@Data
public class LocalAccountVO {

    /** 本地账户主键 */
    private Long id;

    /** 归属用户 ID（恒为 1） */
    private Long userId;

    /** 昵称（显示名；选填） */
    private String nickname;

    /** 头像（本地路径/base64/URL；选填） */
    private String avatarUrl;

    /** 个性签名（选填） */
    private String signature;

    /** 结构化档案-身份（选填） */
    private String identity;

    /** 结构化档案-职业（选填） */
    private String occupation;

    /** 结构化档案-喜好（选填） */
    private String hobbies;

    /** 结构化档案-禁忌（选填） */
    private String taboos;

    /** 自由长文本「个人档案」（选填，注入 NPC 上下文） */
    private String profileText;

    /** 是否已完成首次设置向导：0 未完成/1 已完成 */
    private Integer onboarded;

    /** 上次所在项目 ID（世界沙盘 S1：应用启动直达上次项目；可空） */
    private Long lastProjectId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 时间 */
    private LocalDateTime updatedAt;
}
