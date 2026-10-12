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

package com.holzyn.actor.domain.worldgen.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务 VO（GET /api/worldgen/tasks/{taskId} 与 retry/skip 返回值）。
 * <p>职责：任务整体状态 + 节点进度（按执行顺序）+ Token/耗时汇总（节点级 Token 账本，方案 §3.3）。</p>
 */
@Data
public class WorldGenTaskVO {

    /** 任务 ID */
    private Long id;

    /** 工作流编码 */
    private String workflowCode;

    /** 工作流名称 */
    private String workflowName;

    /** 归属项目 ID */
    private Long projectId;

    /** 任务状态（running/succeeded/failed/canceled） */
    private String status;

    /** 任务输入快照（解析后对象：premise/genre/language + 扩展） */
    private Object input;

    /** 当前执行节点 ID（失败/断点定位） */
    private String currentNodeId;

    /** 创建时间 */
    private String createdAt;

    /** 完成时间 */
    private String finishedAt;

    /** Token 汇总（输入） */
    private Long tokenIn = 0L;

    /** Token 汇总（输出） */
    private Long tokenOut = 0L;

    /** 耗时汇总（毫秒） */
    private Long durationMs = 0L;

    /** 节点进度（按执行顺序） */
    private List<WorldGenNodeVO> nodes = new ArrayList<>();
}
