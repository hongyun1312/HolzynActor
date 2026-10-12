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
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
