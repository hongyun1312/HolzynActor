/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 项目事件仓库（actor_event，时间线节点 + 世界事件总线落库）。
 * <p>；被 agent/event 世界事件总线消费）</p>
 */
@Repository
public interface ActorEventRepository extends JpaRepository<ActorEvent, Long> {

    /**
     * 按项目查询事件（时间倒序）。
     *
     * @param projectId 项目 ID
     *
     * @return 事件列表
     */
    List<ActorEvent> findByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 按项目查询事件（时间正序，B1 滚动保留清理最旧记录用）。
     *
     * @param projectId 项目 ID
     *
     * @return 事件列表（旧→新）
     */
    List<ActorEvent> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 按项目统计事件数（B1 滚动保留阈值判断用）。
     *
     * @param projectId 项目 ID
     *
     * @return 事件条数
     */
    long countByProjectId(Long projectId);

    /**
     * 统计某角色参与的事件数（MUSE 策略切换·经历卷计算用）。
     * <p>口径=旧列 {@code character_id}（行动者单值）∪ 新列 {@code character_ids}（参与角色
     * ref 列表，逗号分隔，如 {@code npc:1,crowd:12}）任一命中即计入——兼容 B1 前后两代事件
     * 写入形态；{@code refFragment} 传 {@code npc:{id}} 统一前缀（{@code NpcRefs.of} 拼装）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色主表 ID（旧列 character_id 单值匹配）
     *
     * @param refFragment 角色 ref 前缀片段（新列 character_ids LIKE 匹配，形如 npc:1）
     *
     * @return 参与事件条数
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(e) from ActorEvent e
            where e.projectId = :projectId
              and (e.characterId = :characterId or e.characterIds like %:refFragment%)
            """)
    long countByProjectIdAndCharacterRef(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("characterId") Long characterId,
            @org.springframework.data.repository.query.Param("refFragment") String refFragment);

    /**
     * 按项目 + 幂等键查唯一事件（B1 publish 幂等去重用，）。
     *
     * @param projectId 项目 ID
     *
     * @param sourceKey 幂等键
     *
     * @return 已发布事件（无则空）
     */
    Optional<ActorEvent> findTopByProjectIdAndSourceKey(Long projectId, String sourceKey);

    /**
     * 查询某角色参与的事件（图谱查询层·角色视角「参与事件」用，时间倒序）。
     * <p>口径对齐 {@link #countByProjectIdAndCharacterRef}（MUSE 经历卷同源）：
     * 旧列 {@code character_id}（行动者单值）∪ 新列 {@code character_ids}（参与角色 ref
     * 列表，逗号分隔，如 {@code npc:1,crowd:12}）任一命中即计入——兼容 B1 前后两代事件写入形态；
     * {@code refFragment} 传 {@code npc:{id}} 统一前缀（{@code NpcRefs.of} 拼装）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色主表 ID（旧列 character_id 单值匹配）
     *
     * @param refFragment 角色 ref 前缀片段（新列 character_ids LIKE 匹配，形如 npc:1）
     *
     * @return 参与事件列表（新→旧）
     */
    @org.springframework.data.jpa.repository.Query("""
            select e from ActorEvent e
            where e.projectId = :projectId
              and (e.characterId = :characterId or e.characterIds like %:refFragment%)
            order by e.id desc
            """)
    List<ActorEvent> findByProjectIdAndCharacterRef(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("characterId") Long characterId,
            @org.springframework.data.repository.query.Param("refFragment") String refFragment);

    /**
     * 查询某地点发生的事件（图谱查询层·地点视角「近期事件」用，时间倒序）。
     *
     * @param projectId 项目 ID
     *
     * @param locationId 地点 ID（actor_world_location.id）
     *
     * @return 事件列表（新→旧）
     */
    List<ActorEvent> findByProjectIdAndLocationIdOrderByIdDesc(Long projectId, Long locationId);

    /**
     * 查询某项目最近 N 条事件（图谱查询层·全局概览「最近事件」用，时间倒序）。
     *
     * @param projectId 项目 ID
     *
     * @return 最近事件列表（新→旧）
     */
    List<ActorEvent> findTop20ByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 全局最近 N 条事件（调试前端事件流新连接补发用，B1 DebugEventHub 回放）。
     *
     * @return 最近事件列表（倒序）
     */
    List<ActorEvent> findTop50ByOrderByIdDesc();

    /**
     * 关键词检索（观测域全局搜索用，只读）。
     * <p>命中字段：title / content / locationName；大小写不敏感子串匹配；标题命中优先，
     * 其次按 id 降序（新事件优先）；由调用方 Pageable 限制条数（事件表量大，必须带 limit）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（已 trim）
     *
     * @param pageable  分页/条数限制
     *
     * @return 命中的事件（标题命中优先）
     */
    @Query("select e from ActorEvent e where e.projectId = :projectId and ("
            + "lower(coalesce(e.title, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(e.content, '')) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(e.locationName, '')) like lower(concat('%', :q, '%')))"
            + " order by case when lower(coalesce(e.title, '')) like lower(concat('%', :q, '%')) then 0 else 1 end,"
            + " e.id desc")
    List<ActorEvent> searchByKeyword(@Param("projectId") Long projectId,
                                     @Param("q") String q,
                                     Pageable pageable);
}
