package com.holzyn.actor.domain.memory.repository;

import com.holzyn.actor.domain.memory.entity.ActorMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 长期记忆仓库（actor_memory）。
 * <p>职责：提供按项目/角色/类型查询记忆的能力——角色级记忆（character_id 非空）、
 * 项目级记忆（character_id IS NULL）、全部记忆（分页）；软删标记统一按 deleted=0 过滤。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorMemoryRepository extends JpaRepository<ActorMemory, Long> {

    /**
     * 查询某角色的全部未删记忆（按创建时间倒序，注入/淘汰用）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     * @param deleted     软删标记（0）
     * @return 记忆列表（新在前）
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndDeletedOrderByCreatedAtDesc(
            Long projectId, Long characterId, Integer deleted);

    /**
     * 查询某角色的指定类型未删记忆（按创建时间倒序，摘要/事实分场景用）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     * @param kind        类型（fact/summary）
     * @param deleted     软删标记（0）
     * @return 记忆列表（新在前）
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndKindAndDeletedOrderByCreatedAtDesc(
            Long projectId, Long characterId, String kind, Integer deleted);

    /**
     * 查询项目级未删记忆（character_id IS NULL，按创建时间倒序）。
     *
     * @param projectId 项目 ID
     * @param deleted   软删标记（0）
     * @return 项目级记忆列表（新在前）
     */
    List<ActorMemory> findByProjectIdAndCharacterIdIsNullAndDeletedOrderByCreatedAtDesc(
            Long projectId, Integer deleted);

    /**
     * 查询某角色 + 项目级全部未删记忆（注入用，合并一次查询）。
     *
     * @param projectId 项目 ID
     * @param characterId 角色 ID
     * @param deleted   软删标记（0）
     * @return 记忆列表（新在前）
     */
    List<ActorMemory> findByProjectIdAndCharacterIdAndDeletedOrderByIdAsc(
            Long projectId, Long characterId, Integer deleted);

    /**
     * 查询项目下全部未删记忆（分页，管理列表用）。
     *
     * @param projectId 项目 ID
     * @param deleted   软删标记（0）
     * @param pageable  分页参数
     * @return 分页记忆
     */
    Page<ActorMemory> findByProjectIdAndDeleted(Long projectId, Integer deleted, Pageable pageable);

    /**
     * 查询项目下全部未删记忆（ID 倒序，时间线记忆里程碑用）。
     *
     * @param projectId 项目 ID
     * @param deleted   软删标记（0）
     * @return 记忆列表（新在前）
     */
    List<ActorMemory> findByProjectIdAndDeletedOrderByIdDesc(Long projectId, Integer deleted);

    /**
     * 查询某角色未删记忆（分页，角色记忆列表用）。
     *
     * @param projectId   项目 ID
     * @param characterId 角色 ID
     * @param deleted     软删标记（0）
     * @param pageable    分页参数
     * @return 分页记忆
     */
    Page<ActorMemory> findByProjectIdAndCharacterIdAndDeleted(
            Long projectId, Long characterId, Integer deleted, Pageable pageable);
}
