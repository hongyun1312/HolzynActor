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

package com.holzyn.actor.domain.debug.repository;

import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.vo.AiCallBrief;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 调用 trace 仓库（actor_ai_call_log，调试可视化前端专用）。
 * <p>职责：全量 AI 调用记录的存取；列表页筛选/分页/排序通过 JpaSpecificationExecutor
 * 动态拼装（projectId/scene/status/keyword/时间范围），详情按 id 直查。</p>
 * <p>滚动保留：删除入口 {@link #deleteByCreatedAtBefore}，由 {@code AiCallLogRetentionService}
 * 定时调用（保留天数见配置 {@code holzyn.actor.debug.ai-log.retention.days}，默认 7 天）。</p>
 */
@Repository
public interface ActorAiCallLogRepository extends JpaRepository<ActorAiCallLog, Long>,
        JpaSpecificationExecutor<ActorAiCallLog> {

    /**
     * 某项目最近一条 AI 调用（总览页「最近调用」）。
     *
     * @param projectId 项目 ID
     *
     * @return 最近一条（可空）
     */
    Optional<ActorAiCallLog> findTop1ByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 某项目最近 10 条 AI 调用（W-2 世界引擎快照 dump 用，B3）。
     *
     * @param projectId 项目 ID
     *
     * @return 最近调用列表（倒序）
     */
    List<ActorAiCallLog> findTop10ByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 全局最近一条 AI 调用（总览页）。
     *
     * @return 最近一条（可空）
     */
    Optional<ActorAiCallLog> findTop1ByOrderByIdDesc();

    /**
     * 自某时刻起的全部 AI 调用（AgentRuntime 概览：决策频率/预算使用/组件状态聚合窗口）。
     *
     * @param from 起始时刻（含）
     *
     * @return 调用列表（按 id 升序，天然按时间序）
     */
    List<ActorAiCallLog> findByCreatedAtGreaterThanEqual(LocalDateTime from);

    /**
     * 自某时刻起某项目的 AI 调用（AgentRuntime 概览：按项目过滤的聚合窗口）。
     *
     * @param projectId 项目 ID
     *
     * @param from 起始时刻（含）
     *
     * @return 调用列表（按 id 升序）
     */
    List<ActorAiCallLog> findByProjectIdAndCreatedAtGreaterThanEqual(Long projectId, LocalDateTime from);

    /**
     * 窗口内某项目的**统计投影**（观测域「AI 运行时状态」专用；按时间倒序，可带上限）。
     *
     * <p>为什么单独开一条投影查询而不是复用 {@link #findByProjectIdAndCreatedAtGreaterThanEqual}：
     * 本表四个 LONGTEXT 列（content / system_prompt / user_prompt / response，单条可达 2 万字），
     * 而观测接口每 8 秒轮询一次、窗口内可能有上万条记录。用构造表达式只取统计字段后，
     * 单次查询搬运的是几十字节/行，而不是数百 MB 文本。</p>
     *
     * @param projectId 项目 ID
     *
     * @param from      起始时刻（含）
     *
     * @param pageable  上限（调用方给 {@code PageRequest.of(0, 上限)}；排序由 JPQL 的 order by 决定）
     *
     * @return 统计投影列表（时间倒序，最新在前）
     */
    @Query("select new com.holzyn.actor.domain.debug.vo.AiCallBrief("
            + "r.id, r.traceId, r.scene, r.model, r.status, r.characterId, "
            + "r.tokenIn, r.tokenOut, r.cacheHit, r.cacheMiss, r.durationMs, r.createdAt, r.errorMessage) "
            + "from ActorAiCallLog r "
            + "where r.projectId = :projectId and r.createdAt >= :from "
            + "order by r.createdAt desc, r.id desc")
    List<AiCallBrief> findBriefInWindow(@Param("projectId") Long projectId,
                                       @Param("from") LocalDateTime from,
                                       Pageable pageable);

    /**
     * 查询某决策链路 trace_id 下的全部环节（决策链路可视化：单链路详情）。
     * <p>一次决策 = 一条 trace_id（感知→思考→行动→学习 的全部 AI 调用 + 非 AI 环节行），
     * 按 id 升序（= 环节发生顺序，attempt/step_type 逐行展开）。</p>
     *
     * @param traceId 决策链路 trace_id
     *
     * @return 环节列表（按 id 升序）
     */
    List<ActorAiCallLog> findByTraceIdOrderByIdAsc(String traceId);

    /**
     * 查询某项目下最近 N 条指定环节类型的记录（反思闭环·自省漂移摘要用：
     * stepType=self_check 的「自省漂移」行，按 id 倒序取最近；content 含
     * ref=npc:{id} 前缀由调用方过滤——recordStep 无 characterId 形参）。
     *
     * @param projectId 项目 ID
     *
     * @param stepType 环节类型（如 self_check）
     *
     * @return 最近记录（按 id 倒序）
     */
    List<ActorAiCallLog> findTop10ByProjectIdAndStepTypeOrderByIdDesc(Long projectId, String stepType);

    /**
     * 滚动保留：删除创建时间早于截止时刻的全部记录（P1-5 数据膨胀治理）。
     * <p>批量删除（单条 DELETE SQL），供 {@code AiCallLogRetentionService} 的定时维护调用；
     * 调用方须处于事务中（服务层 {@code @Transactional}）。</p>
     * <p>与 {@code data/ai-call-logs/} 文件副本联动：本方法只删 DB 行，对应的
     * {@code ai-call-*.json} 文件由 {@code AiCallLogRetentionService} 按文件修改时间同步清理，
     * 保证双份存储口径一致（删除顺序：先删 DB 行，再删同名文件，DB 为主、文件为副）。</p>
     *
     * @param cutoff 截止时刻（含）；创建时间早于该时刻的行删除
     *
     * @return 删除行数
     */
    @Modifying
    @Query("delete from ActorAiCallLog r where r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
