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

package com.holzyn.actor.domain.worldgen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 创建世界观生成任务请求（POST /api/worldgen/tasks）。
 *
 * @param projectId 归属项目 ID
 *
 * @param workflowCode 工作流编码（如 worldgen_build_v1 / worldgen_character_v1）
 *
 * @param inputs 任务输入（key=变量 key：premise/genre/language + 扩展； 冒烟支持
 * simulateFailNodeIds 测试钩子）
 *
 * @param mode 生成模式：
 * <ul>
 * <li>{@code worldview_only}（默认）：仅世界观设定——build 任务跑完预览确认落库即结束（现状行为）；</li>
 * <li>{@code full}：完整项目——build confirm 落库后自动串接 character 工作流
 * （角色阵容+深度生成→自动落库）与世界初始化 6 步（建档/普通 NPC/关系/时钟/知识），
 * 完成后写「世界诞生」编年史记录（不进角色感知，零 OOC）。</li>
 * </ul>
 */
public record CreateWorldGenTaskRequest(
        @NotNull(message = "projectId 不能为空") Long projectId,
        @NotBlank(message = "workflowCode 不能为空") String workflowCode,
        Map<String, String> inputs,
        String mode) {

    /** 生成模式：仅世界观设定（默认，与历史行为一致） */
    public static final String MODE_WORLDVIEW_ONLY = "worldview_only";

    /** 生成模式：完整项目（世界观 + character + 世界初始化 6 步） */
    public static final String MODE_FULL = "full";

    /**
     * 兼容旧调用。
     *
     * @param projectId 项目 ID
     *
     * @param workflowCode 工作流编码
     *
     * @param inputs 任务输入
     */
    public CreateWorldGenTaskRequest(Long projectId, String workflowCode, Map<String, String> inputs) {
        this(projectId, workflowCode, inputs, MODE_WORLDVIEW_ONLY);
    }
}
