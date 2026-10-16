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
 * 对谈上下文配置视图（M4：作者注释 + 滚动窗口摘要 + 世界书统计）。
 *
 * @param authorNote           作者注释内容（空串=未启用）
 * @param authorNoteDepth      作者注释位置：0=system 最前 / 1=system 末尾 / 2=user 段首
 * @param authorNoteFrequency  作者注释频率（每 N 条消息注入一次；1=恒注入）
 * @param summary              滚动窗口摘要缓存（空串=尚未生成）
 * @param summaryMessageCount  已折叠进摘要的消息条数（增量摘要水位）
 * @param historyWindow        滚动窗口条数（超窗前段折叠为摘要）
 * @param worldbookCount       会话世界书条目数（含停用）
 */
public record DialogueContextConfigVO(String authorNote, Integer authorNoteDepth, Integer authorNoteFrequency,
                                      String summary, Integer summaryMessageCount, Integer historyWindow,
                                      Long worldbookCount) {
}
