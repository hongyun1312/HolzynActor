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
 * 工作流定义列表 VO（GET /api/worldgen/workflows）。
 */
@Data
public class WorldGenWorkflowVO {

    /** 工作流 ID */
    private Long id;

    /** 工作流编码（如 worldgen_build_v1） */
    private String code;

    /** 工作流名称 */
    private String name;

    /** 定义版本号 */
    private Integer version;

    /** 节点数 */
    private Integer nodeCount;

    /** 启用标记（1 启用） */
    private Integer enabled;

    /** 时间 */
    private String updatedAt;
}
