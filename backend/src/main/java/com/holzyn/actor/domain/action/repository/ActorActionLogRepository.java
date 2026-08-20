package com.holzyn.actor.domain.action.repository;

import com.holzyn.actor.domain.action.entity.ActorActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 行动执行日志仓库（actor_action_log）。
 * <p>职责：提供按角色/项目角色集合的日志查询，作为行动时间线数据源之一。</p>
 * <p>所属模块：repository（数据访问层-行动子域）</p>
 */
@Repository
public interface ActorActionLogRepository extends JpaRepository<ActorActionLog, Long> {

    /**
     * 按角色查询行动日志（时间倒序）。
     *
     * @param characterId 角色 ID
     * @return 行动日志列表
     */
    List<ActorActionLog> findByCharacterIdOrderByLogTimeDesc(Long characterId);

    /**
     * 查询多个角色（项目内全部角色）的行动日志（时间倒序），用于项目级时间线。
     *
     * @param characterIds 角色 ID 集合
     * @return 行动日志列表
     */
    List<ActorActionLog> findByCharacterIdInOrderByLogTimeDesc(Collection<Long> characterIds);

    /**
     * 按角色 + 决策 ID 查询行动日志（决策详情面板的关联日志）。
     *
     * @param characterId 角色 ID
     * @param planId      决策 ID
     * @return 行动日志列表
     */
    List<ActorActionLog> findByCharacterIdAndPlanIdOrderByLogTimeDesc(Long characterId, Long planId);
}