package com.holzyn.actor.domain.crowd.repository;

import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 普通型 NPC 仓库（actor_ordinary_npc，重构后的单表）。
 * <p>职责：提供按项目查询/计数、归属校验（id+项目）、按名称解析（关系拓扑）、
 * 按分类筛选与按项目删除等能力，支撑 AI 生成/CRUD/调度/关系拓扑。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorOrdinaryNpcRepository extends JpaRepository<ActorOrdinaryNpc, Long> {

    /**
     * 查询某项目全部普通型 NPC（按创建顺序）。
     *
     * @param projectId 项目 ID
     * @return 普通型 NPC 列表
     */
    List<ActorOrdinaryNpc> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 统计某项目的普通型 NPC 总数。
     *
     * @param projectId 项目 ID
     * @return 数量
     */
    long countByProjectId(Long projectId);

    /**
     * 按 id + 项目查询（归属校验）。
     *
     * @param id        主键
     * @param projectId 项目 ID
     * @return 匹配的普通型 NPC（可能为空）
     */
    Optional<ActorOrdinaryNpc> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 按名称解析（关系拓扑/生成去重用，同项目内）。
     *
     * @param projectId 项目 ID
     * @param name      名称
     * @return 匹配的普通型 NPC 列表（可能多个同名）
     */
    List<ActorOrdinaryNpc> findByProjectIdAndName(Long projectId, String name);

    /**
     * 删除某项目全部普通型 NPC（数据清空/导入覆盖用）。
     *
     * @param projectId 项目 ID
     */
    void deleteByProjectId(Long projectId);
}
