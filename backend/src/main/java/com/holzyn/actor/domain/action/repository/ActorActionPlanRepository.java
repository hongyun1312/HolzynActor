package com.holzyn.actor.domain.action.repository;

import com.holzyn.actor.domain.action.entity.ActorActionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 行动决策仓库（actor_action_plan）。
 * <p>职责：提供按角色/项目角色集合/状态/计划时间的查询能力，
 * 支撑行动列表、项目级时间线聚合与 scheduled 定时轮询执行。</p>
 * <p>所属模块：repository（数据访问层-行动子域）</p>
 */
@Repository
public interface ActorActionPlanRepository extends JpaRepository<ActorActionPlan, Long> {

    /**
     * 按角色查询行动决策（新→旧）。
     *
     * @param characterId 角色 ID
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdOrderByIdDesc(Long characterId);

    /**
     * 按角色 + 状态查询行动决策（新→旧）。
     *
     * @param characterId 角色 ID
     * @param status      状态（planned/executing/done/cancelled）
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdAndStatusOrderByIdDesc(Long characterId, String status);

    /**
     * 查询多个角色（项目内全部角色）的行动决策（新→旧），用于项目级时间线。
     *
     * @param characterIds 角色 ID 集合
     * @return 行动决策列表
     */
    List<ActorActionPlan> findByCharacterIdInOrderByIdDesc(Collection<Long> characterIds);

    /**
     * scheduled 定时轮询：查询状态为 planned 且计划时间已到期（<=now）的决策。
     *
     * @param status      状态（planned）
     * @param plannedTime 当前时间
     * @return 到期待执行的决策列表
     */
    List<ActorActionPlan> findByStatusAndPlannedTimeLessThanEqual(String status, LocalDateTime plannedTime);
}