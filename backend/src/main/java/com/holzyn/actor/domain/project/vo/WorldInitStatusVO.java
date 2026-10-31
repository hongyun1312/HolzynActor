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

package com.holzyn.actor.domain.project.vo;

import java.util.List;

/**
 * 世界初始化状态 VO（"这个世界是否完全初始化完成"的唯一判定结果）。
 *
 * <p>职责：给三个消费方同一个答案——世界画廊卡片角标、世界详情页顶部横幅、
 * 初始化向导步骤条。判定口径只有这里一处，避免"详情页说完成、向导说没完成"。</p>
 *
 * <p>完成判据：全部<b>必需</b>步骤已满足（DONE 或实时数据已满足）→ {@code complete=true}；
 * 必需步骤已满足但有可选步骤未完成 → {@code complete=true, degraded=true}（可进世界，
 * 只提示"降级"）。必需步骤缺一 → {@code complete=false}，{@code currentStepCode} 给出
 * 第一个未完成的必需步骤（向导据此定位）。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 *
 * @param projectId           项目 ID
 * @param worldName           世界观名称（无设定时回退项目名）
 * @param initialized         是否已经开始初始化（任一步骤不是 PENDING）
 * @param complete            是否初始化完成（全部必需步骤已满足）
 * @param degraded            是否降级完成（必需步骤完成、可选步骤未完成）
 * @param doneCount           已满足的步骤数（含跳过）
 * @param totalCount          步骤总数
 * @param requiredDoneCount   已满足的必需步骤数
 * @param requiredTotalCount  必需步骤总数
 * @param currentStepCode     下一个待完成步骤编码（全部完成时为 null）
 * @param message             面向用户的一句话结论
 * @param steps               逐步状态（按执行顺序）
 */
public record WorldInitStatusVO(
        Long projectId,
        String worldName,
        boolean initialized,
        boolean complete,
        boolean degraded,
        int doneCount,
        int totalCount,
        int requiredDoneCount,
        int requiredTotalCount,
        String currentStepCode,
        String message,
        List<WorldInitStepVO> steps
) {
}
