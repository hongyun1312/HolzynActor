package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 世界演化轮次消息仓库（actor_evolution_turn）。
 * <p>所属模块：repository（数据访问层-世界演化子域）</p>
 */
@Repository
public interface ActorEvolutionTurnRepository extends JpaRepository<ActorEvolutionTurn, Long> {

    /**
     * 按演化会话查询全部轮次消息（时间正序）。
     *
     * @param evolutionId 演化会话 ID
     * @return 轮次消息列表
     */
    List<ActorEvolutionTurn> findByEvolutionIdOrderByIdAsc(Long evolutionId);

    /**
     * 删除某演化会话的全部轮次消息（演化会话删除时级联清理）。
     *
     * @param evolutionId 演化会话 ID
     */
    void deleteByEvolutionId(Long evolutionId);
}
