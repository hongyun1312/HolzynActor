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

import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 世界时钟仓库（actor_world_clock）。
 * <p>职责：提供按项目查询/懒创建世界时钟行的能力（每项目唯一时钟）。</p>
 */
public interface ActorWorldClockRepository extends JpaRepository<ActorWorldClock, Long> {

    /**
     * 查询某项目的世界时钟（每项目唯一）。
     *
     * @param projectId 项目 ID
     *
     * @return 时钟（可能为空，首次访问时懒创建）
     */
    Optional<ActorWorldClock> findByProjectId(Long projectId);

    /**
     * 只读窄列：当前引擎推进位置（游戏秒）。
     * <p>用途： 长跑<b>测试专用</b>用量明细（{@code actor_test_usage_log}）给每次 AI 调用
     * 打「游戏秒」时间戳，供按游戏日聚合 token 消耗（见 {@code TestUsageLogService}）。</p>
     * <p>口径说明：这是<b>只读标量</b>查询——不加载实体、不进持久化上下文，因此与上面
     * 的「窄列写入」纪律互不干扰（既不写任何列，也不会被 {@code clearAutomatically} 影响）。
     * 调用方在 AI 调用之后的异步写回线程里执行，不在 AI 热路径上；H2 单行标量读为微秒级，
     * 与引擎的 {@code updateEnginePosition} 写不冲突（MVStore 读不阻塞写）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 引擎游戏秒；无该行时返回 null
     */
    @Query("select c.lastGameSecond from ActorWorldClock c where c.projectId = :projectId")
    Long findGameSecondByProjectId(@Param("projectId") Long projectId);

    //
    // 窄列更新
    // ----------------------------------------------------------------------------------
    // 缺陷现场：引擎每 tick 走 `findByProjectId` 载入完整实体 → simulate（秒级~分钟级）→
    // `save(clock)` 全字段回写；而配置侧（WorldClockController PUT）同样整实体回写。两者持有
    // 各自的事务前快照，**后写者的旧值会覆盖前写者的新值**——实测两次：暂停态把 rate 设成 8，
    // 重开世界后被打回 6；设成 6 又被打回 8；日历投影随错误 rate 跑偏（偏移 −196s → +1207s）。
    // 根治口径：**两侧只写各自的列集合，永不整实体覆盖**——
    // 引擎侧 = last_game_second / last_game_hour / last_engine_run_at / last_sim_time / last_summary；
    // 配置侧 = rate / paused / paused_at / world_running / world_start_at / world_start_game_hour
    // （+ 武装用的 last_engine_run_at）。
    // 两集合只共享 last_engine_run_at 一列，且都写「当前真实时刻」，语义一致、无覆盖风险。
    //
    // ⚠️ 冒烟回归（必读）：这些 @Modifying 方法**必须自带 @Transactional**——
    // 引擎侧 WorldEngine.advanceProject 是**无事务编排**（架构不变量：AI 调用不得持事务），
    // 而 Spring Data 的 @Modifying 查询方法不会自开事务；缺注解时 Hibernate 直接抛
    // 「No EntityManager with actual transaction available for current thread - cannot reliably
    // process 'flush' call」→ tick 收尾提交失败、last_game_second 永不前进、每轮扫描重做整轮 AI
    // 。
    // 旧实现 clockRepository.save(clock) 因 SimpleJpaRepository 自带事务而没暴露这一点。
    //

    /**
     * 引擎推进位置窄更新（只写引擎列，绝不触碰 rate/paused/锚点等配置列）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 推进后的游戏秒（权威位置）
     *
     * @param gameHour 兼容派生游戏小时（gameSecond / 3600）
     *
     * @param baseAt 回补基准（last_engine_run_at / last_sim_time 同写当前真实时刻）
     *
     * @param summary 本 tick 摘要
     *
     * @return 更新行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActorWorldClock c set c.lastGameSecond = :gameSecond, c.lastGameHour = :gameHour,"
            + " c.lastEngineRunAt = :baseAt, c.lastSimTime = :baseAt, c.lastSummary = :summary"
            + " where c.projectId = :projectId")
    int updateEnginePosition(@Param("projectId") Long projectId,
                             @Param("gameSecond") long gameSecond,
                             @Param("gameHour") long gameHour,
                             @Param("baseAt") LocalDateTime baseAt,
                             @Param("summary") String summary);

    /**
     * 仅刷新引擎回补基准（「无流逝」「不足一个 tick」两条不推进路径用）。
     * <p>只写 {@code last_engine_run_at}，不会覆盖同期落地的 rate/暂停等配置。</p>
     *
     * @param projectId 项目 ID
     *
     * @param baseAt 新基准（当前真实时刻）
     *
     * @return 更新行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActorWorldClock c set c.lastEngineRunAt = :baseAt where c.projectId = :projectId")
    int updateEngineBase(@Param("projectId") Long projectId, @Param("baseAt") LocalDateTime baseAt);

    /**
     * 配置窄更新（只写配置列；引擎位置列由 {@link #updateEnginePosition} 独占）。
     * <p>{@code lastEngineRunAt} 传入 null 时保持不变（JPQL 的 {@code case when} 兜底，
     * 避免「只想改速率」把武装基准清空）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param rate 速率（1~8760）
     *
     * @param paused 暂停标志（0/1）
     *
     * @param pausedAt 暂停时刻（可空）
     *
     * @param worldRunning 持续运行开关（0/1）
     *
     * @param startAt 日历锚点（可空=不变）
     *
     * @param startGameHour 锚点游戏小时（可空=不变）
     *
     * @param lastEngineRunAt 武装基准（可空=不变）
     *
     * @return 更新行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActorWorldClock c set c.rate = :rate, c.paused = :paused, c.pausedAt = :pausedAt,"
            + " c.worldRunning = :worldRunning,"
            + " c.worldStartAt = case when :startAt is null then c.worldStartAt else :startAt end,"
            + " c.worldStartGameHour = case when :startGameHour is null then c.worldStartGameHour"
            + "   else :startGameHour end,"
            + " c.lastEngineRunAt = case when :lastEngineRunAt is null then c.lastEngineRunAt"
            + "   else :lastEngineRunAt end"
            + " where c.projectId = :projectId")
    int updateConfig(@Param("projectId") Long projectId,
                     @Param("rate") Integer rate,
                     @Param("paused") Integer paused,
                     @Param("pausedAt") LocalDateTime pausedAt,
                     @Param("worldRunning") Integer worldRunning,
                     @Param("startAt") LocalDateTime startAt,
                     @Param("startGameHour") Long startGameHour,
                     @Param("lastEngineRunAt") LocalDateTime lastEngineRunAt);
}
