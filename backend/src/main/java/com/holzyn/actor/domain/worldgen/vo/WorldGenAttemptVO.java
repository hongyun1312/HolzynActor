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

/**
 * 节点尝试 VO（GET 任务/节点详情内嵌：一次执行的完整记录）。
 */
@Data
public class WorldGenAttemptVO {

    /** 尝试行 ID */
    private Long id;

    /** 节点 ID */
    private String nodeId;

    /** 节点执行序号（1 起） */
    private Integer nodeOrder;

    /** 节点类型（agent/script） */
    private String stepType;

    /** 状态（running/succeeded/failed/skipped） */
    private String status;

    /** 重试次数（0=首次执行） */
    private Integer retryCount;

    /** 错误信息 */
    private String error;

    /** 输入 Token 数 */
    private Integer tokenIn;

    /** 输出 Token 数 */
    private Integer tokenOut;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 节点输入（填充占位符后的文本） */
    private String input;

    /** 节点输出 JSON 文本 */
    private String output;

    /** 尝试创建时间 */
    private String createdAt;
}
