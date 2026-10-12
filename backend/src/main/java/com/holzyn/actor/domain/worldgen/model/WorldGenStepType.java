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

package com.holzyn.actor.domain.worldgen.model;

/**
 * 世界观生成节点类型常量（对齐 DeterminFlow 的 Agent / Script 两类 Core Node）。
 * <p>agent 节点：AI 生成；
 * script 节点：转换器。</p>
 */
public final class WorldGenStepType {

    private WorldGenStepType() {
    }

    /** AI 生成节点 */
    public static final String AGENT = "agent";

    /** 脚本/转换节点 */
    public static final String SCRIPT = "script";

    /**
     * 校验是否为合法节点类型。
     *
     * @param type 节点类型
     *
     * @return true=合法
     */
    public static boolean isValid(String type) {
        return AGENT.equals(type) || SCRIPT.equals(type);
    }
}
