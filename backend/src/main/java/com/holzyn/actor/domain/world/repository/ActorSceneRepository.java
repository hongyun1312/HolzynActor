package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorScene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 场景仓库（actor_scene）。
 * <p>所属模块：repository（数据访问层-场景子域）</p>
 */
@Repository
public interface ActorSceneRepository extends JpaRepository<ActorScene, Long> {

    /**
     * 按项目查询场景（启用优先、ID 升序）。
     *
     * @param projectId 项目 ID
     * @return 场景列表
     */
    List<ActorScene> findByProjectIdOrderByEnabledDescIdAsc(Long projectId);
}
