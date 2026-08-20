package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvolutionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 世界演化参与者仓库（actor_evolution_participant）。
 * <p>所属模块：repository（数据访问层-世界演化子域）</p>
 */
@Repository
public interface ActorEvolutionParticipantRepository extends JpaRepository<ActorEvolutionParticipant, Long> {

    /**
     * 按演化会话查询参与者（加入顺序）。
     *
     * @param evolutionId 演化会话 ID
     * @return 参与者列表
     */
    List<ActorEvolutionParticipant> findByEvolutionIdOrderByIdAsc(Long evolutionId);

    /**
     * 查询指定演化会话 + 角色的参与者。
     *
     * @param evolutionId 演化会话 ID
     * @param characterId 角色 ID
     * @return 参与者（可能为空）
     */
    Optional<ActorEvolutionParticipant> findByEvolutionIdAndCharacterId(Long evolutionId, Long characterId);

    /**
     * 删除某演化会话的全部参与者（演化会话删除时级联清理）。
     *
     * @param evolutionId 演化会话 ID
     */
    void deleteByEvolutionId(Long evolutionId);
}
