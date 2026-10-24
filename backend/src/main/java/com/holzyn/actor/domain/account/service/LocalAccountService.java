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

package com.holzyn.actor.domain.account.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.account.vo.LocalAccountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 本地个人账户服务（本地单用户模式）。
 * <p>职责：本地账户的读取/首次创建/更新/完成向导标记；对外提供 VO 转换与
 * 「NPC 个性化档案渲染文本」（供对话功能域注入 NPC 上下文使用）。</p>
 * <p>设计说明：本地单用户恒 userId=1；所有字段选填；onboarded 标记首次向导完成状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalAccountService {

    /** 本地单用户固定 ID（与历史 sys_user 演示用户约定一致） */
    public static final long LOCAL_USER_ID = 1L;

    private final LocalAccountRepository localAccountRepository;

    /**
     * 获取本地账户（不存在时惰性创建空档案，保证后续读取非空）。
     *
     * @return 本地账户实体
     */
    @Transactional
    public LocalAccount getOrCreate() {
        return localAccountRepository.findByUserId(LOCAL_USER_ID)
                .orElseGet(() -> {
                    LocalAccount account = new LocalAccount();
                    account.setUserId(LOCAL_USER_ID);
                    return localAccountRepository.save(account);
                });
    }

    /**
     * 获取本地账户（加行锁；不存在时惰性创建）。
     * <p>用于「引导完成 / 导览已看过」这类会并发写同一行的标记：整行读改写必须串行，
     * 否则后提交者用旧值覆盖前者（实测两列互相冲掉）。</p>
     *
     * @return 本地账户实体（已持有行锁）
     */
    @Transactional
    public LocalAccount getOrCreateForUpdate() {
        return localAccountRepository.findByUserIdForUpdate(LOCAL_USER_ID)
                .orElseGet(() -> {
                    LocalAccount account = new LocalAccount();
                    account.setUserId(LOCAL_USER_ID);
                    return localAccountRepository.save(account);
                });
    }

    /**
     * 查询本地账户（不创建；用于 NPC 上下文只读注入，避免触发写库）。
     *
     * @return 本地账户实体或空
     */
    @Transactional(readOnly = true)
    public LocalAccount find() {
        return localAccountRepository.findByUserId(LOCAL_USER_ID).orElse(null);
    }

    /**
     * 保存/更新本地账户（upsert：不存在则创建，存在则整体覆盖；空字段保留原值或清空由前端决定）。
     *
     * @param vo 本地账户请求内容（userId 字段忽略，恒写 1）
     *
     * @return 保存后的 VO
     */
    @Transactional
    public LocalAccountVO save(LocalAccountVO vo) {
        LocalAccount account = getOrCreate();
        // 空字符串也允许保存（用户主动清空某字段）；null 保持原值
        if (vo.getNickname() != null) account.setNickname(vo.getNickname());
        if (vo.getAvatarUrl() != null) account.setAvatarUrl(vo.getAvatarUrl());
        if (vo.getSignature() != null) account.setSignature(vo.getSignature());
        if (vo.getIdentity() != null) account.setIdentity(vo.getIdentity());
        if (vo.getOccupation() != null) account.setOccupation(vo.getOccupation());
        if (vo.getHobbies() != null) account.setHobbies(vo.getHobbies());
        if (vo.getTaboos() != null) account.setTaboos(vo.getTaboos());
        if (vo.getProfileText() != null) account.setProfileText(vo.getProfileText());
        if (vo.getOnboarded() != null) account.setOnboarded(vo.getOnboarded());
        // lastProjectId 不随通用保存覆盖（专用端点 setLastProject 维护，避免设置页误清空）
        // guideCompleted / layoutTourSeen 同理：只由专用端点维护，通用保存不得覆盖
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 记录「上次所在项目」（世界沙盘 S1：应用启动直达上次项目）。
     * <p>语义：进入项目时写入；只写不清（用户选择「始终回上次项目」）——
     * 返回画廊不清空，下次启动仍回上次项目（项目已删时由前端回退画廊）。</p>
     *
     * @param projectId 项目 ID（null 视为清空——预留，当前只写不清）
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO setLastProject(Long projectId) {
        LocalAccount account = getOrCreate();
        account.setLastProjectId(projectId);
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 标记首次设置向导完成。
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO markOnboarded() {
        LocalAccount account = getOrCreate();
        account.setOnboarded(1);
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 是否已完成首次设置向导。
     *
     * @return true 表示已完成
     */
    @Transactional(readOnly = true)
    public boolean isOnboarded() {
        LocalAccount account = find();
        return account != null && Integer.valueOf(1).equals(account.getOnboarded());
    }

    /**
     * 标记新用户 7 步引导已完成（「完成」与「跳过」同义：都不再自动出现）。
     * <p>唯一写入口：前端 FirstRunGuide 在完成/跳过时调用；完成后跨重启、跨窗口端口都保持。
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO markGuideCompleted() {
        LocalAccount account = getOrCreateForUpdate();
        account.setGuideCompleted(1);
        account.setGuideCompletedAt(LocalDateTime.now());
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 重置新用户引导完成标记（教程中心「重看新手引导」）。
     * <p>唯一写入口：重置后前端置位「待启动」标记并回画廊，引导重新开始。
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO resetGuide() {
        LocalAccount account = getOrCreateForUpdate();
        account.setGuideCompleted(0);
        account.setGuideCompletedAt(null);
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 是否已完成新用户引导（跨重启持久化的权威判断）。
     *
     * @return true 表示已完成或已跳过
     */
    @Transactional(readOnly = true)
    public boolean isGuideCompleted() {
        LocalAccount account = find();
        return account != null && Integer.valueOf(1).equals(account.getGuideCompleted());
    }

    /**
     * 标记「界面导览」（GuidedTour）已看过。
     * <p>为什么落后端：桌面端窗口 origin 每次启动可能不同，localStorage 里的 `tourSeen` 会丢，
     * 结果每次启动都自动再播一遍导览（与新用户引导叠加时还会互相遮挡）。
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO markLayoutTourSeen() {
        LocalAccount account = getOrCreateForUpdate();
        account.setLayoutTourSeen(1);
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 重置「界面导览已看过」标记（设置页/教程中心重看导览时可显式重置；当前重看只影响本机）。
     *
     * @return 更新后的 VO
     */
    @Transactional
    public LocalAccountVO resetLayoutTourSeen() {
        LocalAccount account = getOrCreateForUpdate();
        account.setLayoutTourSeen(0);
        return toVO(localAccountRepository.save(account));
    }

    /**
     * 渲染「NPC 个性化档案」文本（供对话功能域注入 NPC system prompt）。
     * <p>组成：身份/职业/喜好/禁忌 + 自由长文本；全部为空时返回空串（不注入）。</p>
     *
     * @return 渲染后的用户档案文本（可为空）
     */
    @Transactional(readOnly = true)
    public String renderNpcProfile() {
        LocalAccount a = find();
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        if (isNotBlank(a.getIdentity())) sb.append("身份：").append(a.getIdentity()).append("\n");
        if (isNotBlank(a.getOccupation())) sb.append("职业：").append(a.getOccupation()).append("\n");
        if (isNotBlank(a.getHobbies())) sb.append("喜好：").append(a.getHobbies()).append("\n");
        if (isNotBlank(a.getTaboos())) sb.append("禁忌（请勿主动提及或冒犯）：").append(a.getTaboos()).append("\n");
        if (isNotBlank(a.getProfileText())) sb.append("个人档案：").append(a.getProfileText()).append("\n");
        String text = sb.toString().trim();
        if (text.isEmpty()) return "";
        return "【你对用户的了解】\n" + text;
    }

    /**
     * 实体转 VO。
     *
     * @param account 本地账户实体
     *
     * @return 视图对象
     */
    public LocalAccountVO toVO(LocalAccount account) {
        if (account == null) return null;
        LocalAccountVO vo = new LocalAccountVO();
        vo.setId(account.getId());
        vo.setUserId(account.getUserId());
        vo.setNickname(account.getNickname());
        vo.setAvatarUrl(account.getAvatarUrl());
        vo.setSignature(account.getSignature());
        vo.setIdentity(account.getIdentity());
        vo.setOccupation(account.getOccupation());
        vo.setHobbies(account.getHobbies());
        vo.setTaboos(account.getTaboos());
        vo.setProfileText(account.getProfileText());
        vo.setOnboarded(account.getOnboarded());
        vo.setLastProjectId(account.getLastProjectId());
        vo.setGuideCompleted(account.getGuideCompleted());
        vo.setGuideCompletedAt(account.getGuideCompletedAt());
        vo.setLayoutTourSeen(account.getLayoutTourSeen());
        vo.setCreatedAt(account.getCreatedAt());
        vo.setUpdatedAt(account.getUpdatedAt());
        return vo;
    }

    /** 空白判断（null 或全空白视为空） */
    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
