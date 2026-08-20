package com.holzyn.actor.domain.world.vo;

import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import java.time.LocalDateTime;

/**
 * 世界观设定视图对象（WorldSettingVO）。
 * <p>职责：向前端返回世界观设定的全部字段（含版本号）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record WorldSettingVO(
        Long id,
        Long projectId,
        Integer version,
        String name,
        String genre,
        String era,
        String geography,
        String factions,
        String magicSystem,
        String culture,
        String history,
        String freeText,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param w 世界观实体
     * @return VO 对象
     */
    public static WorldSettingVO of(ActorWorldSetting w) {
        return new WorldSettingVO(w.getId(), w.getProjectId(), w.getVersion(), w.getName(), w.getGenre(),
                w.getEra(), w.getGeography(), w.getFactions(), w.getMagicSystem(), w.getCulture(),
                w.getHistory(), w.getFreeText(), w.getStatus(), w.getCreatedAt(), w.getUpdatedAt());
    }
}