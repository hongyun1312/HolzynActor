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

/**
 * 世界初始化工作流结果 VO（世界初始化）。
 * <p>职责：承载初始化 6 步全部执行完成的汇总结果（失败步骤已跳过并记录日志）。</p>
 *
 * @param projectId 项目 ID
 *
 * @param locations 地点数（本次提取并入库）
 *
 * @param cards 角色卡数（本次新生成）
 *
 * @param npcs 普通型 NPC 数（本次生成并入库）
 *
 * @param relations 关系数（本次写入）
 *
 * @param gameTimeText 设置后的世界历时间文本（如「星历 1050 年 3 月 12 日」）
 *
 * @param vectorized 本次完成向量化的知识文档数
 */
public record WorldInitResultVO(
        Long projectId,
        int locations,
        int cards,
        int npcs,
        int relations,
        String gameTimeText,
        int vectorized
) {
}
