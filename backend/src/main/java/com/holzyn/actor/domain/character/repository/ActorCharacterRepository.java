package com.holzyn.actor.domain.character.repository;

import com.holzyn.actor.domain.character.entity.ActorCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 角色仓库（actor_character）。
 * <p>职责：提供按项目查询未删除角色列表的能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorCharacterRepository extends JpaRepository<ActorCharacter, Long> {

    /**
     * 查询某项目未删除的角色列表（按创建顺序）。
     *
     * @param projectId 项目 ID
     * @param deleted   软删除标记（0 正常）
     * @return 角色列表
     */
    List<ActorCharacter> findByProjectIdAndDeletedOrderByIdAsc(Long projectId, Integer deleted);
}