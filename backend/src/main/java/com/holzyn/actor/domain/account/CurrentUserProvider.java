package com.holzyn.actor.domain.account;

import com.holzyn.actor.domain.account.entity.SysUser;
import com.holzyn.actor.domain.account.service.LocalAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 当前用户解析器（本地单用户模式）。
 * <p>职责：统一解析「当前用户」——本地桌面/单机模式恒返回本地单用户（id=1），
 * 已移除 OIDC/登录鉴权逻辑；昵称优先取本地账户档案，缺省「本地用户」。</p>
 * <p>用法：各功能域控制器/服务注入本类，调用 currentUserId() / resolveUserId() 获取归属用户。</p>
 * <p>所属模块：domain/account（本地账户功能域）</p>
 */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final LocalAccountService localAccountService;

    /**
     * 解析当前用户（本地单用户）。
     *
     * @return 本地 SysUser（id=1；昵称优先取本地账户档案）
     */
    public SysUser currentUser() {
        SysUser u = new SysUser();
        u.setId(LocalAccountService.LOCAL_USER_ID);
        String nickname = localAccountService.find() != null
                ? localAccountService.find().getNickname() : null;
        u.setName(nickname != null && !nickname.isBlank() ? nickname : "本地用户");
        u.setNickname(u.getName());
        u.setIsAdmin(true);
        u.setVerified(true);
        return u;
    }

    /**
     * 解析当前用户 ID（本地单用户恒为 1）。
     *
     * @return 当前用户 ID（1）
     */
    public Long currentUserId() {
        return LocalAccountService.LOCAL_USER_ID;
    }

    /**
     * 解析"有效用户 ID"：本地单用户模式下恒为 1（忽略前端传入参数，保证数据归属统一）。
     *
     * @param paramUserId 前端传入的用户 ID（忽略）
     * @return 有效用户 ID（1）
     */
    public Long resolveUserId(Long paramUserId) {
        return LocalAccountService.LOCAL_USER_ID;
    }

    /**
     * 判断当前用户是否为管理员（本地单用户默认 true，保留兼容语义）。
     *
     * @return true
     */
    public boolean isAdmin() {
        return true;
    }
}
