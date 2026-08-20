package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 世界时钟仓库（actor_world_clock）。
 * <p>职责：提供按项目查询/懒创建世界时钟行的能力（每项目唯一时钟）。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorWorldClockRepository extends JpaRepository<ActorWorldClock, Long> {

    /**
     * 查询某项目的世界时钟（每项目唯一）。
     *
     * @param projectId 项目 ID
     * @return 时钟（可能为空，首次访问时懒创建）
     */
    Optional<ActorWorldClock> findByProjectId(Long projectId);
}
