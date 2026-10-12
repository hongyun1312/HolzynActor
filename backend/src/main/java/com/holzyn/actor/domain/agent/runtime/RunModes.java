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

/**
 * 世界运行模式工具（阶段 D，domain/agent/runtime）——项目级三档模式。
 * <p>三种模式控制「该项目下的 AI 投入」：</p>
 * <ul>
 * <li><b>minimal 极简</b>：极致省钱——系统自动能力（目标生成/日计划/反思）降为机制化或关闭，
 * 项目预算压到最低；特殊 NPC 仍尊重 brainProfile（用户手动配置不被强制覆盖）。</li>
 * <li><b>standard 普通</b>（默认）：现状 + 机制化日计划（普通 NPC），特殊 NPC 正常 AI。</li>
 * <li><b>ultimate 极致</b>：成本最高——所有决策尽量 AI（普通 NPC 也 AI 生成当日计划），
 * 预算拉满、反思优先。</li>
 * </ul>
 * <p>归属：actor_world_engine.run_mode（每项目一行，懒创建默认 standard）。</p>
 */
public final class RunModes {

    /** 极简模式：极致省钱（系统自动能力降级/关闭，预算最低） */
    public static final String MINIMAL = "minimal";
    /** 普通模式（默认）：现状 + 机制化计划 */
    public static final String STANDARD = "standard";
    /** 极致模式：全量 AI（普通 NPC 也 AI 日计划，预算拉满） */
    public static final String ULTIMATE = "ultimate";

    /** 合法值集（非法/空回退 standard） */
    static final java.util.Set<String> VALID = java.util.Set.of(MINIMAL, STANDARD, ULTIMATE);

    private RunModes() {
    }

    /**
     * 归一化运行模式（非法/空 → standard）。
     *
     * @param mode 原始值（可空）
     *
     * @return minimal/standard/ultimate 之一
     */
    public static String normalize(String mode) {
        if (mode == null) {
            return STANDARD;
        }
        String m = mode.trim().toLowerCase();
        return VALID.contains(m) ? m : STANDARD;
    }

    /**
     * 是否极简模式（系统自动能力降级/关闭）。
     *
     * @param mode 运行模式（可空=standard）
     */
    public static boolean isMinimal(String mode) {
        return MINIMAL.equals(normalize(mode));
    }

    /**
     * 是否极致模式（全量 AI）。
     *
     * @param mode 运行模式（可空=standard）
     */
    public static boolean isUltimate(String mode) {
        return ULTIMATE.equals(normalize(mode));
    }

    /**
     * 运行模式 → 中文标签（前端展示）。
     *
     * @param mode 运行模式（可空）
     *
     * @return 中文标签
     */
    public static String label(String mode) {
        return switch (normalize(mode)) {
            case MINIMAL -> "极简（省钱优先）";
            case ULTIMATE -> "极致（全量 AI）";
            default -> "普通（标准）";
        };
    }

    /**
     * 每 tick AI 预算生效值（run_mode 覆盖）：
     * minimal=min(配置,1)（压到最低，仅保留关键 AI）、standard=配置原值、ultimate=max(配置,10)（拉满）。
     *
     * @param mode 运行模式（可空）
     *
     * @param configured 项目配置的 budget_per_tick（可空=5）
     *
     * @return 生效预算值
     */
    public static int effectiveBudget(String mode, Integer configured) {
        int base = configured == null ? 5 : Math.max(1, configured);
        return switch (normalize(mode)) {
            case MINIMAL -> Math.min(base, 1);
            case ULTIMATE -> Math.max(base, 10);
            default -> base;
        };
    }
}
