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
 * 本地个人账户实体（本地单用户模式，exe/Web 通用）。
 * <p>职责：存储本地用户的个人信息——用于界面展示（昵称/头像/签名），
 * 以及「面向 NPC 的个性化档案」（身份/职业/喜好/禁忌 + 自由长文本），
 * 在对话时注入 NPC 上下文，让 NPC 基于对用户的了解做出定制化回答。</p>
 * <p>设计说明：所有字段均为选填；userId 固定为本地单用户 id=1（与历史 sys_user 演示用户约定一致）；
 * onboarded 标记是否完成首次设置向导。表：actor_local_account。</p>
 */
@Data
@Entity
@Table(name = "actor_local_account")
public class LocalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 ID（本地单用户，恒为 1；关联 sys_user.id 语义） */
    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;

    /** 昵称（显示名，侧边栏/顶栏/用户菜单展示；选填） */
    @Column(length = 64)
    private String nickname;

    /** 头像（本地路径/base64/URL；选填） */
    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    /** 个性签名（一句话简介；选填） */
    @Column(length = 255)
    private String signature;

    /** 结构化档案-身份（如「大学生 / 旅行者 / 编辑」；选填，注入 NPC 上下文） */
    @Column(length = 255)
    private String identity;

    /** 结构化档案-职业（选填，注入 NPC 上下文） */
    @Column(length = 255)
    private String occupation;

    /** 结构化档案-喜好（选填，注入 NPC 上下文） */
    @Column(length = 512)
    private String hobbies;

    /** 结构化档案-禁忌（NPC 应避免提及/冒犯的内容；选填，注入 NPC 上下文） */
    @Column(length = 512)
    private String taboos;

    /** 自由长文本「个人档案」（背景/经历/性格等；选填，注入 NPC 上下文） */
    @Lob
    @Column(name = "profile_text")
    private String profileText;

    /** 是否已完成首次设置向导：0 未完成/1 已完成 */
    @Column(nullable = false)
    private Integer onboarded = 0;

    /** 上次所在项目 ID（世界沙盘 S1：应用启动直达上次项目；可空，null=无上次项目回画廊） */
    @Column(name = "last_project_id")
    private Long lastProjectId;

    /**
     * 新用户 7 步引导完成标记：0 未完成/1 已完成（完成与「跳过」同义——都不再自动出现）。
     * <p>为什么放在后端：「完成后重启又出现」的根因是桌面端窗口 origin（含随机端口）每次启动都变，
     * localStorage 按 origin 隔离 → 本地标记丢失。完成状态必须以跨 origin 稳定的后端为唯一权威。</p>
     */
    @Column(name = "guide_completed", nullable = false)
    private Integer guideCompleted = 0;

    /** 引导完成时间（审计/排障用；未完成时为空） */
    @Column(name = "guide_completed_at")
    private LocalDateTime guideCompletedAt;

    /**
     * 「界面导览」（GuidedTour）是否已看过：0 未看过/1 已看过。
     * <p>与 {@link #guideCompleted} 同理：桌面端窗口 origin 每次启动可能变化，localStorage 标记会丢，
     * 只存本地就会「每次启动都自动再播一遍界面导览」。</p>
     */
    @Column(name = "layout_tour_seen", nullable = false)
    private Integer layoutTourSeen = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (userId == null) userId = 1L;
        if (onboarded == null) onboarded = 0;
        if (guideCompleted == null) guideCompleted = 0;
        if (layoutTourSeen == null) layoutTourSeen = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
