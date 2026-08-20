package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 世界演化会话仓库（actor_evolution）。
 * <p>所属模块：repository（数据访问层-世界演化子域）</p>
 */
@Repository
public interface ActorEvolutionRepository extends JpaRepository<ActorEvolution, Long> {

    /**
     * 按项目查询演化会话（进行中优先、ID 倒序）。
     *
     * @param projectId 项目 ID
     * @return 演化会话列表
     */
    List<ActorEvolution> findByProjectIdOrderByStatusAscIdDesc(Long projectId);

    /**
     * 查询项目下进行中的演化会话。
     *
     * @param projectId 项目 ID
     * @param status    状态（running）
     * @return 进行中的演化会话列表
     */
    Optional<ActorEvolution> findFirstByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);
}
