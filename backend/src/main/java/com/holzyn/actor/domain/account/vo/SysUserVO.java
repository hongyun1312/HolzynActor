package com.holzyn.actor.domain.account.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统用户视图对象。
 * <p>职责：作为用户中心/会话接口返回的用户信息载体（含昵称、头像、认证状态、管理员标记等）。</p>
 * <p>所属模块：model/vo（视图对象层-用户中心子域）</p>
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
    /** 更新时间 */
    private LocalDateTime updateTime;
}