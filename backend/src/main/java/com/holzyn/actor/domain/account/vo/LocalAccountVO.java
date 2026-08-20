package com.holzyn.actor.domain.account.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 本地个人账户视图对象。
 * <p>职责：作为本地账户接口（GET/PUT /api/local-account）的返回载体，
 * 供前端展示用户信息，并在设置页编辑；onboarded 供首次向导判断。</p>
 * <p>所属模块：domain/account/vo（本地账户功能域-视图层）</p>
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

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
