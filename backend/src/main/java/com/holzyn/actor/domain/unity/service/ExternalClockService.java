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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外部时钟服务（U 轨道 U1 服务端对接层，§6 插件化预留切换点 1 兑现，）。
 * <p><b>时钟权威切换</b>：
 * Unity 连接期间，该世界时钟权威 = Unity——Unity 上报 {@code clock_report{gameSecond, rate}}
 * → 本服务把引擎推进位置拨到上报值（<b>防回退</b>：只进不退；<b>seq 幂等</b>：连接级对账
 * 在 {@link UnitySessionRegistry}）→ 复用 {@link WorldEngine} 既有 simulate 循环
 * 从旧位置推进到新位置（5 游戏分钟 tick + 每小时决策钩子全部照常触发）。
 * <b>单一权威</b>：外部时钟激活期间 WorldEngine 真实流逝×rate 自走路径跳过
 * （advanceProject 返回 unity_clock 状态），唯一写者 = Unity 时钟上报。</p>
 * <p>断开 = 世界静止（激活源回退多源判定），时钟位置冻结在 last_game_second；
 * 重连后从冻结进度接续（不补离线）。</p>
 * <p><b>异步处理</b>：WS 处理器线程只做入队（毫秒级返回）——引擎 simulate 可能含真实 AI
 * 调用（本地模型单次 5~30s），同步处理会长时间阻塞 WS 会话线程导致心跳堆积被看门狗自踢；
 * 每项目串行锁保证同一项目上报不并发（防止并发 simulate 双读旧位置重复推进）。</p>
 * <p>⚠️ 冒烟注意：单次上报推进封顶（默认 24 游戏小时）防 Unity 端异常大跳/重连补报雪崩；
 * 暂停语义保留（paused=1 时上报仅更新位置不模拟——对齐「暂停=时间冻结」）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalClockService {

    /** Unity 对接配置（时钟源/封顶） */
    private final UnityProperties props;

    /** Unity 会话注册表（连接判定：外部时钟激活 = 至少一个连接） */
    private final UnitySessionRegistry sessionRegistry;

    /** 世界时钟服务（读引擎位置/落库权威 last_game_second） */
    private final WorldClockService worldClockService;

    /** 世界推进引擎（复用 simulate 循环做外部推进） */
    private final WorldEngine worldEngine;

    /** 自代理（@Lazy 自注入：异步任务内调用 applyClockReportSync 走 Spring 代理使 @Transactional 生效） */
    @Lazy
    @Autowired
    private ExternalClockService self;

    /** 每项目串行锁（同一项目上报不并发——防双读旧位置重复推进） */
    private final ConcurrentHashMap<Long, Object> projectLocks = new ConcurrentHashMap<>();

    /** 异步执行器（虚拟线程：引擎推进/AI 决策不占 WS 容器线程） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 外部时钟当前是否激活（= Unity 连接存在；WorldEngine 自走路径据此跳过）。
     *
     * @param projectId 项目 ID
     *
     * @return true=外部时钟权威生效（Unity 连接中）
     */
    public boolean isExternalClockActive(Long projectId) {
        return props.isEnabled() && sessionRegistry.isConnected(projectId);
    }

    /**
     * 时钟上报<b>异步入口</b>（WS clock_report 消息消费；毫秒级返回，推进在虚拟线程执行）。
     * <p>每项目串行锁 + 虚拟线程：同一项目上报排队处理（客户端可按推进节奏调节上报频率）；
     * 失败隔离仅记日志（时钟不推进不阻塞后续上报）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond Unity 上报的游戏秒
     *
     * @param rate Unity 上报的倍率（可空）
     *
     * @param traceId 链路追踪 ID（日志贯通）
     */
    public void submitClockReport(Long projectId, Long gameSecond, Integer rate, String traceId) {
        if (!props.isEnabled()) {
            return;
        }
        if (projectId == null || gameSecond == null || gameSecond < 0) {
            log.warn("[Unity时钟] 上报参数无效（丢弃）: project={} gameSecond={} trace={}", projectId, gameSecond, traceId);
            return;
        }
        if (!isExternalClockActive(projectId)) {
            log.debug("[Unity时钟] 项目无 Unity 连接，上报忽略: project={}", projectId);
            return;
        }
        executor.execute(() -> {
            Object lock = projectLocks.computeIfAbsent(projectId, k -> new Object());
            synchronized (lock) {
                try {
                    UnityClockResult result = self.applyClockReportSync(projectId, gameSecond, rate);
                    log.info("[Unity时钟] 上报处理完成: project={} status={} {}→{} trace={}",
                            projectId, result.status(), result.fromSecond(), result.toSecond(), traceId);
                } catch (Exception e) {
                    log.warn("[Unity时钟] 上报处理失败（隔离继续）: project={} : {} trace={}",
                            projectId, e.getMessage(), traceId);
                }
            }
        });
    }

    /**
     * 时钟上报<b>同步核心</b>（测试/调试直调；WS 入口经 {@link #submitClockReport} 异步转发）。
     * <p>流程：连接校验 → 目标钳制/防回退 → 步长封顶 → 引擎从旧位置模拟到新位置
     * （复用 simulate）→ 返回结果。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond Unity 上报的游戏秒（必填，≥0）
     *
     * @param rate Unity 上报的倍率（可空=不更新时钟速率）
     *
     * @return 处理结果（status: disabled/invalid/external_inactive/rejected/idle/advanced/...）
     */
    @Transactional
    public UnityClockResult applyClockReportSync(Long projectId, Long gameSecond, Integer rate) {
        if (!props.isEnabled()) {
            return new UnityClockResult("disabled", -1, -1, "Unity 对接未启用");
        }
        if (projectId == null || gameSecond == null || gameSecond < 0) {
            return new UnityClockResult("invalid", -1, -1, "clock_report 缺少有效 gameSecond");
        }
        if (!isExternalClockActive(projectId)) {
            return new UnityClockResult("external_inactive", -1, -1, "项目无 Unity 连接，时钟上报忽略");
        }
        ActorWorldClock clock = worldClockService.requireClock(projectId);
        long current = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();

        // ① 防回退（只进不退）：Unity 端异常回拨拒绝（不覆盖引擎位置）
        if (gameSecond < current) {
            log.warn("[Unity时钟] 上报回退拒绝: project={} 上报={} 当前={}", projectId, gameSecond, current);
            return new UnityClockResult("rejected", current, gameSecond, "上报游戏秒小于当前引擎位置（防回退）");
        }
        // ② 幂等：相同位置不重复模拟
        if (gameSecond == current) {
            return new UnityClockResult("idle", current, current, "上报位置与引擎位置相同，无推进");
        }
        // ③ 步长封顶（防 Unity 端异常大跳/重连补报雪崩）
        long maxStep = Math.max(1L, props.getClock().getMaxCatchUpGameSeconds());
        long step = gameSecond - current;
        long effectiveTo = gameSecond;
        if (step > maxStep) {
            effectiveTo = current + maxStep;
            log.warn("[Unity时钟] 上报步长封顶: project={} 上报={} 当前={} 封顶后推进至={}（单次上限 {} 游戏秒）",
                    projectId, gameSecond, current, effectiveTo, maxStep);
        }
        // ④ 速率透传（Unity 端倍率刷新时钟行，供显示口径/恢复后自走参考；钳制 1~8760 与既有校验一致）
        if (rate != null && rate > 0 && rate <= 8760 && !rate.equals(clock.getRate())) {
            clock.setRate(rate);
            log.info("[Unity时钟] 速率透传: project={} rate={}", projectId, rate);
        }

        // ⑤ 引擎推进（复用 simulate：感知/决策/记忆三周期照常触发；事务边界在引擎方法内）
        Map<String, Object> result = worldEngine.advanceProjectExternal(projectId, effectiveTo);
        String status = String.valueOf(result.getOrDefault("status", "?"));
        return new UnityClockResult(status, current, effectiveTo,
                "时钟上报驱动推进 " + (effectiveTo - current) + " 游戏秒 → " + effectiveTo);
    }

    /** 执行器关闭（应用退出释放虚拟线程池）。 */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * 时钟上报处理结果（测试/日志断言用）。
     *
     * @param status 状态：disabled/invalid/external_inactive/rejected/idle/advanced（透传引擎状态）
     *
     * @param fromSecond 推进前引擎位置（无效时为 -1）
     *
     * @param toSecond 推进目标（无效时为 -1）
     *
     * @param message 说明
     */
    public record UnityClockResult(String status, long fromSecond, long toSecond, String message) {
        /** 便捷判断：是否成功推进（引擎返回 advanced） */
        public boolean advanced() {
            return "advanced".equals(status);
        }
    }
}
