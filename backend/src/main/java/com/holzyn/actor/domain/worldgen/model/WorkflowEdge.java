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

package com.holzyn.actor.domain.worldgen.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流边定义（edges_json 数组元素的数据模型）。
 * <p>对齐 DeterminFlow edge 概念：{@code source} → {@code target}；
 * 伪节点 {@code __start__} / {@code __end__} 表示链起点/终点（不参与执行）。
 * 执行顺序由解析器按边拓扑排序得到。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEdge {

    /** 边 ID */
    private String id;

    /** 源节点 ID（可为 __start__） */
    private String source;

    /** 目标节点 ID（可为 __end__） */
    private String target;
}
