package com.holzyn.actor.domain.character.repository;

import com.holzyn.actor.domain.character.entity.ActorCharacterCard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * 角色卡仓库（actor_character_card）。
 * <p>职责：提供按角色读取最新版本角色卡与版本历史的能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorCharacterCardRepository extends JpaRepository<ActorCharacterCard, Long> {

    /**
     * 查询某角色最新版本的角色卡。
     *
     * @param characterId 角色 ID
     * @return 版本号最大的角色卡（可能为空）
     */
    Optional<ActorCharacterCard> findTopByCharacterIdOrderByVersionDesc(Long characterId);

    /**
     * 查询某角色全部版本（升序，用于版本历史展示）。
     *
     * @param characterId 角色 ID
     * @return 角色卡版本列表
     */
    List<ActorCharacterCard> findByCharacterIdOrderByVersionAsc(Long characterId);
}