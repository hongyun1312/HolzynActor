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

package com.holzyn.actor.domain.project.service;

/**
 * 工作流日志回调（新建项目解析 / 世界初始化）。
 * <p>职责：解析与初始化工作流执行时向调用方（SSE 控制器）逐条推送
 * 后端终端日志（info 行）与阶段进度（stage），前端控制台实时显示工作流进度。</p>
 * <p>实现约定：服务内每个步骤先 {@code log.info(...)}（后端终端必须输出），
 * 再通过本回调把同一行日志推给前端（与后端终端日志保持一致）。</p>
 */
public interface WorkflowLog {

    /**
     * 输出一行工作流日志（后端终端已输出后回调，供前端控制台展示）。
     *
     * @param message 日志内容（含前缀如 [世界观分段] 便于前端着色）
     */
    void info(String message);

    /**
     * 阶段进度通知（供前端进度条/节点定位当前阶段）。
     *
     * @param name 阶段中文名（如 世界观地点 / 角色卡）
     *
     * @param index 当前阶段序号（1 开始）
     *
     * @param total 阶段总数
     */
    void stage(String name, int index, int total);
}
