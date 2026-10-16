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

package com.holzyn.actor.domain.agent.runtime.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngine;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 世界引擎配置控制器（阶段 D，domain/agent/runtime/controller）。
 * <p>职责：项目级世界引擎配置的读取与更新——<b>运行模式 run_mode</b>
 * （minimal 极简/standard 普通/ultimate 极致，）+ <b>AI 强度 ai_intensity</b>
 * （programmatic 纯规则/light 轻 AI/full 标准/max 全 AI，独立四档维度，与 runMode 正交可叠加——
 * runMode 管引擎节奏，AI 强度管智能体 AI 使用密度，设计《宏观社会演化系统_设计方案》§8.2；
 * 前端 AI 强度滑块/按钮读写此接口）。配置行每项目唯一，懒创建默认。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-engine")
@RequiredArgsConstructor
public class WorldEngineController {

    private final ActorWorldEngineRepository engineRepository;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiIntensityGate aiIntensityGate;
    private final WorldEngine worldEngine;
    private final com.holzyn.actor.domain.agent.runtime.DecisionLedgerService decisionLedgerService;

    /**
     * 决策重试作业服务（P0-02 根治 · 可重试性）。字段注入 required=false：未装配（单测/壳）
     * 时端点返回空统计，不阻断。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.DecisionRetryService decisionRetryService;

    /**
     * 读取世界引擎配置（含 run_mode + ai_intensity）。
     *
     * @param projectId 项目 ID
     *
     * @return 引擎配置（runMode/budgetPerTick/maxStepHours/runModeLabel/aiIntensity/aiIntensityLabel）
     */
    @GetMapping
    public R<Map<String, Object>> get(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("runMode", engine == null ? RunModes.STANDARD : RunModes.normalize(engine.getRunMode()));
        out.put("runModeLabel", RunModes.label(out.get("runMode").toString()));
        out.put("budgetPerTick", engine == null ? 5 : (engine.getBudgetPerTick() == null ? 5 : engine.getBudgetPerTick()));
        out.put("maxStepHours", engine == null ? 24 : (engine.getMaxStepHours() == null ? 24 : engine.getMaxStepHours()));
        String intensity = aiIntensityGate.intensityOf(projectId);
        out.put("aiIntensity", intensity);
        out.put("aiIntensityLabel", AiIntensityGate.label(intensity));
        return R.ok(out);
    }

    /**
     * 时间回补可观测（P0-01 调试查询入口）。
     * <p>返回 tick 队列统计：{@code committedGameSecond} 已消费位置（引擎实际走到哪）、
     * {@code currentBacklogGameSeconds} 未消费积压（授权前沿−已消费位置；0=已追平）、
     * {@code peakBacklogGameSeconds} 观测到的峰值积压（WARN 高水位）、{@code deferredRounds} 出现积压的轮次数、
     * {@code droppedGameSeconds} 显式丢弃累计（队列溢出部分）、{@code queueCapacityGameSeconds} 队列容量。
     * 配合世界时钟 VO 的 {@code flowingGameSecond / clockLagGameSeconds / authorizedTargetSecond / backlogGameSeconds}
     * 诊断字段，可观测「引擎位置 vs 墙钟推算位置」两条线是否持续偏离（P0-01 验收标准 3）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 时间回补统计（该项目从未推进时为全 0 基线）
     */
    @GetMapping("/time-metrics")
    public R<Map<String, Object>> timeMetrics(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        Map<String, Object> m = worldEngine.timeBacklogSnapshot(projectId);
        if (m == null) {
            m = new LinkedHashMap<>();
            m.put("projectId", projectId);
            m.put("committedGameSecond", 0L);
            m.put("currentBacklogGameSeconds", 0L);
            m.put("peakBacklogGameSeconds", 0L);
            m.put("deferredRounds", 0L);
            m.put("droppedGameSeconds", 0L);
            m.put("queueCapacityGameSeconds", 0L);
            m.put("unit", "game-seconds");
        }
        return R.ok(m);
    }

    /**
     * 决策来源统计（P0-03 度量基座；P0-02 验收 1「三类之和 = 该日决策点总数」）。
     * <p>按 {@code gameDay} 统计决策台账：{@code ai}=AI 决策（AI/AI_RETRY）、
     * {@code fallback}=程序化兜底 + 预算耗尽降级、{@code missing}=决策缺失；
     * {@code total}=决策点总数，{@code aiRatio/fallbackRatio/missingRatio}=占比（%），
     * {@code bySource}=按枚举名细分。关闭模型空跑时面板应显示 {@code ai=0 / fallback=0 / missing=全部}
     * （不得显示「一切正常」）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日（自纪元起；缺省=最近一次 tick 统计的游戏日）
     *
     * @return 决策来源统计
     */
    @GetMapping("/decision-stats")
    public R<Map<String, Object>> decisionStats(@PathVariable("projectId") Long projectId,
                                                @RequestParam(name = "gameDay", required = false) Long gameDay) {
        requireProject(projectId);
        Long day = gameDay;
        if (day == null) {
            var recent = decisionLedgerService.recentTickStats(projectId, 1);
            day = recent.isEmpty() ? 0L : (recent.get(0).get("gameSecond") == null ? 0L
                    : Math.floorDiv((Long) recent.get(0).get("gameSecond"), 86400L));
        }
        return R.ok(decisionLedgerService.dayStats(projectId, day));
    }

    /**
     * 决策重试队列统计（P0-02 根治 · 可重试性可观测）。
     * <p>返回 {@code pending}=待重试（未到退避到期/等待扫描认领）、{@code retrying}=认领中、
     * {@code succeeded}=重试成功（决策点已以 AI/AI_RETRY 回填）、{@code abandoned}=重试耗尽
     * 放弃（决策点落显式缺失），以及退避/容量配置。配合决策来源统计（{@code /decision-stats}）
     * 可核对「决策失败 → 重试补算 → AI 占比恢复」的完整闭环。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 重试队列统计（服务未装配时返回空基线）
     */
    @GetMapping("/decision-retries")
    public R<Map<String, Object>> decisionRetries(@PathVariable("projectId") Long projectId) {
        requireProject(projectId);
        if (decisionRetryService == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", projectId);
            out.put("enabled", false);
            out.put("unit", "decision-retry-job");
            return R.ok(out);
        }
        return R.ok(decisionRetryService.stats(projectId));
    }

    /**
     * 最近 N 轮 tick 预算统计（P0-03：BudgetGuard 消耗持久化查询——预算/已用/降级/各来源决策数，
     * 修「预算耗尽只在内存、endTick 即 remove」）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 条数上限（缺省 10）
     *
     * @return 最近 N 轮 tick 统计（最新在前）
     */
    @GetMapping("/tick-stats")
    public R<Map<String, Object>> tickStats(@PathVariable("projectId") Long projectId,
                                            @RequestParam(name = "limit", required = false) Integer limit) {
        requireProject(projectId);
        int lim = limit == null || limit <= 0 ? 10 : Math.min(limit, 100);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("rows", decisionLedgerService.recentTickStats(projectId, lim));
        out.put("unit", "tick-stat");
        return R.ok(out);
    }

    /**
     * 世界引擎配置（run_mode 与/或 ai_intensity）。
     *
     * @param projectId 项目 ID
     *
     * @return 更新后的引擎配置
     */
    @PutMapping
    public R<Map<String, Object>> update(@PathVariable("projectId") Long projectId, @RequestBody Map<String, Object> body) {
        requireProject(projectId);
        String runMode = body == null ? null : (String) body.get("runMode");
        String aiIntensity = body == null ? null : (String) body.get("aiIntensity");
        if ((runMode == null || runMode.isBlank()) && (aiIntensity == null || aiIntensity.isBlank())) {
            throw new BizException(400, "runMode（minimal/standard/ultimate）或 aiIntensity（programmatic/light/full/max）至少一项");
        }
        String normalized = runMode == null || runMode.isBlank() ? null : RunModes.normalize(runMode);
        String normalizedIntensity = aiIntensity == null || aiIntensity.isBlank() ? null
                : AiIntensityGate.normalize(aiIntensity);
        ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorWorldEngine e = new ActorWorldEngine();
            e.setProjectId(projectId);
            e.setBudgetPerTick(5);
            e.setMaxStepHours(24);
            e.setLastAiBudgetResetAt(LocalDateTime.now());
            return e;
        });
        if (normalized != null) {
            engine.setRunMode(normalized);
        }
        if (normalizedIntensity != null) {
            engine.setAiIntensity(normalizedIntensity);
            aiIntensityGate.setIntensity(projectId, normalizedIntensity); // 缓存同步（引擎行已由本方法保存）
        }
        engineRepository.save(engine);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("runMode", engine.getRunMode());
        out.put("runModeLabel", RunModes.label(engine.getRunMode()));
        out.put("aiIntensity", normalizedIntensity == null ? aiIntensityGate.intensityOf(projectId) : normalizedIntensity);
        out.put("aiIntensityLabel", AiIntensityGate.label(normalizedIntensity == null
                ? aiIntensityGate.intensityOf(projectId) : normalizedIntensity));
        return R.ok(out);
    }

    /** 项目归属校验（单用户 id=1，project 存在性 + 归属） */
    private void requireProject(Long projectId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, currentUserProvider.currentUserId(), 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }
}
