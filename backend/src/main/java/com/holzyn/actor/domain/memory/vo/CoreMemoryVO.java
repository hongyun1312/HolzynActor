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

package com.holzyn.actor.domain.memory.vo;

import java.util.List;

/**
 * L3 核心记忆详情视图对象。
 * <p>职责：前端「核心记忆」分组的一次性数据——<b>静态基线只读渲染</b>（角色卡 persona +
 * brainProfile + active 目标 + 关键关系四源组合，不落库）+ <b>动态核心块列表</b>（可 CRUD）。</p>
 * <p>设计依据：《记忆系统分层重构 L1/L2/L3/归档 设计方案》§4.3/§7.1（静态基线不落此表，避免身份数据双源）。</p>
 *
 * @param characterRef 统一角色引用（special:{id}/crowd:{id}）
 *
 * @param baselineText 静态基线只读渲染文本（无内容=空串）
 *
 * @param blocks 动态核心块列表（重要度 desc）
 */
public record CoreMemoryVO(String characterRef, String baselineText, List<CoreBlockVO> blocks) {

    /**
     * 空态 VO（无归属/非法参数时返回）。
     *
     * @param characterRef 角色引用（可空）
     *
     * @return 空详情
     */
    public static CoreMemoryVO empty(String characterRef) {
        return new CoreMemoryVO(characterRef, "", List.of());
    }
}
