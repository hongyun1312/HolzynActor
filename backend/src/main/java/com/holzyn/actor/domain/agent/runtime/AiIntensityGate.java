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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 强度闸门。
 * <p>职责：项目级「AI 强度」四档独立维度的统一闸门（设计《宏观社会演化系统_设计方案》
 * §8.2：programmatic 纯规则 → light 轻 AI → full 标准双通道 → max 全 AI 必调；与 runMode
 * 正交可叠加——runMode 控制引擎节奏，AI 强度控制智能体 AI 使用密度，全局生效）：</p>
 * <ul>
 * <li><b>programmatic</b>：所有 AI 决策点禁止调用（纯规则/模板兜底）；</li>
 * <li><b>light</b>：仅关键点低预算 AI（预算按档位缩减——宏观层=每日预算 ×0.5）；</li>
 * <li><b>full</b>：标准双通道（当前默认行为，预算原值）；</li>
 * <li><b>max</b>：全 AI 必调、无预算限制（效果演示/验收用，成本最大）。</li>
 * </ul>
 * <p>持久化：actor_world_engine.ai_intensity 列（引擎行懒创建默认 full）；进程缓存 + 写入直通。
 * 本轮接入范围=宏观层 AI 决策点（MacroAiService 叙事/判定）；微观层（实时决策/环境润色/
 * 编年史提炼/世界帧等）接入列为 <b>阶段</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIntensityGate {

    /** 档位：纯规则（零 AI） */
    public static final String PROGRAMMATIC = "programmatic";
    /** 档位：轻 AI（关键点低预算） */
    public static final String LIGHT = "light";
    /** 档位：标准双通道（默认） */
    public static final String FULL = "full";
    /** 档位：全 AI 必调（无预算限制） */
    public static final String MAX = "max";

    /** 档位显示名 */
    public static final Map<String, String> LABELS = Map.of(
            PROGRAMMATIC, "纯规则", LIGHT, "轻 AI", FULL, "标准", MAX, "Max 全 AI");

    private final ActorWorldEngineRepository engineRepository;

    /** 进程缓存：projectId → 强度档（写入直通 + 缓存失效；重启从引擎行恢复） */
    private final Map<Long, String> cache = new ConcurrentHashMap<>();

    /** 档位归一化（未知回退 full）。 */
    public static String normalize(String v) {
        if (v == null || v.isBlank()) {
            return FULL;
        }
        return switch (v.trim()) {
            case PROGRAMMATIC, LIGHT, MAX -> v.trim();
            default -> FULL;
        };
    }

    /**
     * 项目当前 AI 强度档（恒非空；引擎行缺失=默认 full）。
     */
    public String intensityOf(Long projectId) {
        if (projectId == null) {
            return FULL;
        }
        String cached = cache.get(projectId);
        if (cached != null) {
            return cached;
        }
        try {
            ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElse(null);
            String v = normalize(engine == null ? null : engine.getAiIntensity());
            cache.put(projectId, v);
            return v;
        } catch (Exception e) {
            return FULL;
        }
    }

    /**
     * 该档位是否允许 AI 调用（programmatic=false，其余 true）。
     */
    public boolean allowsAi(Long projectId) {
        return !PROGRAMMATIC.equals(intensityOf(projectId));
    }

    /** 是否 Max 档（全 AI 必调、无预算限制）。 */
    public boolean isMax(Long projectId) {
        return MAX.equals(intensityOf(projectId));
    }

    /** 是否 programmatic 档（纯规则，零 AI——allowsAi 的否定语义，供微层接线可读调用）。 */
    public boolean isProgrammatic(Long projectId) {
        return PROGRAMMATIC.equals(intensityOf(projectId));
    }

    /** 是否 Light 档（轻 AI：预算缩减）。 */
    public boolean isLight(Long projectId) {
        return LIGHT.equals(intensityOf(projectId));
    }

    /**
     * 按档位折算的每日预算上限（full=max 原值 / light=ceil(原值×0.5) / max=不限[Int.MAX] /
     * programmatic=0；调用方配合 allowsAi 使用）。
     *
     * @param projectId 项目 ID
     *
     * @param baseCap 配置原值（如宏观每日 AI 预算 8）
     *
     * @return 生效上限（max → Integer.MAX_VALUE 表示不限）
     */
    public int effectiveCap(Long projectId, int baseCap) {
        String level = intensityOf(projectId);
        if (MAX.equals(level)) {
            return Integer.MAX_VALUE;
        }
        if (LIGHT.equals(level)) {
            return Math.max(1, (int) Math.ceil(baseCap * 0.5));
        }
        if (PROGRAMMATIC.equals(level)) {
            return 0;
        }
        return Math.max(0, baseCap);
    }

    /**
     * 设置项目 AI 强度档（写引擎行 + 缓存；引擎行缺失懒创建）。
     *
     * @param projectId 项目 ID
     *
     * @param level 档位（programmatic/light/full/max）
     */
    public void setIntensity(Long projectId, String level) {
        String normalized = normalize(level);
        ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorWorldEngine e = new ActorWorldEngine();
            e.setProjectId(projectId);
            return e;
        });
        engine.setAiIntensity(normalized);
        engineRepository.save(engine);
        cache.put(projectId, normalized);
        log.info("[AI 强度] 项目档位更新: project={} level={}", projectId, normalized);
    }

    /** 档位显示名（未知回退「标准」）。 */
    public static String label(String level) {
        return LABELS.getOrDefault(normalize(level), LABELS.get(FULL));
    }
}
