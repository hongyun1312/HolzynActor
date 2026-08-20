package com.holzyn.actor.domain.project.vo;

import com.holzyn.actor.domain.project.entity.ActorProject;
import java.time.LocalDateTime;

/**
 * 项目视图对象（ProjectVO）。
 * <p>职责：向前端返回项目数据，补充角色数等聚合信息。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param id             项目主键
 * @param projectUid     项目 UUID（跨电脑唯一，.holzyn 导入幂等检测依据）
 * @param userId         归属用户 ID
 * @param name           项目名称
 * @param code           项目编码
 * @param coverUrl       封面图 URL
 * @param summary        项目概要
 * @param status         状态（0草稿/1已生成角色卡/2进行中）
 * @param characterCount 角色数量
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record ProjectVO(
        Long id,
        String projectUid,
        Long userId,
        String name,
        String code,
        String coverUrl,
        String summary,
        Integer status,
        Long characterCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param p              项目实体
     * @param characterCount 角色数量
     * @return VO 对象
     */
    public static ProjectVO of(ActorProject p, Long characterCount) {
        return new ProjectVO(p.getId(), p.getProjectUid(), p.getUserId(), p.getName(), p.getCode(), p.getCoverUrl(),
                p.getSummary(), p.getStatus(), characterCount, p.getCreatedAt(), p.getUpdatedAt());
    }
}