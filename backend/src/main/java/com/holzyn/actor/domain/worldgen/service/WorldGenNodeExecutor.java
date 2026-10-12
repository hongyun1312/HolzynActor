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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;

/**
 * 节点执行器抽象。
 * <p>职责：定义「执行一个节点」的统一契约——按 {@link #supportedNodeType()} 注册到
 * {@code WorldGenTaskRunner}，运行时按节点类型分发执行器：</p>
 * <ul>
 * <li><b>agent</b>：AI 生成节点。 由 {@link StubWorldGenNodeExecutor} 桩执行（占位 JSON）；
 * 替换为真实实现（AiProviderRouter.chatCompletion + json_mode + Schema 校验/修复/重试，
 * 经 AiCallContext.run(scene="worldgen", trace_id=taskId) 记账 + trace 贯穿）；</li>
 * <li><b>script</b>：转换节点。 桩执行； 真实实现（LocalArchiveService 检查点 /
 * WorldGenRenderer 合并渲染 / 6 维映射落库服务）。</li>
 * </ul>
 */
public interface WorldGenNodeExecutor {

    /**
     * 本执行器支持的节点类型（{@link WorldGenStepType#AGENT} / {@link WorldGenStepType#SCRIPT}）。
     *
     * @return 节点类型
     */
    String supportedNodeType();

    /**
     * 执行一个节点。
     *
     * @param ctx 节点执行上下文（任务/节点/填充输入/上游产物/模拟失败钩子）
     *
     * @return 节点执行结果（状态/输出 JSON/错误/Token 账本/耗时）
     */
    NodeExecutionResult execute(NodeExecutionContext ctx);
}
