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

package com.holzyn.actor.ai.decision.protocol;

import com.holzyn.actor.ai.decision.DecisionResponse;
import com.holzyn.actor.ai.decision.DecisionState;

import java.util.List;

/**
 * 决策模型<b>线协议</b>（wire protocol）适配层——只负责「一次 HTTP 往返的报文形状」。
 *
 * <p><b>为什么需要协议层</b>：不同开源决策模型的服务端接口形状并不一致，实测可归为两族：</p>
 * <ul>
 *   <li><b>{@code nanojev-batch}</b>：一次请求携带多个 state（{@code {"states":[...]}}），
 *       响应含 {@code execution} 元数据，天然批量；</li>
 *   <li><b>{@code jev-systemone}</b>：一次请求只带一个 state
 *       （{@code {"state":..., "questions":{...}}}），boolean 题型名为 {@code noul}，
 *       响应为 {@code {"answers":{...}}}、无 {@code execution}。</li>
 * </ul>
 * <p>业务侧只认内部统一模型 {@link com.holzyn.actor.ai.decision.DecisionState} /
 * {@link DecisionResponse}，协议差异全部收敛在实现类内——新增一家决策模型时，
 * 只需（在形状相同时）复用协议或新增一个实现，不必触碰判定门面、A/B 设施与前端。</p>
 *
 * <p><b>约定</b>：实现必须是<b>无状态、可并发调用</b>的（Spring 单例 Bean，被多线程判定链路共享）。</p>
 */
public interface DecisionProtocol {

    /** 协议 ID（配置值；用于日志/trace 与调试页展示）。 */
    String id();

    /**
     * 默认 evaluate 路径（相对 base-url；空串表示直接 POST 到 base-url）。
     * <p>可被 {@code holzyn.actor.ai.decision.evaluate-path} 覆盖（同协议不同服务端路径差异，
     * 例如自托管网关 {@code /nanojev/evaluate} vs 官方脚本 {@code /api/evaluate}）。</p>
     */
    String defaultEvaluatePath();

    /**
     * 是否支持「多 state 进一次请求」。
     * <p>{@code true}=客户端按批上限切分后每批一次往返；{@code false}=客户端逐 state 调用后合并。</p>
     */
    boolean batched();

    /**
     * 构造请求体 JSON。
     *
     * @param states 待判定 state（{@link #batched()} 为 false 时，调用方保证只有 1 个）
     *
     * @return 请求体 JSON 文本
     */
    String buildBody(List<DecisionState> states);

    /**
     * 解析响应体为内部统一模型。
     *
     * @param body 响应体原文
     *
     * @param sent 本次请求发出的 state（用于补全响应缺失的 stateId 与合成元数据）
     *
     * @return 统一响应；结构不可识别返回 {@code null}（调用方 fail-closed）
     */
    DecisionResponse parse(String body, List<DecisionState> sent);

    /**
     * 健康检查路径（相对 base-url；{@code null}=该协议无独立健康端点，
     * 由适配器退化为「端口可连 + 一次最小 evaluate 试探」）。
     */
    String healthPath();

    /**
     * 请求参数快照（写入 trace 的 params_snapshot，供调度记录回溯）。
     *
     * @param states state 列表
     *
     * @return 形如 {@code states=1, questions=3, paths=7} 的快照
     */
    default String paramsSnapshot(List<DecisionState> states) {
        int questions = 0;
        int paths = 0;
        for (DecisionState s : states) {
            questions += s.questionCount();
            paths += s.pathCount();
        }
        return "states=" + states.size() + ", questions=" + questions + ", paths=" + paths;
    }
}
