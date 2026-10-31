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

import com.holzyn.actor.domain.project.model.WorldInitStep;

import java.time.LocalDateTime;

/**
 * 世界初始化单步状态 VO（初始化向导步骤条的一项）。
 *
 * <p>职责：把「步骤定义（静态）」与「步骤状态（动态）」合成前端可直接渲染的一行——
 * 前端不再自己维护步骤名/顺序/是否必需，也不从状态反推"能不能点"。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 *
 * @param code        步骤编码（{@code WorldInitStep.code}）
 * @param label       步骤中文名
 * @param index       执行顺序（1 开始）
 * @param required    是否必需步骤
 * @param desc        步骤说明（向导当前步引导文案）
 * @param status      状态：PENDING / RUNNING / DONE / FAILED / SKIPPED
 * @param satisfied   实时数据是否已满足该步产出（true=已有数据，即便状态行为空）
 * @param itemCount   最近一次执行产出的数据条数
 * @param message     结果说明或失败原因（可空）
 * @param attemptCount 累计执行次数
 * @param finishedAt  最近一次完成时间（可空）
 */
public record WorldInitStepVO(
        String code,
        String label,
        int index,
        boolean required,
        String desc,
        String status,
        boolean satisfied,
        int itemCount,
        String message,
        int attemptCount,
        LocalDateTime finishedAt
) {

    /** 状态：未执行 */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：已完成 */
    public static final String STATUS_DONE = "DONE";

    /** 状态：执行失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：用户跳过（仅可选步骤） */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /**
     * 静态定义 + 动态状态 → 步骤 VO（统一装配点，避免各处手拼）。
     *
     * @param step         步骤定义
     * @param status       状态（空视为 PENDING）
     * @param satisfied    实时数据是否已满足
     * @param itemCount    产出条数
     * @param message      结果说明
     * @param attemptCount 执行次数
     * @param finishedAt   完成时间
     *
     * @return 步骤 VO
     */
    public static WorldInitStepVO of(WorldInitStep step, String status, boolean satisfied,
                                     int itemCount, String message, int attemptCount,
                                     LocalDateTime finishedAt) {
        String resolved = status == null || status.isBlank() ? STATUS_PENDING : status;
        // 实时数据已满足但状态行没记过（历史世界 / 手工建库）→ 对外呈现为已完成，
        // 否则向导会把"数据明明都在"的步骤显示成"未执行"，用户重复点会白烧 AI。
        //
        // 注意 SKIPPED 不参与这个提升：跳过是"这个世界的档位不需要这一步"或"用户主动跳过"，
        // 抬成「已完成」会让休闲档的宏观步在向导里显示成跑过了（实测），也把跳过原因弄丢。
        String shown = resolved;
        String shownMessage = message;
        if (satisfied && (STATUS_PENDING.equals(resolved) || STATUS_FAILED.equals(resolved))) {
            shown = STATUS_DONE;
            // 用户补齐数据后"满足"会盖掉失败状态，但失败文案是上一轮的，留着会让已完成步骤
            // 还挂着一条红字原因（实测截图就是这样），所以一并清掉。
            shownMessage = null;
        }
        return new WorldInitStepVO(step.code(), step.label(), step.index(), step.required(), step.desc(),
                shown, satisfied, itemCount, shownMessage, attemptCount, finishedAt);
    }
}
