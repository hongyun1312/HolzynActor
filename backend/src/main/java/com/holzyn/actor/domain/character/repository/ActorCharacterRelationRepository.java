package com.holzyn.actor.domain.character.repository;

import com.holzyn.actor.domain.character.entity.ActorCharacterRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 角色社会关系仓库（actor_character_relation）。
 * <p>职责：提供按项目查询社会关系列表的能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorCharacterRelationRepository extends JpaRepository<ActorCharacterRelation, Long> {

    /**
     * 查询某项目全部社会关系。
     *
     * @param projectId 项目 ID
     * @return 关系列表
     */
    List<ActorCharacterRelation> findByProjectId(Long projectId);
}