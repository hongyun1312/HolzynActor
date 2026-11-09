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

/**
 * 工作流节点定义摘要（GET /api/worldgen/workflows 的 nodes 字段）。
 *
 * <p>为什么要有它：任务只会在节点开始执行时才有状态行，尚未执行到的节点在
 * 前端是一个占位块——占位块若没有名字，就只能显示「等待执行」，
 * 用户看不到「接下来会发生什么」。这里把定义的节点名一次性给前端，
 * 让等待中的节点也能显示自己的阶段名（顺序即执行顺序）。</p>
 *
 * @param nodeId   节点 ID（与任务的 nodeId 同源，用于对齐已执行节点）
 * @param label    节点中文名（展示用）
 * @param stepType 节点类型（agent / script，仅作展示细分）
 */
public record WorldGenWorkflowNodeVO(String nodeId, String label, String stepType) {
}
