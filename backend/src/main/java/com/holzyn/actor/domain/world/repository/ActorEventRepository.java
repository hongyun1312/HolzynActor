package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 项目事件仓库（actor_event，时间线节点）。
 * <p>所属模块：repository（数据访问层-时间线/事件子域）</p>
 */
@Repository
public interface ActorEventRepository extends JpaRepository<ActorEvent, Long> {

    /**
     * 按项目查询事件（时间倒序）。
     *
     * @param projectId 项目 ID
     * @return 事件列表
     */
    List<ActorEvent> findByProjectIdOrderByIdDesc(Long projectId);
}
