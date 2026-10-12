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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.world.config.EvolutionDramatizeProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重大世界帧事件触发演绎。
 * <p>需求来源：主开发方案 §4.5「重大世界帧事件触发演绎（可选，开关默认关）——world_frame 事件
 * severity ≥ 阈值时，可自动创建一场演化会话去『演绎』该事件（导演式呈现）」+
 * （AI 选角 + 自动建会话 + 每日限频 + source=engine 限定 + 归档排除防环 + 幂等去重）。
 * ⚠️ 冒烟抓修：预算门控从「同窗同流 tick 预算」改为<b>独立每日预算</b>（用户确认）——
 * 引擎活跃时（重大事件发生的正常场景）tick 预算几乎总是耗尽，同窗门控会让演绎永不触发；
 * 对齐世界帧 AI 的独立预算模式：不占/不被占用 tick 预算，每日上限由限频天然约束，开关默认关兜底成本。</p>
 * <p>触发条件（防环与成本护栏）：</p>
 * <ul>
 * <li>① 总开关 {@link EvolutionDramatizeProperties#isEnabled()}（默认关，关=不注册订阅者零成本）；</li>
 * <li>② kind=world_frame 且 source=<b>engine</b>——每日帧/紧急帧宏观事件（扩散到达事件 importance=1
 * 天然低于阈值）；<b>排除 source=evolution 归档事件防「演绎的演绎」无限循环</b>（起归档事件
 * kind 同为 world_frame，靠 source 区分）；</li>
 * <li>③ importance ≥ 阈值（默认 4）；</li>
 * <li>④ 实时决策激活闸门：世界未激活（未打开沙盘/演化页）不演绎；</li>
 * <li>⑤ 幂等：同 source_key 只演绎一次（内存 Set）；</li>
 * <li>⑥ 每日限频：每项目每游戏日 ≤ {@link EvolutionDramatizeProperties#getMaxPerDay()}（内存计数器）。</li>
 * </ul>
 * <p>执行：异步虚拟线程委托 {@link WorldEvolutionMechanismService#startDramatization} 创建演化会话
 * （AI 选角、模式=ai、running，用户可在演化页手动播放/推进；AI 失败回退确定性选角，会话仍创建），
 * 失败隔离不阻断事件总线。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvolutionDramatizeTrigger implements WorldEventSubscriber {

    /** 事件类别：世界帧（与 WorldFrameService 同源常量） */
    private static final String KIND_WORLD_FRAME = "world_frame";

    /** 事件来源：引擎（每日帧/紧急帧宏观事件；排除 evolution 归档防环） */
    private static final String SOURCE_ENGINE = "engine";

    /** 幂等键集合（直接用事件 source_key；进程内存态，重启清空） */
    private final Set<String> dramatized = ConcurrentHashMap.newKeySet();

    /**
     * 每日限频计数：key=projectId:gameDay → 当日已触发次数（进程内存态，重启丢失可接受；
     * 超上限清空防膨胀，对齐失真缓存上限清理先例）
     */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> dailyCounts = new ConcurrentHashMap<>();

    /** 每日限频计数上限（超限清空防内存膨胀） */
    private static final int DAILY_COUNT_CACHE_LIMIT = 500;

    /** 异步演绎执行器（虚拟线程，不阻塞事件发布方） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final EvolutionDramatizeProperties props;
    private final WorldEventBus worldEventBus;
    private final WorldEvolutionMechanismService evolutionMechanismService;
    private final RealtimeDecisionActivationService realtimeDecisionActivationService;

    /** 世界时钟（当前游戏日基准；null=未装配[单测]，回退事件 gameHour 换算） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 启动注册：总开关开启时注册为世界事件总线订阅者（关=不注册，零成本）。
     */
    @PostConstruct
    void register() {
        if (props.isEnabled()) {
            worldEventBus.subscribe(this);
            log.info("[演绎] 重大世界帧事件自动演绎已开启（阈值={} 每日上限={}）",
                    props.getImportanceThreshold(), props.getMaxPerDay());
        }
    }

    /**
     * 世界事件总线订阅回调：满足触发条件 → 异步创建演绎演化会话。
     *
     * @param event 已落库的世界事件
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!props.isEnabled() || event == null || event.getProjectId() == null) {
            return;
        }
        // ② 触发类别限定：kind=world_frame + source=engine（防环：evolution 归档排除；扩散到达 importance=1 天然低于阈值）
        if (!KIND_WORLD_FRAME.equals(event.getKind()) || !SOURCE_ENGINE.equals(event.getSource())) {
            return;
        }
        // ③ 重要度阈值
        Integer importance = event.getImportance();
        if (importance == null || importance < props.getImportanceThreshold()) {
            return;
        }
        Long projectId = event.getProjectId();
        // ④ 激活闸门：世界未激活（未打开沙盘/演化页）不演绎（用户需求）
        if (!realtimeDecisionActivationService.isActive(projectId)) {
            return;
        }
        // ⑤ 幂等：同事件只演绎一次
        String key = event.getSourceKey() != null && !event.getSourceKey().isBlank()
                ? event.getSourceKey() : "ev:" + event.getId();
        if (!dramatized.add(key)) {
            return;
        }
        // ⑥ 每日限频：每项目每游戏日 ≤ maxPerDay（超限跳过不报错；计数器防膨胀）
        long day = gameDayOf(projectId, event);
        if (day >= 0) {
            if (dailyCounts.size() > DAILY_COUNT_CACHE_LIMIT) {
                dailyCounts.clear();
            }
            String dayKey = projectId + ":" + day;
            java.util.concurrent.atomic.AtomicInteger cnt = dailyCounts.computeIfAbsent(dayKey,
                    k -> new java.util.concurrent.atomic.AtomicInteger());
            if (cnt.get() >= props.getMaxPerDay()) {
                log.info("[演绎] 当日已触发（上限 {} 次/游戏日）跳过：project={} day={} key={}",
                        props.getMaxPerDay(), projectId, day, key);
                return;
            }
            cnt.incrementAndGet();
        }
        log.info("[演绎] 重大世界帧事件触发演绎：project={} event={} day={} title={}",
                projectId, event.getId(), day, event.getTitle());
        // 独立每日预算（用户确认）：不占/不被占用 tick 预算——引擎活跃时 tick 预算常耗尽，
        // 同窗门控会让演绎永不触发；成本由「开关默认关 + 每日限频」天然约束（对齐世界帧 AI 独立预算模式）
        executor.execute(() -> {
            try {
                evolutionMechanismService.startDramatization(projectId, event);
            } catch (Exception e) {
                log.warn("[演绎] 自动演绎失败（隔离继续）：project={} event={} : {}", projectId, event.getId(), e.getMessage());
            }
        });
    }

    /**
     * 当前游戏日（每日限频基准）：优先世界时钟引擎位置，回退事件 gameHour 换算。
     *
     * @param projectId 项目 ID
     *
     * @param event 触发事件
     *
     * @return 游戏日序号（自纪元起；全部不可用返回 -1=不限制）
     */
    private long gameDayOf(Long projectId, ActorEvent event) {
        try {
            if (worldClockService != null) {
                Long second = worldClockService.currentEngineGameSecond(projectId);
                if (second != null && second > 0) {
                    return second / 86400L;
                }
            }
        } catch (Exception ignored) {
            // 回退事件换算
        }
        if (event.getGameHour() != null) {
            return event.getGameHour() / 24L;
        }
        return -1L;
    }
}
