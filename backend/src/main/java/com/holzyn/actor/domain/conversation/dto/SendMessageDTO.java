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

package com.holzyn.actor.domain.conversation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发送消息请求 DTO。
 * <p>职责：承载用户发送的单条消息内容（content 必填），
 * 群聊场景可选携带 forceCharacterId 指定发言人覆盖（AI 调度结果被该角色覆盖）。</p>
 *
 * @param content 消息正文（必填）
 *
 * @param forceCharacterId 指定发言人角色 ID（可选，仅群聊生效；为空则按 AI 发言欲望调度）
 */
public record SendMessageDTO(
        @NotBlank(message = "消息内容不能为空") String content,
        Long forceCharacterId
) {
}