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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 空闲降频服务。
 * <p>定位（主开发方案 §7 「空闲降频（可选）」+ 「做：行动延续期间降频」）：
 * 特殊 NPC 有进行中行动（最近决策过）且无新刺激时，该游戏小时的<b>周期决策</b>跳过
 * （延续当前行动零 AI）；行动完成（窗口过后）或有刺激（即时通道立即处理）自动恢复——
 * 真人不会每分钟都想一次「我该干嘛」，省 AI 成本且行为更真实（对齐自适应决策频率研究：
 * 决策频率随情境自适应，而非固定节拍）。</p>
 * <p>实现：内存 Map（projectId:ref → 最近一次决策游戏秒）记录决策时刻；{@link #shouldSkipPeriodic}
 * 判定「距上次决策 < idle-decision-window-game-minutes（默认 120 游戏分钟）」→ 本次周期决策跳过。
 * 任何触发源（周期/刺激/手动）的决策都经 {@link #noteDecision} 刷新——刺激即时通道天然不受本降频
 * 影响（即时决策不经本判定），行为连续性语义保持。</p>
 * <p>对齐目标「世界 24h 不间断 + 角色像真人」：降频只作用于「无事可做的例行决策」，
 * 不降低世界真实度——有刺激随时响应，窗口过后恢复例行规划。</p>
 */
@Slf4j
@Service
public class IdleFrequencyService {

    /**
     * 决策时刻表：key=projectId:npcRef → 最近一次决策游戏秒（进程内存态；重启丢失可接受，
     * 重启后首小时视为「需要决策」立即恢复周期通道）
     */
    private final ConcurrentHashMap<String, Long> lastDecisionSecond = new ConcurrentHashMap<>();

    /** 配置（environment/idle 段；null=纯数据单测/未装配，判定放宽不降频） */
    @Autowired(required = false)
    private RealtimeDecisionProperties props;

    /** 刺激日志服务（调试前端刺激日志页数据源；null=未装配跳过记录） */
    @Autowired(required = false)
    private StimulusLogService stimulusLogService;

    /**
     * 记录一次决策时刻（AgentRuntime 决策循环完成后调用——任何触发源共用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（仅 special:{id} 参与降频）
     *
     * @param gameSecond 当前游戏秒（可空=不记录）
     */
    public void noteDecision(Long projectId, String npcRef, Long gameSecond) {
        if (projectId == null || npcRef == null || gameSecond == null) {
            return;
        }
        lastDecisionSecond.put(projectId + ":" + npcRef, gameSecond);
        enforceLimit();
    }

    /**
     * 是否应跳过本次周期决策（空闲降频判定）：
     * 总开关关 / 最近无决策记录 / 窗口配置非法 → false（不降频，正常决策）；
     * 距上次决策 < 窗口 → true（有进行中行动，延续零 AI）；
     * 距上次决策 ≥ 窗口 → false（行动视为完成/需要新决策，恢复周期通道）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（仅特殊 NPC 参与）
     *
     * @param gameSecond 当前游戏秒（可空=0 基准，不降频）
     *
     * @return true=本次周期决策跳过
     */
    public boolean shouldSkipPeriodic(Long projectId, String npcRef, Long gameSecond) {
        if (props == null || !props.isIdleReductionEnabled() || projectId == null || npcRef == null
                || gameSecond == null) {
            return false;
        }
        int windowMinutes = props.getIdleDecisionWindowGameMinutes();
        if (windowMinutes <= 0) {
            return false;
        }
        Long last = lastDecisionSecond.get(projectId + ":" + npcRef);
        if (last == null) {
            return false; // 从未决策过 → 需要决策
        }
        long windowSeconds = windowMinutes * 60L;
        boolean skip = gameSecond - last < windowSeconds;
        if (skip && stimulusLogService != null) {
            try {
                stimulusLogService.log(projectId, gameSecond, "idle_skip", npcRef, "",
                        "idle_skip", "空闲降频：距上次决策 " + (gameSecond - last) + " 秒 < 窗口 " + windowMinutes + " 分钟，延续行动零 AI");
            } catch (Exception e) {
                log.debug("[空闲降频] 刺激日志记录失败（跳过）: {}", e.getMessage());
            }
        }
        return skip;
    }

    /** 内存表条目上限保护（防长期运行膨胀）。 */
    private void enforceLimit() {
        if (lastDecisionSecond.size() > 5000) {
            lastDecisionSecond.keySet().stream().findFirst().ifPresent(lastDecisionSecond::remove);
        }
    }
}
