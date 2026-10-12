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
 * 世界观生成工作流状态常量（任务级 + 节点尝试级统一命名）。
 * 节点尝试状态：{@link #RUNNING} / {@link #SUCCEEDED} / {@link #FAILED} / {@link #SKIPPED}。
 * 均以字符串落库（H2 VARCHAR），与既有系统状态列（如 actor_conversation.status）一致。</p>
 */
public final class WorldGenStatus {

    private WorldGenStatus() {
    }

    /** 任务/节点：执行中 */
    public static final String RUNNING = "running";

    /** 任务/节点：成功（全部节点成功） */
    public static final String SUCCEEDED = "succeeded";

    /** 任务/节点：失败（任务=任一节点失败且未重试成功） */
    public static final String FAILED = "failed";

    /** 任务：已取消 */
    public static final String CANCELED = "canceled";

    /** 节点：已跳过（skip 端点，下游继续） */
    public static final String SKIPPED = "skipped";

    /**
     * 任务是否处于终态（后续不可再执行节点链）。
     *
     * @param status 任务状态
     *
     * @return true=终态
     */
    public static boolean isTaskTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status);
    }
}
