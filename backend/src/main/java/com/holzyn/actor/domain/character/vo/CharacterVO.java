package com.holzyn.actor.domain.character.vo;

import com.holzyn.actor.domain.character.entity.ActorCharacter;
import java.time.LocalDateTime;

/**
 * 角色视图对象（CharacterVO）。
 * <p>职责：向前端返回角色基础档案（含是否已有角色卡标记）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record CharacterVO(
        Long id,
        Long projectId,
        String type,
        String name,
        String title,
        String detail,
        String avatarUrl,
        Integer isProtagonist,
        Integer importance,
        Integer status,
        Boolean hasCard,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param c       角色实体
     * @param hasCard 是否已有角色卡
     * @return VO 对象
     */
    public static CharacterVO of(ActorCharacter c, Boolean hasCard) {
        return new CharacterVO(c.getId(), c.getProjectId(), c.getType(), c.getName(), c.getTitle(), c.getDetail(),
                c.getAvatarUrl(), c.getIsProtagonist(), c.getImportance(), c.getStatus(), hasCard,
                c.getCreatedAt(), c.getUpdatedAt());
    }
}