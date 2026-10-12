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

package com.holzyn.actor.domain.evaluation;

/**
 * Judge 评分器接口。
 * <p>实现：{@link JudgeChatClient}（独立云端模型，与生成模型不同家族）；
 * 测试可注入桩实现（固定返回/失败），BehaviorJudgeService 只依赖本接口。</p>
 */
public interface JudgeScorer {

    /**
     * 调用 judge 模型。
     *
     * @param systemPrompt 评分员系统提示（rubric 与偏见规避协议）
     *
     * @param userPrompt 样本输入（角色/刺激/上下文/输出）
     *
     * @return 模型原始输出文本（解析由 {@link JudgeScoreParse} 承担）
     *
     * @throws com.holzyn.actor.ai.AiCallException 调用失败（超时/网络/服务端错误）
     */
    String call(String systemPrompt, String userPrompt);

    /** 实际 judge 模型名（批次记录/报告展示）。 */
    String modelName();
}
