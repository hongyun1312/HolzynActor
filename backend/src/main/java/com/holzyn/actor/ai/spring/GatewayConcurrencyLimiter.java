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

package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关并发限流器（_07 网关并发错峰观察项；前缀 holzyn.actor.ai.gateway-concurrency.*）。
 * <p><b>背景</b>：日边界突发（20 角色决策 + 日级任务：plan_day/reflection/growth_space/world_frame/
 * 编年史/批量巩固）把自托管模型网关（本地 35B 单实例，并发容量有限）打排队 → 大量 chat 调用 120s
 * 读超时失败（_02 实测：growth_space 32 / plan_day 6 / world_frame 4 等失败；NanoJev/embedding
 * 毫秒级不受影响）。DeepSeek API 主用场景无此问题（官方网关并发容量大）——故<b>默认关闭（0=不限流）</b>，
 * 本地模型测试经 env 开启：超出上限的 chat 调用<b>排队等待空位</b>（而非雪崩式并发），等待超时按
 * 既有失败语义（AiCallException）走业务侧重试/兜底。</p>
 * <p><b>收口位置</b>：本限流器挂在 {@link AiSpringChatGateway}（全部 chat 调用唯一入口：
 * chatCompletion / chatCompletionJson / chatCompletionStream），NanoJev evaluate 与 embedding
 * 走独立通道天然不受影响。与 R1 前瞻预热（小时边界决策异步化）叠加削峰。</p>
 * <p><b>观测</b>：inFlight/maxInFlight/totalWaits/waitTimeouts 计数（{@link #stats()}），
 * 供测试冒烟与调试页观测排队而非超时。</p>
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "holzyn.actor.ai.gateway-concurrency")
public class GatewayConcurrencyLimiter {

    /** 并发上限（0=不限流直通；>0 启用：超出上限的 chat 调用排队等待空位）。env=HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_MAX_CONCURRENT */
    private int maxConcurrent = 0;

    /** 排队等待空位超时（秒；超时未获空位抛 AiCallException，调用方按既有失败语义重试/兜底）。env=HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_WAIT_TIMEOUT_SECONDS */
    private int waitTimeoutSeconds = 45;

    /** 是否启用（@PostConstruct 按 maxConcurrent>0 定；volatile 供观测线程安全读） */
    private volatile boolean enabled;

    /** 并发信号量（公平：先到先得，防等待长调用饿死新请求） */
    private Semaphore semaphore;

    /** 当前在途调用数 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** 观测到的历史最大在途数 */
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** 排队等待总次数（首获空位失败才计） */
    private final AtomicLong totalWaits = new AtomicLong();

    /** 等待超时次数（排队未获空位而失败） */
    private final AtomicLong waitTimeouts = new AtomicLong();

    /** 配置绑定后初始化（Spring 先 setter 后 @PostConstruct，保证 maxConcurrent 生效）。 */
    @PostConstruct
    void init() {
        enabled = maxConcurrent > 0;
        if (enabled) {
            semaphore = new Semaphore(maxConcurrent, true);
            log.info("[网关限流] 已启用：并发上限={} 排队等待超时={}s", maxConcurrent, waitTimeoutSeconds);
        } else {
            log.info("[网关限流] 未启用（max-concurrent=0 不限流）——本地模型测试可设 "
                    + "HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_MAX_CONCURRENT>0 开启");
        }
    }

    /**
     * HTTP 调用前占位：未启用=直通零开销；启用=先快速 tryAcquire，失败则排队等待空位
     * （最多 {@link #waitTimeoutSeconds} 秒），超时抛 {@link AiCallException}（业务侧按既有
     * 失败语义重试/兜底，与「网关排队 120s 读超时」同向但提前失败、避免悬挂）。
     */
    public void acquire() {
        if (!enabled) {
            return;
        }
        if (!semaphore.tryAcquire()) {
            totalWaits.incrementAndGet();
            long start = System.nanoTime();
            boolean acquired;
            try {
                acquired = semaphore.tryAcquire(waitTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                waitTimeouts.incrementAndGet();
                throw new AiCallException("AI 调用排队被中断（网关并发限流等待空位）");
            }
            if (!acquired) {
                waitTimeouts.incrementAndGet();
                throw new AiCallException("AI 调用排队超时（网关并发已达上限 " + maxConcurrent
                        + "，等待 " + waitTimeoutSeconds + "s 未获空位）：请稍后重试或调高 "
                        + "HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_MAX_CONCURRENT");
            }
            if (log.isDebugEnabled()) {
                log.debug("[网关限流] 排队获空位：等待 {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }
        int cur = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(cur, Math::max);
    }

    /** HTTP 调用结束释放空位（未启用=零开销）。 */
    public void release() {
        if (!enabled) {
            return;
        }
        inFlight.decrementAndGet();
        semaphore.release();
    }

    /** 限流观测快照。 */
    public Stats stats() {
        return new Stats(maxConcurrent, enabled, inFlight.get(), maxInFlight.get(),
                totalWaits.get(), waitTimeouts.get());
    }

    /** 限流观测数据。 */
    public record Stats(int maxConcurrent, boolean enabled, int inFlight, int maxInFlight,
                        long totalWaits, long waitTimeouts) {
    }
}
