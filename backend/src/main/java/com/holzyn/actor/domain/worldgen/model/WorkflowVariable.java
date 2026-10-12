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
 * 工作流输入变量定义（variables_json 数组元素的数据模型）。
 * <p>对齐 DeterminFlow WorkflowVariable 概念：{@code key} 为占位符名（如 premise/genre/language），
 * 任务创建时按 key 校验必填并从请求 inputs 冻结取值。JSON 键驼峰与字段一致（defaultValue）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVariable {

    /** 变量 key（占位符名，如 premise） */
    private String key;

    /** 变量中文名（如 题材创意） */
    private String name;

    /** 输入控件类型 */
    private String type;

    /** 默认值（language 默认「中文」） */
    private String defaultValue;

    /** 是否必填（premise/genre 必填；创建任务时校验） */
    private Boolean required = false;

    /** 说明文案 */
    private String description;

    /**
     * 是否必填（容错 null）。
     *
     * @return true=必填
     */
    public boolean required() {
        return Boolean.TRUE.equals(required);
    }
}
