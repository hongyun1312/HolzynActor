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
 * 节点 VO（GET 任务详情内嵌：节点当前状态 + 尝试历史）。
 * <p>「当前状态」= 该节点最新一次尝试（id 最大）；output 为最新成功尝试的产物 JSON（解析后对象）。</p>
 */
@Data
public class WorldGenNodeVO {

    /** 节点 ID */
    private String nodeId;

    /** 节点中文名 */
    private String label;

    /** 节点类型（agent/script） */
    private String stepType;

    /** 节点状态（=最新尝试状态） */
    private String status;

    /** 最新尝试的重试次数 */
    private Integer retryCount;

    /** 尝试总数 */
    private Integer attemptCount;

    /** 错误信息（最新失败尝试） */
    private String error;

    /** 产物 JSON（最新成功尝试的输出，解析后对象；无则 null） */
    private Object output;

    /** 尝试历史（按执行顺序） */
    private List<WorldGenAttemptVO> attempts = new ArrayList<>();
}
