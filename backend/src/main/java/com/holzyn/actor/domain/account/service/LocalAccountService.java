package com.holzyn.actor.domain.account.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.account.vo.LocalAccountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地个人账户服务（本地单用户模式）。
 * <p>职责：本地账户的读取/首次创建/更新/完成向导标记；对外提供 VO 转换与
 * 「NPC 个性化档案渲染文本」（供对话功能域注入 NPC 上下文使用）。</p>
 * <p>设计说明：本地单用户恒 userId=1；所有字段选填；onboarded 标记首次向导完成状态。</p>
 * <p>所属模块：domain/account/service（本地账户功能域-服务层）</p>
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
        vo.setCreatedAt(account.getCreatedAt());
        vo.setUpdatedAt(account.getUpdatedAt());
        return vo;
    }

    /** 空白判断（null 或全空白视为空） */
    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
