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

import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一调度壳（阶段 D，domain/agent/runtime）——世界推进与计划行动的<b>唯一定时入口</b>。
 * <p>背景： 「统一推进入口 + 暂停语义修复」B3 已把双推进线收敛到 {@link WorldEngine}（CrowdScheduledJob
 * 已删）； 收尾进一步把剩余两个 {@code @Scheduled} 定时任务收编为统一调度壳，实现：</p>
 * <ul>
 * <li><b>世界推进扫描</b>（原 {@code WorldSimulationJob}）：定时扫描并推进「持续运行」的项目
 * （委托 {@link WorldEngine#scanAndAdvance}，引擎内部按 world_running 筛选 + paused 统一跳过）；</li>
 * <li><b>计划行动到期执行</b>（原 {@code ActionScheduledJob}）：轮询 actor_action_plan 中
 * status=planned 且已到期的决策，委托 {@link AgentActionService#execute}；
 * <b>统一暂停语义</b>：世界暂停（paused=1）的项目跳过执行（暂停=时间冻结，角色不应继续行动）；</li>
 * <li><b>统一开关/间隔/异常隔离</b>：world-scan-enabled / action-execute-enabled 各自开关，
 * 单项目/单计划失败不阻断其他（try 隔离），@Scheduled 仅保留在本壳两处。</li>
 * </ul>
 * <p>旧 {@code WorldSimulationJob}/{@code ActionScheduledJob} 已物理删除（0 引用；
 * 手动推进端点 {@code /world-clock/advance} 改为直连 {@link WorldEngine}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EngineScheduler {

    /** 世界推进引擎（唯一推进入口） */
    private final WorldEngine worldEngine;

    /** 行动决策仓库（到期计划轮询） */
    private final ActorActionPlanRepository planRepository;

    /** 行动组件（决策执行收编 AgentActionService） */
    private final AgentActionService agentActionService;

    /** 角色仓库（解析计划归属项目，判断世界是否暂停） */
    private final ActorCharacterRepository characterRepository;

    /** 世界时钟仓库（查询项目时钟暂停状态） */
    private final ActorWorldClockRepository clockRepository;

    /**
     * 实时决策激活闸门（用户需求 后端启动不自动运行实时决策——默认关；
     * 到期计划执行同样门控：未激活项目不执行计划行动（世界暂停）；null=未装配[单测]跳过闸门）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RealtimeDecisionActivationService realtimeDecisionActivationService;

    /** 世界推进扫描开关（默认 true） */
    @Value("${holzyn.actor.scheduler.world-scan-enabled:true}")
    private boolean worldScanEnabled;

    /** 计划行动到期执行开关（默认 true） */
    @Value("${holzyn.actor.scheduler.action-execute-enabled:true}")
    private boolean actionExecuteEnabled;

    /**
     * 定时扫描并推进「持续运行」的项目（原 WorldSimulationJob； 起默认每 12.5 真实秒
     * = 5 游戏分钟@rate=24，可配 scan-interval-ms）。暂停的项目由 WorldEngine 内部统一跳过。
     * <p>⚠️ 关键配套（世界演化与游戏时间系统，）：原默认 180000（3 真实分钟）在
     * rate=24 下按游戏小时换算恒为 0（引擎永远 idle），故默认降至 12500 并配合引擎「逐 5 游戏分钟
     * 步进」实现高频追平（游戏时间单一连续权威 + 引擎几乎同步）。</p>
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.world.scan-interval-ms:12500}")
    public void worldScan() {
        if (!worldScanEnabled) {
            return;
        }
        worldEngine.scanAndAdvance();
    }

    /**
     * 定时轮询到期计划并执行（原 ActionScheduledJob；每 60 秒）。
     * <b>统一暂停语义</b>：世界暂停（paused=1）的项目跳过执行（暂停=世界时间冻结，角色不应继续行动）。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void runDuePlans() {
        if (!actionExecuteEnabled) {
            return;
        }
        try {
            List<ActorActionPlan> due = planRepository.findByStatusAndPlannedTimeLessThanEqual("planned", LocalDateTime.now());
            for (ActorActionPlan plan : due) {
                try {
                    // ⚠️ 冒烟抓修：等待类计划（planned_game_second 非空，时间感行动
                    // wait_minutes 排期）不走真实时间定时执行——由 WorldEngine 每游戏小时边界
                    // runDueWaitPlans 按游戏时刻到点统一处理（标记 done + 重新 AI 决策）；
                    // 否则排期后 ~60s 即被本定时器抢先 execute（实测 2768 在排期后约 6 分钟被
                    // 抢先执行，到点重新决策从未发生，等待语义失效）
                    if (plan.getPlannedGameSecond() != null) {
                        log.debug("跳过等待类计划（由游戏时刻到点处理）：plan={}", plan.getId());
                        continue;
                    }
                    // 世界暂停的项目跳过执行（与世界推进扫描的暂停语义统一）
                    if (isWorldPaused(plan.getCharacterId())) {
                        log.info("行动定时执行跳过（世界已暂停）：plan={} char={}", plan.getId(), plan.getCharacterId());
                        continue;
                    }
                    // 实时决策激活闸门：未激活项目跳过执行（世界未运行，角色不应继续行动）
                    Long pid = projectIdOf(plan.getCharacterId());
                    if (pid != null && realtimeDecisionActivationService != null
                            && !realtimeDecisionActivationService.isActive(pid)) {
                        log.info("行动定时执行跳过（实时决策未激活）：plan={} char={}", plan.getId(), plan.getCharacterId());
                        continue;
                    }
                    log.info("定时执行行动决策: plan={} char={}", plan.getId(), plan.getCharacterId());
                    agentActionService.execute(plan);
                } catch (Exception e) {
                    // 单计划执行失败不阻断其他计划
                    log.warn("定时执行行动决策失败（隔离继续）：plan={} : {}", plan.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("行动定时任务执行失败: {}", e.getMessage());
        }
    }

    /**
     * 判断角色所属项目世界是否暂停（无角色/无时钟视为未暂停）。
     *
     * @param characterId 角色 ID
     *
     * @return true 表示世界已暂停，应跳过执行
     */
    private boolean isWorldPaused(Long characterId) {
        Long projectId = projectIdOf(characterId);
        if (projectId == null) {
            return false;
        }
        return clockRepository.findByProjectId(projectId)
                .map(c -> Integer.valueOf(1).equals(c.getPaused()))
                .orElse(false);
    }

    /**
     * 角色所属项目 ID 解析（无角色返回 null）。
     *
     * @param characterId 角色 ID
     *
     * @return 项目 ID（无角色/查询失败返回 null）
     */
    private Long projectIdOf(Long characterId) {
        if (characterId == null) {
            return null;
        }
        return characterRepository.findById(characterId)
                .map(ActorCharacter::getProjectId).orElse(null);
    }
}
