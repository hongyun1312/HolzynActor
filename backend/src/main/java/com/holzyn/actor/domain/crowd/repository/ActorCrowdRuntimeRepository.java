package com.holzyn.actor.domain.crowd.repository;

import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 普通型 NPC 项目级调度运行时仓库（actor_crowd_runtime）。
 * <p>职责：提供按项目定位运行时行（一项目一行）、查询全部启用定时调度的项目
 * （定时任务推进用）。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorCrowdRuntimeRepository extends JpaRepository<ActorCrowdRuntime, Long> {

    /**
     * 按项目查询运行时行（唯一约束，一项目一行）。
     *
     * @param projectId 项目 ID
     * @return 匹配的运行时行（可能为空）
     */
    Optional<ActorCrowdRuntime> findByProjectId(Long projectId);

    /**
     * 查询全部启用定时调度项目的运行时行（CrowdScheduledJob 使用）。
     *
     * @param enabled 启用标记（1=启用）
     * @return 启用项目的运行时行列表
     */
    List<ActorCrowdRuntime> findByEnabledOrderByIdAsc(Integer enabled);
}
