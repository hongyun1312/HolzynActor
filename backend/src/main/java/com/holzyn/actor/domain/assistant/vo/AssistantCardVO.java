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

package com.holzyn.actor.domain.assistant.vo;

/**
 * 数据卡片载荷（结构化数据展示；前端按 type 渲染对应卡片组件）。
 *
 * @param type   卡片类型：world 世界概览 / character 角色 / location 地点 / macro 宏观 / event 事件
 * @param title  卡片标题
 * @param fields 字段列表（label/value 对）
 */
public record AssistantCardVO(String type, String title, java.util.List<Field> fields) {

    /**
     * 卡片字段。
     *
     * @param label 字段名
     * @param value 字段值（文本）
     */
    public record Field(String label, String value) {
    }
}
