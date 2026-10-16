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

package com.holzyn.actor.domain.dialogue.vo;

/**
 * 对谈上下文用量视图（M4：token 用量实时显示）。
 * <p>本地估算（复用 {@code TokenEstimator}：CJK≈0.55 token/字符 + ASCII≈0.28 + 单词开销），
 * 供前端上下文面板展示；真实 token 用量由供应商返回并记入 actor_usage_log
 * （scene=dialogue_chat/dialogue_greeting/dialogue_summary）。</p>
 *
 * @param sessionId      会话 ID
 * @param messageCount   会话消息条数
 * @param contextChars   上下文总字符数（消息正文 + 摘要 + 作者注释 + 世界书命中片段）
 * @param estimateTokens 估算总 token（各段估算合计）
 * @param summaryChars   摘要字符数（0=未生成）
 * @param authorNoteChars 作者注释字符数（0=未启用/频率未命中）
 * @param worldbookChars 世界书命中片段字符数（0=无命中）
 */
public record DialogueUsageVO(Long sessionId, Integer messageCount, Integer contextChars, Integer estimateTokens,
                              Integer summaryChars, Integer authorNoteChars, Integer worldbookChars) {
}
