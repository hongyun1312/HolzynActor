package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 世界观设定仓库（actor_world_setting）。
 * <p>职责：提供按项目读取最新版本世界观的能力（版本化）。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorWorldSettingRepository extends JpaRepository<ActorWorldSetting, Long> {

    /**
     * 查询某项目最新版本的世界观设定。
     *
     * @param projectId 项目 ID
     * @return 版本号最大的世界观（可能为空）
     */
    Optional<ActorWorldSetting> findTopByProjectIdOrderByVersionDesc(Long projectId);
}