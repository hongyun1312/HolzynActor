package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 世界观地点表仓库。
 * <p>职责：按项目查询/维护地点（排序稳定：sortOrder 升序 + id 升序兜底）。
 * 项目级归属，随 .holzyn 导入导出。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
public interface WorldLocationRepository extends JpaRepository<ActorWorldLocation, Long> {

    /** 按项目查询地点（sortOrder 升序，同序按 id 升序，保证顺序稳定） */
    List<ActorWorldLocation> findByProjectIdOrderBySortOrderAscIdAsc(Long projectId);

    /** 按项目查询全部地点（供候选/统计，顺序无关） */
    List<ActorWorldLocation> findByProjectId(Long projectId);
}
