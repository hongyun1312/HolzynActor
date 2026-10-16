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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * AI 调用日志滚动保留（P1-5 数据膨胀最小治理）。
 * <p>职责：给 {@code actor_ai_call_log} 加<b>滚动保留</b>——把创建时间早于
 * {@code retention-days} 天的历史记录批量删除，阻断「只增不删」的无限膨胀
 * （本表四个 LONGTEXT 列 + detail_json，单条可上万字符，是主库体积增长的最大来源）。</p>
 * <p>双份存储联动：DB 行删除后，同步清理 {@code <data-dir>/ai-call-logs/} 下
 * 修改时间早于同一截止时刻的 {@code ai-call-*.json} 文件副本（该目录与 DB 行同内容，
 * 删 DB 不删文件会留下孤儿副本；DB 为主、文件为副）。</p>
 * <p>配置（application.yml {@code holzyn.actor.debug.ai-log.retention}）：<ul>
 * <li>{@code enabled}（默认 true）：false=整项关闭，不删 DB 行也不删文件；</li>
 * <li>{@code days}（默认 7）：保留天数；<b>0=关闭滚动删除</b>（回退到「无保留策略」旧行为）；</li>
 * <li>{@code maintenance-interval-ms}（默认 6 小时，对齐记忆归档/知识生命周期维护周期）。</li>
 * </ul></p>
 * <p>调度：{@code @Scheduled(fixedDelay)} 首次执行在应用启动后立即触发一次——升级到本策略后，
 * 首次启动即把既有历史超期数据收进保留口径，无需人工介入。单次维护失败仅告警不阻断业务。</p>
 */
@Slf4j
@Service
public class AiCallLogRetentionService {

    /** trace 仓库（滚动删除 DB 行） */
    private final ActorAiCallLogRepository repository;

    /** 滚动保留总开关（@Value 字段注入，默认 true） */
    @Value("${holzyn.actor.debug.ai-log.retention.enabled:true}")
    private boolean enabled = true;

    /** 保留天数（0=关闭滚动删除；默认 7 天） */
    @Value("${holzyn.actor.debug.ai-log.retention.days:7}")
    private int retentionDays = 7;

    /** 定时维护间隔（毫秒，默认 6 小时，对齐记忆归档维护周期） */
    @Value("${holzyn.actor.debug.ai-log.retention.maintenance-interval-ms:21600000}")
    private long maintenanceIntervalMs = 21_600_000L;

    /** 本地数据目录（与 {@code AiCallTraceService} 同源；文件副本根 = &lt;data-dir&gt;/ai-call-logs） */
    @Value("${holzyn.actor.data-dir:./data}")
    private String dataDir = null;

    /**
     * 构造（Spring 使用：仓库注入，开关/保留期/间隔/数据目录由 @Value 字段注入）。
     *
     * @param repository trace 仓库
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AiCallLogRetentionService(ActorAiCallLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 测试专用构造：显式指定开关/保留天数/数据目录（不触发 Spring 配置注入）。
     *
     * @param repository     trace 仓库（测试可传 mock）
     * @param enabled        滚动保留总开关
     * @param retentionDays  保留天数（0=关闭滚动删除）
     * @param dataDir        文件副本根目录（null=不清理文件）
     */
    AiCallLogRetentionService(ActorAiCallLogRepository repository, boolean enabled, int retentionDays, String dataDir) {
        this.repository = repository;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.dataDir = dataDir;
    }

    /**
     * 定时维护（独立调度，不动 EngineScheduler）：滚动删除超期 DB 行 + 联动清理文件副本。
     * <p>{@code fixedDelay} 首次执行在应用启动后立即触发，之后每间隔维护一次；
     * 开关关闭 / 保留天数 0 时静默跳过。</p>
     * <p><b>事务必须标在本方法上（2026-10-14 修复）</b>：{@link #retain()} 上的
     * {@code @Transactional} 对本方法内部的 {@code this.retain()} 调用<b>不生效</b>——Spring AOP
     * 只拦截经代理的外部调用，类内自调用走原始对象、绕过事务拦截器；而
     * {@code deleteByCreatedAtBefore} 是 {@code @Modifying} 批量删除，无事务即抛
     * {@code No active transaction for update or delete query}，被下方 catch 吞成一条 WARN，
     * 外部表现为「滚动保留静默失效、{@code actor_ai_call_log} 只增不删」。
     * 故事务边界上移到调度入口（{@code @Scheduled} + {@code @Transactional} 同方法）。</p>
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.debug.ai-log.retention.maintenance-interval-ms:21600000}")
    @Transactional
    public void scheduledMaintenance() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        try {
            retain();
        } catch (Exception e) {
            // 维护失败不影响业务，仅告警（与记忆归档/知识生命周期维护同一隔离口径）
            log.warn("[AI-Log保留] 定时滚动清理失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 执行一次滚动清理：删除创建时间早于 {@code now - retentionDays} 的 DB 行，
     * 再删除 {@code <data-dir>/ai-call-logs/} 下修改时间早于同一截止时刻的文件副本。
     *
     * @return 删除的 DB 行数（文件副本删除数记日志）
     */
    @Transactional
    public int retain() {
        if (!enabled || retentionDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int rows = repository.deleteByCreatedAtBefore(cutoff);
        int files = deleteExpiredFiles(cutoff);
        if (rows > 0 || files > 0) {
            log.info("[AI-Log保留] 滚动清理完成：删除 DB 行 {} 条、文件副本 {} 个（截止 {}，保留 {} 天）",
                    rows, files, cutoff, retentionDays);
        }
        return rows;
    }

    /**
     * 删除 {@code <data-dir>/ai-call-logs/} 下修改时间早于截止时刻的 {@code ai-call-*.json} 文件副本。
     * <p>仅处理本服务命名前缀（{@code ai-call-}）的文件，不误删目录内其他内容；
     * 单文件删除失败仅告警跳过，不中断后续文件。</p>
     *
     * @param cutoff 截止时刻（文件最后修改时间早于它则删除）
     *
     * @return 删除文件数
     */
    private int deleteExpiredFiles(LocalDateTime cutoff) {
        if (dataDir == null || dataDir.isBlank()) {
            return 0;
        }
        Path dir = Path.of(dataDir).resolve("ai-call-logs");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long epochMillis = cutoff.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int deleted = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().startsWith("ai-call-")) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < epochMillis) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("[AI-Log保留] 删除文件副本失败（跳过）: {} : {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[AI-Log保留] 扫描 ai-call-logs 目录失败: {}", e.getMessage());
        }
        return deleted;
    }
}
