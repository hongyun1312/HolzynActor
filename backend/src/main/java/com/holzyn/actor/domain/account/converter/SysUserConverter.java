package com.holzyn.actor.domain.account.converter;

import com.holzyn.actor.domain.account.entity.SysUser;
import com.holzyn.actor.domain.account.vo.SysUserVO;
import org.springframework.stereotype.Component;

/**
 * 系统用户实体转视图对象转换器。
 * <p>职责：将 SysUser 实体转换为 SysUserVO（用户中心/会话接口返回）。</p>
 * <p>所属模块：model/converter（转换器层-用户中心子域）</p>
 */
@Component
public class SysUserConverter {

    /**
     * 单个用户实体转 VO。
     *
     * @param u 用户实体
     * @return 视图对象；实体为 null 时返回 null
     */
    public SysUserVO toVO(SysUser u) {
        if (u == null) return null;
        SysUserVO vo = new SysUserVO();
        vo.setId(u.getId());
        vo.setCasdoorUserId(u.getCasdoorUserId());
        vo.setName(u.getName());
        vo.setNickname(u.getNickname());
        vo.setAvatar(u.getAvatar());
        vo.setEmail(u.getEmail());
        vo.setPhone(u.getPhone());
        vo.setBio(u.getBio());
        vo.setVerified(u.getVerified());
        vo.setIsAdmin(u.getIsAdmin());
        vo.setCreateTime(u.getCreateTime());
        vo.setUpdateTime(u.getUpdateTime());
        return vo;
    }
}