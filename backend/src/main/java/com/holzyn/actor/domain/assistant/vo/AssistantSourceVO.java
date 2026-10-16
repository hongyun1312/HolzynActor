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
 * 来源标注（回答末尾可展开的「这条信息来源」）。
 *
 * @param source    数据源（如「WorldGraphQueryService·角色视角」「知识库·角色知识卡」）
 * @param detail    补充说明（如更新时间/游戏时刻/文档路径）
 */
public record AssistantSourceVO(String source, String detail) {
}
