package com.holzyn.actor.domain.character.vo;

import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import java.time.LocalDateTime;

/**
 * 角色卡视图对象（CharacterCardVO）。
 * <p>职责：向前端返回角色卡的结构化 JSON 与渲染 Prompt（含版本号/来源）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record CharacterCardVO(
        Long id,
        Long characterId,
        Integer version,
        String personaJson,
        String systemPrompt,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param card 角色卡实体
     * @return VO 对象
     */
    public static CharacterCardVO of(ActorCharacterCard card) {
        return new CharacterCardVO(card.getId(), card.getCharacterId(), card.getVersion(),
                card.getPersonaJson(), card.getSystemPrompt(), card.getSource(),
                card.getCreatedAt(), card.getUpdatedAt());
    }
}