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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionRetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 决策重试作业服务（P0-02 根治 · 可重试性）。
 * <p>把「决策」从「一次调用 + 兜底/缺失」变成<b>可重试的作业</b>（审计 P0-02 根治）：</p>
 * <ul>
 * <li><b>入队</b>（{@link #enqueue}）：AI 决策抛异常 → 按 {@code (project, character, gameSecond)}
 * 去重落一行 PENDING 作业，退避到期前该决策点<b>不落缺失</b>（时间可以等作业）；</li>
 * <li><b>到点扫描</b>（{@link #dueJobs}）：引擎扫描（世界运行中）拉取到期作业，经
 * {@link #claim} 原子认领后由 {@code WorldEngine} 重跑决策并<b>回填到原定 gameSecond</b>；</li>
 * <li><b>指数退避</b>（{@link #markFailed}）：失败递增尝试次数、按
 * {@code initial-backoff × factor^(attempt-1)}（封顶 max-backoff）重排；<b>max 档不限次数</b>
 * （档位判定由调用方传入，对齐「最高性能档不限制 AI 成本」），其余档位达到上限 → ABANDONED；</li>
 * <li><b>显式收口</b>：成功 → SUCCEEDED（决策点最终以 AI/AI_RETRY 落台账）；放弃 → ABANDONED
 * （随后由调用方落显式决策缺失记录，比编造更接近真实）。</li>
 * </ul>
 * <p><b>事务纪律</b>：各方法为轻量短事务（无 AI/网络调用），从非事务的引擎编排中安全调用
 * （对齐「AI 调用在事务外、写阶段毫秒级短事务提交」的项目纪律）。</p>
 */
@Slf4j
@Service
public class DecisionRetryService {

    /** 作业状态：待重试（未到退避到期/等待扫描认领） */
    public static final String STATUS_PENDING = "PENDING";

    /** 作业状态：认领中（原子认领 PENDING→RETRYING，防并发双跑） */
    public static final String STATUS_RETRYING = "RETRYING";

    /** 作业状态：重试成功（决策点已以 AI/AI_RETRY 回填） */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 作业状态：重试耗尽放弃（决策点随后落显式缺失记录） */
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** 失败原因列宽上限 */
    private static final int ERROR_MAX = 500;

    /** 作业仓库 */
    private final ActorDecisionRetryRepository repository;

    /** 重试总开关（false=决策失败直接落缺失，退回 P0-02 止血 (a) 行为） */
    @Value("${holzyn.actor.world.decision.retry-enabled:true}")
    private boolean retryEnabled = true;

    /** 单作业重试次数上限（非 max 档；0/-1=不限，配合档位语义由调用方传 unlimited 判定） */
    @Value("${holzyn.actor.world.decision.retry-max-attempts:8}")
    private int maxAttempts = 8;

    /** 首次重试退避（毫秒，墙钟） */
    @Value("${holzyn.actor.world.decision.retry-initial-backoff-ms:15000}")
    private long initialBackoffMs = 15_000L;

    /** 退避增长因子（每次 × factor） */
    @Value("${holzyn.actor.world.decision.retry-backoff-factor:2.0}")
    private double backoffFactor = 2.0;

    /** 退避封顶（毫秒） */
    @Value("${holzyn.actor.world.decision.retry-max-backoff-ms:300000}")
    private long maxBackoffMs = 300_000L;

    /** 每项目 PENDING 作业容量上限（有界队列语义：溢出即落缺失，不无限堆积） */
    @Value("${holzyn.actor.world.decision.retry-max-pending:500}")
    private int maxPending = 500;

    /** 崩溃残留 RETRYING 作业的过期回收窗口（毫秒；认领后超过该时长未收口视为认领方已死） */
    @Value("${holzyn.actor.world.decision.retry-stale-ms:600000}")
    private long staleMs = 600_000L;

    public DecisionRetryService(ActorDecisionRetryRepository repository) {
        this.repository = repository;
    }

    /**
     * 重试总开关（WorldEngine 快速判定用；false=跳过一切重试逻辑）。
     *
     * @return true=启用
     */
    public boolean isEnabled() {
        return retryEnabled;
    }

    /**
     * 决策失败入队（P0-02 根治入口）。
     * <p>策略检查（开关/容量）→ 去重（已有 PENDING 行则仅刷新原因，不新建）→ 落 PENDING
     * 作业，退避到期前该决策点不落缺失。任一失败返回 false（调用方按决策缺失留痕继续）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param userId 归属用户 ID（重跑时透传，可空）
     *
     * @param gameSecond 原定决策时刻（游戏秒）
     *
     * @param reason 失败原因（异常消息，可空）
     *
     * @return true=已入队（本时段暂无决策，等待重试补算）；false=未入队（调用方落缺失）
     */
    @Transactional
    public boolean enqueue(Long projectId, Long characterId, String characterRef, Long userId,
                           long gameSecond, String reason) {
        if (!retryEnabled || projectId == null || characterId == null) {
            return false;
        }
        try {
            if (maxPending > 0 && repository.countByProjectIdAndStatus(projectId, STATUS_PENDING) >= maxPending) {
                log.warn("[决策重试] PENDING 作业已达容量上限（{}），按决策缺失继续: project={} char={} 时刻={}",
                        maxPending, projectId, characterId, gameSecond);
                return false;
            }
            Optional<ActorDecisionRetry> existing =
                    repository.findByProjectIdAndCharacterIdAndGameSecond(projectId, characterId, gameSecond);
            if (existing.isPresent()) {
                ActorDecisionRetry row = existing.get();
                if (STATUS_PENDING.equals(row.getStatus())) {
                    // 同决策点重复失败（理论少见）：仅刷新失败原因，不重置退避、不重复计数
                    row.setLastError(truncate(reason, ERROR_MAX));
                    repository.save(row);
                    return true;
                }
                // 该决策点已成功/已放弃：不再重入（去重键保护「每决策点恰好一次补算」）
                return false;
            }
            ActorDecisionRetry row = new ActorDecisionRetry();
            row.setProjectId(projectId);
            row.setCharacterId(characterId);
            row.setCharacterRef(characterRef);
            row.setUserId(userId);
            row.setGameSecond(gameSecond);
            row.setGameDay(Math.floorDiv(gameSecond, 86400L));
            row.setAttemptCount(0);
            row.setStatus(STATUS_PENDING);
            row.setNextRetryAt(LocalDateTime.now().plusNanos(backoffNanos(1)));
            row.setLastError(truncate(reason, ERROR_MAX));
            repository.save(row);
            log.info("[决策重试] 决策失败已入队（退避 {}ms 后重试）: project={} char={} 时刻={} 原因={}",
                    initialBackoffMs, projectId, characterId, gameSecond, truncate(reason, 120));
            return true;
        } catch (Exception e) {
            log.warn("[决策重试] 入队失败（按决策缺失继续）: project={} char={} : {}", projectId, characterId, e.getMessage());
            return false;
        }
    }

    /**
     * 到点待重试作业（PENDING 且 next_retry_at ≤ 当前墙钟）。
     *
     * @param projectId 项目 ID
     *
     * @param now 当前墙钟时刻
     *
     * @param limit 单轮拉取上限（>0 生效；超出留到下一轮扫描，防单轮重活堆积）
     *
     * @return 到点作业（按退避到期升序）
     */
    public List<ActorDecisionRetry> dueJobs(Long projectId, LocalDateTime now, int limit) {
        if (!retryEnabled || projectId == null) {
            return List.of();
        }
        List<ActorDecisionRetry> jobs =
                repository.findByProjectIdAndStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        projectId, STATUS_PENDING, now);
        if (limit > 0 && jobs.size() > limit) {
            return new ArrayList<>(jobs.subList(0, limit));
        }
        return jobs;
    }

    /**
     * 回收崩溃残留的 RETRYING 作业（回 PENDING 并立即可重试）。
     * <p>引擎扫描每次排空前一并调用；认领方进程崩溃后作业停留在 RETRYING 超过
     * {@code retry-stale-ms} 即回收，杜绝单机重启后作业永久卡死。</p>
     *
     * @param projectId 项目 ID
     *
     * @param now 当前墙钟时刻
     *
     * @return 回收行数
     */
    @Transactional
    public int reclaimStale(Long projectId, LocalDateTime now) {
        if (!retryEnabled || projectId == null || staleMs <= 0) {
            return 0;
        }
        try {
            return repository.reclaimStale(projectId, now, now.minusNanos(staleMs * 1_000_000L));
        } catch (Exception e) {
            log.warn("[决策重试] 残留作业回收失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            return 0;
        }
    }

    /**
     * 原子认领一个到期作业（PENDING→RETRYING）。
     * <p>并发防双跑：引擎扫描与手动推进并发时只有一个线程认领成功，其余跳过——
     * 同一决策点绝不被重跑两次（双重行动执行 = 世界污染）。</p>
     *
     * @param job 作业（须有 id）
     *
     * @param now 认领时刻
     *
     * @return true=认领成功（调用方负责执行与收口）；false=已被认领/已终态
     */
    @Transactional
    public boolean claim(ActorDecisionRetry job, LocalDateTime now) {
        if (!retryEnabled || job == null || job.getId() == null) {
            return false;
        }
        try {
            return repository.claim(job.getId(), now) == 1;
        } catch (Exception e) {
            log.warn("[决策重试] 认领失败（按未认领跳过）: job={} : {}", job.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 重试成功收口：作业 → SUCCEEDED。
     * <p>决策点已由重跑以 AI/AI_RETRY 来源回填到原定 gameSecond（台账写入发生在
     * {@code AgentRuntime} 决策循环内），本方法只负责作业终态。</p>
     *
     * @param job 作业
     */
    @Transactional
    public void markSucceeded(ActorDecisionRetry job) {
        if (!retryEnabled || job == null || job.getId() == null) {
            return;
        }
        job.setStatus(STATUS_SUCCEEDED);
        job.setNextRetryAt(null);
        job.setLastError(null);
        repository.save(job);
    }

    /**
     * 重试失败收口：递增尝试次数 → 指数退避重排（继续 PENDING）或放弃（ABANDONED）。
     *
     * @param job 作业
     *
     * @param error 失败原因
     *
     * @param now 当前墙钟时刻
     *
     * @param unlimited true=不限重试次数（max 档：不限成本，世界最真实优先）；
     *                  false=按 {@code retry-max-attempts} 上限，超过即放弃
     *
     * @return true=继续重试（已按退避重排）；false=已放弃（调用方随后落显式缺失记录）
     */
    @Transactional
    public boolean markFailed(ActorDecisionRetry job, String error, LocalDateTime now, boolean unlimited) {
        if (!retryEnabled || job == null || job.getId() == null) {
            return false;
        }
        int attempt = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
        job.setAttemptCount(attempt);
        job.setLastError(truncate(error, ERROR_MAX));
        boolean giveUp = !unlimited && maxAttempts >= 0 && attempt > maxAttempts;
        if (giveUp) {
            job.setStatus(STATUS_ABANDONED);
            job.setNextRetryAt(null);
            log.warn("[决策重试] 重试耗尽（{} 次），作业放弃——决策点将落显式缺失: job={} char={} 时刻={}",
                    attempt, job.getId(), job.getCharacterId(), job.getGameSecond());
        } else {
            job.setStatus(STATUS_PENDING);
            job.setNextRetryAt(now.plusNanos(backoffNanos(attempt)));
        }
        repository.save(job);
        return !giveUp;
    }

    /**
     * 主动放弃收口（角色已删除/已故/异步派发失败等无需重试的情形）。
     *
     * @param job 作业
     *
     * @param reason 放弃原因
     */
    @Transactional
    public void markAbandoned(ActorDecisionRetry job, String reason) {
        if (!retryEnabled || job == null || job.getId() == null) {
            return;
        }
        job.setStatus(STATUS_ABANDONED);
        job.setNextRetryAt(null);
        job.setLastError(truncate(reason, ERROR_MAX));
        repository.save(job);
    }

    /**
     * 重试队列统计（可观测端点 {@code GET /decision-retries}）。
     *
     * @param projectId 项目 ID
     *
     * @return pending/succeeded/abandoned/retrying 数量 + 配置（enabled/maxAttempts/maxPending）
     */
    public Map<String, Object> stats(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("enabled", retryEnabled);
        out.put("pending", repository.countByProjectIdAndStatus(projectId, STATUS_PENDING));
        out.put("retrying", repository.countByProjectIdAndStatus(projectId, STATUS_RETRYING));
        out.put("succeeded", repository.countByProjectIdAndStatus(projectId, STATUS_SUCCEEDED));
        out.put("abandoned", repository.countByProjectIdAndStatus(projectId, STATUS_ABANDONED));
        out.put("maxAttempts", maxAttempts);
        out.put("maxPending", maxPending);
        out.put("initialBackoffMs", initialBackoffMs);
        out.put("maxBackoffMs", maxBackoffMs);
        out.put("unit", "decision-retry-job");
        return out;
    }

    /**
     * 第 attempt 次重试的退避毫秒（首次=initialBackoffMs；每次 × factor；封顶 maxBackoffMs；
     * factor≤1 视为固定间隔）。
     *
     * @param attempt 尝试次数（≥1）
     *
     * @return 退避毫秒
     */
    long backoffMs(int attempt) {
        if (attempt <= 1 || backoffFactor <= 1.0) {
            return Math.max(0L, Math.min(maxBackoffMs, initialBackoffMs));
        }
        double ms = initialBackoffMs * Math.pow(backoffFactor, Math.max(0, attempt - 1));
        return Math.max(0L, Math.min(maxBackoffMs, (long) ms));
    }

    /** 退避毫秒 → 纳秒（LocalDateTime.plusNanos 用）。 */
    private long backoffNanos(int attempt) {
        return backoffMs(attempt) * 1_000_000L;
    }

    /** 字符串截断（列宽保护）。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
