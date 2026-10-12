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

package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.usage.entity.TestUsageLog;
import com.holzyn.actor.domain.usage.repository.TestUsageLogRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 长跑测试用量明细写入器（表 {@code actor_test_usage_log}）。
 * <h2>职责</h2>
 * <p>把 {@link AiCallTraceService} 汇聚的每一次 AI 调用，按轮次标签完整落到
 * {@code actor_test_usage_log}：用时 / 请求输入（system + user 全文）/ 模型输出全文 /
 * 输入 token / 输出 token / 缓存 token（命中 + 未命中），以及场景、模型、供应商、
 * 游戏秒等定位字段。数据只服务「这一轮到底花了多少 token、每个游戏日花多少」的计量与
 * 事后逐条复核。</p>
 * <h2>开关与隔离</h2>
 * <ul>
 * 默认 <b>false</b>）：关闭时本服务完全惰性——不建线程、不查库、不落一行；</li>
 * 默认空）：轮次标签（如 {@code R3-20260927}）。空标签会写成 {@code UNLABELED}，
 * 以便报告脚本按标签过滤时不会把「没标签的数据」误算进某一轮。</li>
 * </ul>
 * <h2>为什么这样接（三个不可让步的约束）</h2>
 * <ol>
 * <li><b>不阻断 AI 主流程</b>：写入发生在独立虚拟线程（{@code Executors.newVirtualThreadPerTaskExecutor}），
 * 与 {@code actor_ai_call_log} 的写入路径平行、互不等待；任何异常只 WARN。
 * 多调用/秒的高压下，H2 插入慢也不会把 AI 结果拖住或丢掉。</li>
 * <li><b>不进事务</b>：本类<b>没有</b>{@code @Transactional}（架构不变量：任何含 AI 调用的方法不得持事务；
 * 本类是 AI 调用之后的异步写回，同样保持无事务，让每次插入由
 * {@code SimpleJpaRepository.save} 自带的事务独立提交）。</li>
 * <li><b>全文保真</b>：system/user/response 三列原样落库，<b>复用 trace 的原文</b>而不是
 * {@code actor_ai_call_log} 那条按 {@code max-prompt-chars} 截断后的副本（：
 * 输入输出保真、不截断）。</li>
 * </ol>
 * <h2>游戏秒口径</h2>
 * <p>{@code game_second} = 落库时刻 {@code actor_world_clock.last_game_second} 的只读标量快照
 * 。快照在异步线程里做，不在 AI 热路径上。
 * 这里的读是 H2 单行标量读（微秒级），且与引擎的窄列写不冲突。</p>
 */
@Slf4j
@Service
public class TestUsageLogService {

    /** 测试明细开关（默认关闭：不进长跑时本表零写入） */
    @Value("${holzyn.actor.test-usage.enabled:false}")
    private boolean enabled = false;

    /** 轮次标签（报告脚本按此过滤；空=UNLABELED） */
    @Value("${holzyn.actor.test-usage.run-label:}")
    private String runLabel = "";

    /** 用量明细仓库 */
    private final TestUsageLogRepository repository;

    /** 世界时钟仓库（只读游戏秒；单元测试可不注入） */
    @Autowired(required = false)
    private ActorWorldClockRepository worldClockRepository;

    /** 异步写入线程池（虚拟线程；写入永不阻塞 AI 主流程） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 在途写入计数（供脚本/测试等待落盘完成） */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** 因异常放弃写入的次数（供诊断：非 0 说明计量有丢失） */
    private final AtomicInteger failed = new AtomicInteger();

    /**
     * 构造器：仅依赖仓库（Spring 注入）。
     *
     * @param repository 用量明细仓库
     */
    public TestUsageLogService(TestUsageLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 启动日志：让「本轮到底记没记」在 boot 日志里一眼可见。
     */
    @PostConstruct
    void logState() {
        if (enabled) {
            log.info("[TestUsage] 长跑测试用量明细已开启：run_label={} 表=actor_test_usage_log", effectiveRunLabel());
        } else {
            log.info("[TestUsage] 长跑测试用量明细未开启（holzyn.actor.test-usage.enabled=false）");
        }
    }

    /** 开关状态（供脚本/测试断言） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 在途写入数（供脚本/测试等待落盘） */
    public int getInFlight() {
        return inFlight.get();
    }

    /** 放弃写入计数（非 0 = 有计量丢失，报告需说明） */
    public int getFailedCount() {
        return failed.get();
    }

    /** 生效的轮次标签（空=UNLABELED） */
    public String effectiveRunLabel() {
        return (runLabel == null || runLabel.isBlank()) ? "UNLABELED" : runLabel.trim();
    }

    /**
     * 异步记录一次 AI 调用（由 {@code AiCallTraceService.submitAsync} 调用，非 AI 热路径）。
     *
     * @param trace 本次调用 trace（未调用过 Provider 的 trace 不应传进来）
     */
    public void recordAsync(AiCallTrace trace) {
        if (!enabled || trace == null) {
            return;
        }
        inFlight.incrementAndGet();
        try {
            executor.submit(() -> {
                try {
                    repository.save(toEntity(trace));
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("[TestUsage] 写入 actor_test_usage_log 失败（不影响 AI 主流程）: scene={} err={}",
                            trace.getScene(), e.getMessage());
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (Exception e) {
            // 线程池拒绝等极端情况：同样只告警
            inFlight.decrementAndGet();
            failed.incrementAndGet();
            log.warn("[TestUsage] 提交用量明细写入任务失败（不影响 AI 主流程）: {}", e.getMessage());
        }
    }

    /**
     * 等待在途写入落盘（测试与「跑完立刻出报告」用）。
     *
     * @param timeoutMs 最长等待毫秒
     *
     * @return true=已全部落盘
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.get() <= 0) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return inFlight.get() <= 0;
            }
        }
        return inFlight.get() <= 0;
    }

    /**
     * trace → 实体映射（全文原样，只对「元数据列」做防御性限长，避免超长值让整行插入失败）。
     *
     * @param trace 本次调用 trace
     *
     * @return 待插入实体
     */
    TestUsageLog toEntity(AiCallTrace trace) {
        TestUsageLog e = new TestUsageLog();
        e.setRunLabel(effectiveRunLabel());
        e.setCallAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(trace.getStartMs()), ZoneId.systemDefault()));
        e.setGameSecond(currentGameSecond(trace.getProjectId()));
        e.setScene(clamp(trace.getScene(), 48));
        e.setStepType(clamp(trace.getStepType(), 30));
        e.setTraceId(clamp(trace.getTraceId(), 64));
        e.setAttempt(trace.getAttempt());
        e.setProjectId(trace.getProjectId());
        e.setCharacterId(trace.getCharacterId());
        e.setProviderId(trace.getProviderId());
        e.setModel(clamp(trace.getModel(), 100));
        e.setBaseUrl(clamp(trace.getBaseUrl(), 255));
        e.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, trace.getDurationMs())));
        e.setTokenIn(trace.getTokenIn());
        e.setTokenOut(trace.getTokenOut());
        e.setCacheHitTokens(trace.getCacheHit());
        e.setCacheMissTokens(trace.getCacheMiss());
        e.setStatus(clamp(trace.getStatus(), 20));
        e.setErrorMessage(clamp(trace.getErrorMessage(), 500));
        // ===== 全文保真列：绝不截断 =====
        e.setSystemPrompt(trace.getSystemPrompt());
        e.setUserPrompt(trace.getUserPrompt());
        e.setResponseText(trace.getResponse());
        e.setRequestChars(codePointLength(trace.getSystemPrompt()) + codePointLength(trace.getUserPrompt()));
        e.setResponseChars(codePointLength(trace.getResponse()));
        return e;
    }

    /**
     * 取当前引擎游戏秒（只读标量；仓库缺失/无行/异常时返回 -1 表示「未知」，绝不抛给调用方）。
     *
     * @param projectId 项目 ID
     *
     * @return 游戏秒；未知 = -1
     */
    private long currentGameSecond(Long projectId) {
        if (worldClockRepository == null || projectId == null) {
            return -1L;
        }
        try {
            Long gs = worldClockRepository.findGameSecondByProjectId(projectId);
            return gs == null ? -1L : gs;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 元数据列防御性限长（按码点截断，避免截出半个代理对） */
    private static String clamp(String s, int max) {
        if (s == null) {
            return null;
        }
        int cp = s.codePointCount(0, s.length());
        if (cp <= max) {
            return s;
        }
        int end = s.offsetByCodePoints(0, max);
        return s.substring(0, end);
    }

    /** 码点长度（中文按 1 个字符计，便于与 token 估算对照） */
    private static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
